# Nacre IME — タイピング・音声入力の限界追求設計

**Date**: 2026-04-06
**Goal**: タイピング変換をATOK同等、音声入力をTypeless同等以上に引き上げる
**Approach**: ボトムアップ（リファクタ → KenLM 3-gram → Viterbi → 学習 → 音声 → UI）

---

## 1. リファクタリング — NacreDictionary分割

### 1.1 背景

NacreDictionary.kt が2,267行で辞書管理・Viterbi・ユーザー学習・KenLMリスコア・英語マッチ・予測・変換のすべてを担当している。改善を安全に入れるため、責務ごとに分割する。

### 1.2 分割先

| 新ファイル | 責務 | 推定行数 |
|---|---|---|
| `DictionaryManager.kt` | 辞書ロード、エントリ検索、接頭辞マッチ、接続コスト行列 | ~500 |
| `ViterbiEngine.kt` | ビーム探索、パス構築、コスト計算、文節分割 | ~600 |
| `UserLearner.kt` | unigram/bigram/trigram/4-gram boost、時間減衰、永続化 | ~400 |
| `CandidateRanker.kt` | KenLMリスコア、POS文脈コスト、LLMリランク統合、最終ソート | ~300 |
| `EnglishMatcher.kt` | 英語辞書検索、スペル補正、edit distance | ~300 |
| `ConversionPipeline.kt` | convert/predictの統合パイプライン（公開API） | ~200 |

### 1.3 依存関係

```
ConversionPipeline (公開API — InputEngineが呼ぶ)
  ├── DictionaryManager (辞書エントリ提供)
  ├── ViterbiEngine (セグメンテーション)
  │     └── DictionaryManager (候補取得)
  ├── UserLearner (ブースト計算)
  ├── CandidateRanker (最終ランキング)
  │     ├── KenLmScorer (LMスコア)
  │     └── LlmReranker (非同期リランク)
  └── EnglishMatcher (英語候補)
```

### 1.4 API互換性

既存のpublic APIシグネチャ（`convert()`, `predict()`, `recordSelection()`, `commitCandidate()`）は `ConversionPipeline` がそのまま引き継ぐ。InputEngine側の変更は import パスの変更のみ。

### 1.5 配置先

すべて `ime-core/src/main/kotlin/space/manus/nacre/ime/input/` に配置（既存パッケージを維持）。

---

## 2. KenLM 3-gramバンドル

### 2.1 目標

全ユーザーがインストール直後からKenLMの恩恵を受けられるようにする。

### 2.2 モデル仕様

| 項目 | 3-gram (新規バンドル) | 5-gram (既存外部DL) |
|---|---|---|
| サイズ | 20-30MB | 561MB |
| 精度 | 85-90% of 5-gram | 100% (ベースライン) |
| バンドル | APK内 assets | 外部DL |
| 形式 | trie compressed binary | trie compressed binary |

### 2.3 訓練パイプライン

既存の `.github/workflows/train-kenlm.yml` を拡張:

1. Wikipedia日本語コーパス → MeCab分かち書き
2. `lmplz -o 3 --prune 0 2 3` (bigram閾値2、trigram閾値3)
3. `build_binary -s trie model.arpa model.klm` (trie圧縮)
4. GitHub Releases にアップロード + `ime-ai/src/main/assets/models/` にコピー

### 2.4 ランタイム統合

```kotlin
// KenLmScorer に追加
fun selectModel(): String {
    val external5gram = findExternalModel("japanese-5gram.klm")
    if (external5gram != null) return external5gram

    val bundled3gram = extractBundledModel("japanese-3gram.klm")
    return bundled3gram
}
```

初回起動時に assets → filesDir/models/ にコピー（assets から直接 mmap 不可のため）。

### 2.5 重み分岐

| パラメータ | 3-gram使用時 | 5-gram使用時 |
|---|---|---|
| VITERBI_LM_WEIGHT | 2200 | 3000 |
| リスコア dynamicWeight (短文) | 1500 | 1800-2200 |
| リスコア dynamicWeight (長文) | 2500 | 3200 |
| contextMultiplier | ×1.0-1.15 | ×1.0-1.25 |

### 2.6 APKサイズ影響

現状 ~50MB → 70-80MB。Gboard (200MB+)、ATOK (100MB+) と比較して許容範囲。

---

## 3. Viterbi改善 — ATOK同等の変換品質

### 3.1 動的ビーム幅

```kotlin
fun dynamicBeamWidth(inputLength: Int, ambiguity: Float, hasLM: Boolean): Int {
    val base = when {
        inputLength <= 6 -> if (hasLM) 40 else 25
        inputLength <= 10 -> if (hasLM) 35 else 22
        else -> if (hasLM) 30 else 18
    }
    // 同音異義語が多い場合はビーム拡大
    return (base * (1.0 + ambiguity * 0.5)).toInt().coerceAtMost(60)
}
```

