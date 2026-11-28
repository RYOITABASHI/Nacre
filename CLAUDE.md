# Nacre — Developer Keyboard for Android

## Architecture Decisions (変更時は必ず更新)

| 判断 | 理由 | 影響範囲 |
|------|------|----------|
| ComposeView は final | subclass不可 → addOnAttachStateChangeListener で ViewTree owners を stamp | NacreInputMethodService.kt |
| compileSdk = 34 | Termux aapt2 v2.19 が android-35 非対応 | 全 build.gradle.kts |
| core-ktx = 1.13.1 | 1.15.0 は compileSdk 35 必須 | ime-core/build.gradle.kts |
| VSplit 回転角 8° | 15° ではキーが画面外にクリップ | VSplitLayout.kt |
| Row 4 は 8キー偶数 | V-Split の size/2 分割で 4:4 均等にするため | KeymapConfig.kt, PresetProvider.kt |
| Shift キー明示配置 | swipe のみでは Shift+文字の同時操作不可 | 全レイアウト Row 4 |
| GL は右端寄り | Space 隣だと誤タップで IME 切替が頻発 | 全レイアウト Row 4 |
| キー高さ 56dp | 52dp では touch target が小さすぎ（Material 最小 48dp） | KeyboardScreen.kt, VSplitLayout.kt |
| CI で sed で aapt2 行削除 | gradle.properties の Termux パスが CI で存在しない | .github/workflows/build-android.yml |
| 変換は Mozc モデル準拠の格子（単語コスト＋接続コストそのまま、前向き Viterbi＋後ろ向き A* の N-best） | 旧ビームサーチは接続コスト /2・長さボーナス・ひらがな補正などの独自補正で Mozc のコストを崩していた（210文ベンチ top-1 21.4%→70.0%） | NacreDictionary.kt (latticeConvert / rankedConversions) |
| mozc_dict.bin は同じ表記の品詞違い（DictEntry.variants）を保持 | 旧変換が（読み, 表記）で重複除去し、接続助詞の「て」などが消えて接続コストが破綻していた。Mozc b17e284（接続行列と同じ版）から復元 | tools/dict/restore_mozc_pos_variants.py, NacreDictionary.kt |
| 人名コスト −1500 補正は廃止 | id 1921..1923 だけに効き 齋藤>斎藤 のように Mozc のコストを歪めていた | NacreDictionary.kt |
| 数詞は格子に「組み立てた数」ノードを足す（アラビア数字優先） | 辞書は単独の数しか持たず にじゅうご→二重5 に割れていた。Gboard の第一候補はアラビア数字。連濁形（びゃく・ぜん）は さん の後だけ認める（ごぜん=午前 を 5000 にしない） | NumberReading.kt, NacreDictionary.addNumberNodes |
| 同音異義は Wikipedia 由来の共起表で N-best を並べ替える | Mozc OSS の collocation.txt は40行の見本のみ。述語は表記の読みも照合（良い(よい) が いい の場面で加点されない）。重み 300 は本ベンチのみで選定 | Collocations.kt, assets/dict/collocations.tsv.gz, tools/dict/build_collocations.py |

## Module Structure

```
app/          — メインアプリ (設定画面, セットアップウィザード)
ime-core/     — IME サービス本体 (InputEngine, KeyboardScreen, TrackballView, FlickInputPad)
ime-config/   — キーマップ定義, テーマ, 設定永続化
ime-ai/       — AI アドオン (whisper.cpp JNI, llama.cpp JNI, AIDL)
```

## Key Layout (Base Layer Row 4)
`[▲(0.5x)][▼(0.5x)][Fn][⇧][Space(2x)][Alt] [⌫(1.2x)][Enter(1.2x)][GL][.]`

## Build
- Termux: `./gradlew :app:assembleDebug`
- CI: GitHub Actions が gradle.properties から aapt2 行を sed で削除してビルド

## KenLM 5-gram 言語モデル（重要！）

- **訓練済みモデルあり**: GitHub Releases `v0.1.0-models` に `japanese-5gram.klm` (561MB)
- **訓練元**: Wikipedia日本語ダンプ → MeCab形態素解析 → KenLM 5-gram
- **訓練ワークフロー**: `.github/workflows/train-kenlm.yml` (手動トリガー)
- **ダウンロードURL**: `https://github.com/RYOITABASHI/Nacre/releases/download/v0.1.0-models/japanese-5gram.klm`
- **自動ロード**: `NacreInputMethodService.kt` がIME起動時に以下を検索:
  1. `filesDir/models/japanese-5gram.klm` (アプリ内部)
  2. `Downloads/kenlm-light/japanese-5gram.klm` (外部)
  3. `Downloads/japanese-5gram.klm` (外部)
- **ModelDownloader.kt**: `downloadKenLm()` でGitHub Releasesから自動ダウンロード
- **変換への効果**: Viterbi内のインクリメンタルスコアリング + 事後リスコアリングで変換精度大幅向上

## ローマ字変換ルール（JapaneseEngine.kt）

| 入力 | 出力 | ルール |
|------|------|--------|
| nn (常に) | ん | n2つ消費。nn+母音→ん+母音 (nni→んい, nna→んあ) |
| nn+子音 (nnk,nnd) | ん+k, ん+d | 同上（n2つ消費、子音は次へ） |
| nn末尾 | ん | n2つ消費 |
| ny+母音 (nya,nyu) | にゃ, にゅ | テーブルマッチにフォールスルー |
| n+子音 (nk,nb) | ん+k, ん+b | n1つ消費、子音保持 |
| wo | を | 標準ローマ字（変換段階でうぉ/お読み替え候補生成） |

## 辞書・変換精度改善

- **静的バイグラム**: `dict/bigrams.tsv` — 頻出日本語コロケーション100+
- **かな読み替え**: `を→うぉ/お`, `ぢ↔じ`, `づ↔ず` のバリアント自動生成
- **辞書拡充**: `dict/slang_words.tsv` — テック用語、固有名詞、現代スラング
- **変換格子**: 読みごとに最大40語（＋その品詞違い）、未知1文字ノード cost 12000、接頭詞で終わる経路 +3000
- **予測**: 変換 N-best（上位12）＋補完（読みを延長する語、1語経路コスト −500 ＋余り1かなごと +200）＋履歴を同一コスト尺度で並べる
- **補完の価格**: 品詞違いのうち最も安い1語経路で評価（感動詞の ありがとう 等）
- **共起表**: 名詞＋格助詞＋述語（K=漢字語幹:読み語幹、R=読み語幹）、件数10以上 約62万キー、ソート済みハッシュ配列で約6MB
- **ベンチ**: 調整用 `tools/benchmark/sentences.tsv`（210文。`|` 区切りは別解で先頭が厳密解）と `completions.tsv`（補完30件）。ホールドアウト `heldout.tsv`（同音異義40・数詞12）と `completions_heldout.tsv`（20件）は調整に使わない

## Known Constraints
- AIDL Java stubs are hand-written (aidl compiler is x86-64 only)
- whisper.cpp / llama.cpp JNI bridges are in ime-ai/src/main/cpp/
- Physical keyboard detection uses InputDevice scanning
- Foldable detection: hingeAngle sensor + screen width >= 500dp → VSplit