ambiguity = 同じ読みに対する辞書エントリ数 / 平均エントリ数。

### 3.2 文節レベルセグメンテーション（長文対応）

ATOK最大の強みである長文一括変換を実現:

1. **助詞・接続詞で文節境界を推定**: 入力かなの中で「を」「に」「が」「は」「で」「と」「も」「の」「へ」+ その直後が文節境界候補
2. **文節単位Viterbi**: 長文（12文字以上）を文節境界で分割し、文節ごとにViterbiを適用
3. **文節間接続**: 文節間のPOS接続コスト + KenLMスコアで最適な文節境界を選択
4. **フォールバック**: 文節分割で良い結果が出ない場合は全文Viterbiにフォールバック

### 3.3 Forward-Backward Rescoring

前方Viterbi後、後方からもスコアリング:

1. Forward pass: 通常のViterbi（左→右）
2. Backward pass: 右→左にViterbiを実行、各ノードにbackwardスコアを付与
3. Combined score: `forward_cost * 0.6 + backward_cost * 0.4`（前方を重視、後方は補助）

計算コスト: Viterbiが2回走るが、2回目はforward passの上位パスのみ再計算するので実質 ×1.3 程度。

### 3.4 タイムバジェット

```kotlin
val startTime = System.nanoTime()
val BUDGET_MS = 50L // 50ms上限

// Viterbiループ内
if ((System.nanoTime() - startTime) / 1_000_000 > BUDGET_MS) {
    break // ベスト候補で打ち切り
}
```

### 3.5 コスト計算の精緻化

- **動的接続コストスケール**: 短文（≤5文字）は `÷4`、中文（6-10）は `÷3`、長文（11+）は `÷2.5`
- **KenLM×長さボーナス相互作用**: LMスコアが上位25%の候補は長さボーナスを ×1.3 増幅
- **動的ひらがな関数語ボーナス**: 前のPOSが名詞→「の」は -3000、動詞→「の」は -1500

### 3.6 候補生成拡充

- **複合語分解**: 辞書にない長い読みを2-3語の組み合わせで解釈。既存の partial segmentation を拡張
- **送り仮名バリエーション**: 活用語の送り仮名パターンを辞書から自動生成（`行う`/`行なう`）
- **カタカナ語検出**: 長音（ー）含むカタカナ候補のコストを -1000 ブースト

---

## 4. ユーザー学習強化

### 4.1 時間減衰

```kotlin
data class BoostEntry(
    val count: Int,
    val lastUsedAt: Long, // epoch millis
    val sessionCount: Int
)

fun decayedBoost(entry: BoostEntry, now: Long): Int {
    val daysSinceUse = (now - entry.lastUsedAt) / (24 * 60 * 60 * 1000)
    val decayFactor = when {
        daysSinceUse <= 1 -> 1.0
        daysSinceUse <= 7 -> 0.85
        daysSinceUse <= 14 -> 0.6
        daysSinceUse <= 30 -> 0.35
        else -> 0.15
    }
    return (calculateRawBoost(entry.count) * decayFactor).toInt()
}
```

セッション起動時に全エントリの `lastUsedAt` をチェックし、30日超過分は削除。

### 4.2 POS付きbigram

```
現状: "東京→とうきょうたわー:東京タワー"
改善: "名詞:東京→とうきょうたわー:東京タワー"
```

同じ「の」でも名詞+の+名詞と動詞+の（名詞化）で文脈が変わる。POS大分類（名詞/動詞/助詞/形容詞/副詞/接続詞）を付与。

### 4.3 4-gram学習

3単語ではカバーできない定型表現:
- 「お/世話/に/なっております」（4語）
- 「ご/確認/いただけ/ますでしょうか」（4語）

上限1000エントリ、減衰あり。

### 4.4 アプリ別ドメイン学習

```kotlin
data class DomainBoost(
    val packageName: String, // "com.termux", "com.google.android.gm" など
    val boost: ConcurrentHashMap<String, BoostEntry>
)
```

EditorInfo の packageName をキーにドメイン分離。ターミナルでは技術用語、メールでは敬語が優先される。共通ドメイン + アプリ固有ドメインの2層。

### 4.5 リジェクト学習

```kotlin
fun onCandidateSelected(index: Int, candidates: List<ConversionCandidate>) {
    // 選んだ候補を学習
    recordSelection(candidates[index])

    // スキップされた候補（index > 0 の場合、0番目）にペナルティ
    if (index > 0) {
        recordRejection(candidates[0], penalty = 500)
    }
}

fun recordRejection(candidate: ConversionCandidate, penalty: Int) {
    val key = "${candidate.reading}:${candidate.surface}"
    rejectCount[key] = (rejectCount[key] ?: 0) + 1
    if (rejectCount[key]!! >= 3) {
        // 3回リジェクトで強ペナルティ
        permanentPenalty[key] = 2000
    }
}
```

### 4.6 訂正検出

変換確定 → 500ms以内にBS → 再変換のパターンを検出:

```kotlin
fun onCommit(candidate: ConversionCandidate) {
    lastCommit = candidate
    lastCommitTime = System.currentTimeMillis()
}

fun onBackspace() {
    if (lastCommit != null &&
        System.currentTimeMillis() - lastCommitTime < 500) {
        // 直前の確定を取り消して再変換 = 誤変換
        recordRejection(lastCommit!!, penalty = 1000)
    }
}
```

### 4.7 インライン辞書登録

- 候補バーの候補を長押し → BottomSheet表示
- フィールド: 読み（自動入力）、表記（自動入力）、品詞（名詞/固有名詞/動詞のDropdown）
- 登録先: `UserLearner.userDictionary`、減衰対象外フラグ付き
- UI: Compose ModalBottomSheet、最小限のフォーム

---

## 5. 音声パイプライン強化 — Typeless同等以上

### 5.1 リアルタイムストリーミング表示

**現状**: VAD無音検出まで結果が出ない（2-4秒遅延）

**改善**:
```
Audio → VAD → 音声チャンク検出
                ↓
         SenseVoice即時推論（チャンク単位）
                ↓
         Composing span に部分表示
                ↓
         次チャンク到着 → 前チャンクと結合リスコア
                ↓
         安定判定（3回一致）→ 確定コミット
```

VoiceInputManager のストリーミングロジックを改修:
- `SherpaRecognizer.processAudio()` をチャンク完了ごとに呼び出し
- 部分結果を `InputConnection.setComposingText()` で表示
- 安定した部分を `commitText()` で確定

### 5.2 句読点自動挿入の強化

3層の判定システム:

1. **ポーズベース（VAD）**: 無音長 0.3-0.8秒 = 読点候補、0.8秒+ = 句点候補
2. **パターンベース（既存80+パターン）**: 「ですか」→？、「ください」→。等
3. **KenLMスコアベース**: 文末に「。」「？」「！」「、」をそれぞれ付与してKenLMスコア比較

最終判定: 3層のスコアを重み付き合算。KenLM 0.5 + パターン 0.3 + ポーズ 0.2。

### 5.3 フィラー除去の強化

**文脈依存フィラー判定**:
```kotlin
fun isFillerInContext(word: String, preceding: String, following: String): Boolean {
    if (word == "あの") {
        // 「あの人」「あの時」→ 指示詞（フィラーではない）
        val nextIsNoun = kenLm.scorePOS(following, POS.NOUN) > threshold
        return !nextIsNoun
    }
    // 同様に「その」「この」「まあ」等
}
```

**ユーザー固有フィラー学習**:
- 音声入力後にユーザーが手動削除した単語を記録
- 5回以上削除されたパターンをフィラー辞書に自動追加

### 5.4 言い直し検出の強化

**意味的重複検出**:
```kotlin
fun detectRephrase(prev: String, current: String): Boolean {
    // KenLMで両方をスコアリング
    val scorePrev = kenLm.score(context + prev + continuation)
    val scoreCurr = kenLm.score(context + current + continuation)

    // 同じ文脈での代替表現 = 言い直し
    if (scoreCurr > scorePrev * 0.8 && hasSimilarMeaning(prev, current)) {
        return true
    }
    return false
}

fun hasSimilarMeaning(a: String, b: String): Boolean {
    // 文字レベルの類似度（Levenshtein ratio > 0.5）
    // または同じ読みで異なる表記
    return levenshteinRatio(a, b) > 0.5 ||
           toHiragana(a) == toHiragana(b)
}
```

**部分修正対応**: 「東京に…大阪に行きました」パターン:
1. 直前フレーズと現フレーズの共通接尾辞を検出（「に行きました」）
2. 異なる接頭辞部分のみ置換（「東京」→「大阪」）

### 5.5 誤変換自動補正

**ユーザー訂正学習**:
```kotlin
data class CorrectionPair(
    val recognized: String,  // SenseVoiceの出力
    val corrected: String,   // ユーザーの修正
    val count: Int,
    val lastUsed: Long
)

// 音声コミット後500ms以内の手動編集を検出
fun onTextChanged(old: String, new: String) {
    if (lastVoiceCommitTime != null &&
        System.currentTimeMillis() - lastVoiceCommitTime < 500) {
        val diff = computeDiff(old, new)
        correctionPairs.record(diff.removed, diff.added)
    }
}
```

5回以上の訂正で自動辞書に昇格。quickClean で自動適用。

**KenLMフィルタ**:
- 認識結果のKenLMパープレキシティが閾値（200）超の場合、音が近い代替候補を辞書探索
- 代替候補のパープレキシティが低ければ自動置換

### 5.6 常時リスニング

```kotlin
// VoiceInputManager に追加
var continuousMode: Boolean = false

fun toggleContinuousListening() {
    continuousMode = !continuousMode
    if (continuousMode) {
        startListening() // 時間制限なし
    } else {
        stopListening()
    }
}

// VAD駆動の省電力
// 無音時: VADのみ動作（~5mW）
// 音声検出時: SenseVoice起動（~200mW）
// 2秒無音: 段落区切り挿入
```

バッテリー10%未満で自動停止は維持。

---

## 6. PostProcessor統合改善

### 6.1 数字・日付処理の拡張

追加パターン:
- **時刻**: `十四時三十分` → `14:30`
- **電話番号**: 連続する数字読みをグルーピング（`ゼロサン` → `03`）
- **金額カンマ**: `二千五百円` → `2,500円`
- **1文字漢数字**: 文脈でカウンター/日付に隣接する場合のみ変換

### 6.2 技術用語辞書拡張

40件 → 200件+:
- プログラミング言語: Python, JavaScript, TypeScript, Rust, Go, Swift, C++, Java
- フレームワーク: React, Vue, Angular, Next.js, Express, Django, Flask, Spring
- ツール: Docker, Kubernetes, Terraform, Ansible, Jenkins, GitHub Actions
- クラウド: AWS, GCP, Azure, Vercel, Netlify, Cloudflare
- 略語: CI/CD, API, SDK, CLI, IDE, ORM, SSR, SSG, PWA

### 6.3 文体一貫性チェック

音声入力中の文体混在を検出:
- 直前3文の「です/ます」vs「だ/である」比率を計算
- フォーマル度 > 0.7 の文脈で「だ」が出たら「です」形の候補を優先
- 逆も同様（カジュアル文脈で「です/ます」→「だ/である」を候補に）
- あくまで候補の順序を変えるだけ。強制置換はしない。

---

## 7. テスト戦略

### 7.1 ユニットテスト

| コンポーネント | テスト内容 | 目標カバレッジ |
|---|---|---|
| ViterbiEngine | セグメンテーション精度、ビーム幅、タイムバジェット | 90%+ |
| UserLearner | 減衰計算、ドメイン分離、リジェクト学習 | 95%+ |
| CandidateRanker | KenLM 3g/5g分岐、スコア正規化 | 90%+ |
| PostProcessor | 既存128テスト + 新規パターン | 95%+ |
| 数字処理 | 時刻、電話番号、金額 | 100% |

### 7.2 統合テスト

- **変換精度ベンチマーク**: 1000文の標準テストセット（新聞記事 + 技術文書 + 日常会話）
- **音声パイプラインE2E**: 録音済み音声ファイル → 期待テキストとの一致率
- **レイテンシ**: Viterbi 50ms以内、音声チャンク→表示 500ms以内

### 7.3 回帰テスト

リファクタリング前後で `convert()` / `predict()` の出力が一致することを確認するスナップショットテスト。100入力 × 期待候補リストを記録。

---

## 8. 実装順序

| Phase | 内容 | 依存 |
|---|---|---|
| 1 | NacreDictionary分割リファクタリング | なし |
| 2 | KenLM 3-gram訓練パイプライン + バンドル | なし (Phase 1と並行可) |
| 3 | ViterbiEngine改善 | Phase 1 |
| 4 | UserLearner強化 | Phase 1 |
| 5 | CandidateRanker改善 (3g/5g分岐) | Phase 1, 2 |
| 6 | 音声ストリーミング + 常時リスニング | Phase 2 |
| 7 | PostProcessor強化 | Phase 2 |
| 8 | インライン辞書登録UI | Phase 1, 4 |
| 9 | 統合テスト + チューニング | Phase 1-8 |

Phase 1と2は並行実行可能。Phase 3-5はPhase 1完了後に並行可能（ファイルが分離されているため）。Phase 6-7はPhase 2完了後に並行可能。

---

## 9. リスク

| リスク | 影響 | 対策 |
|---|---|---|
| KenLM 3-gramの精度が不十分 | 変換品質の底上げが期待以下 | pruning閾値を調整（0 1 2 で再訓練）、4-gramも検討 |
| Viterbi改善でレイテンシ増大 | 入力遅延でUX悪化 | 50msタイムバジェットで強制打ち切り |
| リファクタリングでバグ導入 | 変換品質が一時的に劣化 | スナップショットテストで回帰検出 |
| 音声ストリーミングで部分結果のちらつき | UX悪化 | 安定判定の閾値調整（3回→5回一致） |
| APKサイズ増大 | DL数減少 | 3-gramモデルをAPK Expansion File化も選択肢として残す |
| ユーザー学習の減衰が強すぎる | よく使う単語が消える | 減衰係数をConfigRepositoryで調整可能にする |
