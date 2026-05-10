# Nacre — 開発特化カスタムキーボード (IME)

> **INTERNET権限なし。全てローカル処理。キーロガーになりえないIME。**

## 概要
Nacre（ネイカー）は、開発者・ターミナルユーザー向けのAndroidカスタムIMEアプリ。
Shellyの姉妹プロジェクトとして、モバイルでのコーディング体験を根本から改善する。

名前の由来: 真珠層（nacre）。Shelly（貝殻）の中にある最も美しい部分。

**ワンラインピッチ**: 物理トラックボール付きBlackBerryの操作感をソフトウェアで再現するIME。

## コアコンセプト
1. **中央トラックボール** — 最大の差別化。カーソル移動の苦痛を根本解決
2. **少ないキー、大きなターゲット** — Fittsの法則に基づく。不要なキーを排除し、1キーを大きく
3. **完全カスタマイズ可能なバインド** — 全キーにユーザー定義のアクション/マクロを割り当て可能
4. **V字分割レイアウト** — ハの字角度で左右分割。親指の自然な可動域に最適化
5. **開発者ファースト** — ターミナル操作、コード入力に最適化

## やらないことリスト
- スワイプ入力（Glide typing）— V字分割と相性が悪い
- 中国語/韓国語等のIME作り込み — AI変換で対応（後述）
- タブレット専用レイアウト — Z Fold対応に含める程度
- GIF/ステッカー/絵文字パネル — 開発者には不要
- カスタムフォント — スコープ外
- クラウド連携 — INTERNET権限なしを維持

## 競合との差別化
既存IMEにない独自領域:
- **常時表示の専用トラックボール領域** — Gboard等の「スペースバー長押し」方式とは根本的に異なる
- **IME内マクロ/キーバインドの完全カスタマイズ** — Key MapperはIMEではない。既存IMEでこれを持つものはない
- **Vim/Emacs/Terminalプリセット** — 開発者向けプリセットを持つIMEは存在しない

主要競合: Unexpected Keyboard（軽量・開発者向け）、Hacker's Keyboard（メンテ停止）、FlorisBoard（OSS・カスタマイズ性高）

## ターゲットユーザー
- Termux / Shelly でモバイル開発するユーザー
- SSHでリモートサーバーを操作するユーザー
- コードエディタ（VS Code Server等）をモバイルで使うユーザー

## 収益モデル

| 部分 | 価格 | 配布 |
|------|------|------|
| **基本IME**（QWERTY + トラックボール + 英日入力） | 無料・OSS (Apache-2.0) | Google Play / F-Droid / GitHub |
| **Nacre AI**（音声入力→LLM変換アドオン） | 買い切り 500-800円 | Google Play内課金 / GitHub Sponsors |

- 基本IMEはINTERNET権限なし。F-Droidコミュニティとの親和性を最大化
- AI機能は有料購入後にモデルをダウンロード → APKサイズ問題を解決
- 収益よりポートフォリオ/ブランディング目的。年間数万円の収益は期待しない

## 技術スタック

| 項目 | 決定 | 根拠 |
|------|------|------|
| 言語 | Kotlin | Android IME標準 |
| UI | Jetpack Compose + ComposeView | Thumb-Keyで実績あり。Composeで一貫して通す（Canvas移行しない） |
| 設定画面 | Jetpack Compose | アプリUIにはCompose最適 |
| 打鍵音 | SoundPool | 短音の低レイテンシ再生に特化。OGG形式、maxStreams=6 |
| アーキテクチャ | InputMethodService | Android IME API |
| ビルド | Gradle + GitHub Actions | CI/CD |
| 最小API | Android 8.0 (API 26) | VibrationEffect対応。シェア97%以上 |
| 配布 | Google Play + F-Droid + GitHub Releases | 3本立て。INTERNET権限不要で審査有利 |

### Compose IME の注意点
- `InputMethodService` は LifecycleOwner を持たない
  - `ServiceLifecycleDispatcher` + `ViewModelStore` + `SavedStateRegistryOwner` を自前実装（Thumb-Key方式）
  - Phase 1チェックリストに含める
- `onCreateInputView()` でComposeViewをキャッシュ
  - ただし Configuration 変更時（画面回転、Fold展開）に Composition が古くなる問題あり
  - `LocalConfiguration.current` を Composition 内で監視するか、`onConfigurationChanged()` でComposeViewを再生成
- Samsung固有の `imePadding()` 二重適用バグに注意
- `onEvaluateFullscreenMode()` で必ず `false` を返す（横画面フルスクリーン防止）
- `InputConnection` のメソッドはメインスレッドから呼ぶ必要がある → バックグラウンド処理の結果反映は `Dispatchers.Main`

### Canvas移行は行わない
Phase 1→2でCompose→Canvasのフルリライトは非現実的。Thumb-KeyはComposeのまま実用に耐えている。
パフォーマンス問題が顕在化した場合にのみ、部分的なCanvas化を検討する。

## レイアウト設計

### V字分割キーボード
```
  [Q][W][E][R][T]          [Y][U][I][O][P]
   [A][S][D][F][G]        [H][J][K][L][;]
    [Z][X][C][V]   [TR]    [B][N][M][,]
   [Tab][Fn][SP][GL] [TR] [BS][EN][.]

  左手側（15度傾斜）  中央  右手側（-15度傾斜）
```

**凡例:**
- `[TR]` = トラックボール（直径60dp、ヒットエリア+16dp拡張）
- `[Tab]` = Tab（ターミナル補完の生命線。常時1タップ）
- `[Fn]` = ファンクション（タップ=トグル、ホールド=一時切替、**長押し=Esc**）
- `[GL]` = IME切替（他のキーボードに切り替え）
- `[SP]` = スペース
- `[BS]` = バックスペース（左スワイプで単語削除）
- `[EN]` = Enter

### トラックボールと隣接キーの境界
- トラックボールと V/B キーの間に **最低8dpのデッドゾーン** を設ける
- 誤タッチ防止: 直前操作がトラックボールの場合、隣接キーの判定を50ms遅延

### 中央列の配置（決定）
標準QWERTY準拠。既存の筋記憶を破壊しない:
- **左手**: T, G, V（左人差し指担当）
- **右手**: Y, H, B, N（右人差し指担当）

### キーサイズ — Fittsの法則による根拠

Fittsの法則: MT = a + b * log2(2D/W)
- MT: 移動時間, D: ターゲットまでの距離, W: ターゲット幅
- キーを大きく(W↑)、距離を短く(D↓)することでMTを最小化

| キータイプ | 幅 | 高さ | 根拠 |
|-----------|------|------|------|
| 通常文字キー | 48dp | 52dp | Material Design最小タッチターゲット48dp。縦長にすることで親指の縦方向の可動域にフィット |
| スペース | 96dp | 52dp | 最頻用キー。2倍幅でFitts ID最小化 |
| BS / Enter | 56dp | 52dp | 頻用キーは少し大きく |
| Fnキー | 52dp | 52dp | モディファイアは確実に押せるサイズ |
| キー間隔 | 2-3dp | - | 密着させるとミスタッチ増加。ただし4dp以上は面積の無駄 |

### キーボード高さ制約
- **上限: 画面高さの35%以下** — Termux等のターミナルアプリで表示行数を確保
- スワイプで高さ調整可能（コンパクトモード: 200dp / 通常: 240dp）
- Gboard基準（約200dp）を意識

### 親指コンフォートゾーン

親指のCMC関節（手首付近）を中心とした弧状の可動域:
- **コンフォートゾーン**: CMC関節から15-45mm — 最も正確で疲れにくい
- **ストレッチゾーン**: 45-70mm — 到達可能だが精度低下
- **デッドゾーン**: 70mm+ — 到達困難、キー配置を避ける

注意: 上記値はエルゴノミクス文献からの理論値。スマホの持ち方（小指で支える等）では親指の起点が異なる。
→ **Phase 2でプロトタイプのタッチヒートマップ検証を行い、実測値で修正する。**

### キーキャップデザイン
- 角丸矩形（radius: 8dp）
- 押下時にスケールダウン（0.95x）+ 色変化
- 主文字: 中央 14-16sp
- 長押し文字: 右上 9-10sp — **ただし長押し時にGboard同等のポップアップバブルで拡大表示**
- レイヤー切替時にキーボード全体の色味を変更 + **レイヤー名テキスト（「Fn」「Fn2」）をキーボード隅に常時表示**
  - 色覚障害への配慮: 色だけに頼らず、テキスト+形状変化で識別

### スワイプ入力（Unexpected Keyboard方式）
- 各キーに**上下左右スワイプで最大4文字追加**割り当て可能
- デフォルト: **上スワイプ = 数字**（Q→1, W→2, E→3...）
- 左右スワイプ = 記号（キーごとにカスタマイズ可能）
- 下スワイプ = 代替文字（長押しと同じ文字。長押しより素早い）
- スワイプ閾値: 12dp（誤操作防止）
- スワイプ方向のポップアップ表示（キーキャップの四隅に小文字で表示）

### レイヤー設計
- **基本レイヤー**: アルファベット26文字 + スペース + BS + Enter + Fn + Tab + IME切替
  - **Tab**: Fnキーの左隣に常時配置（ターミナル補完の生命線）
  - **Esc**: Fnキー長押し = Esc（常時1タップ圏内）
- **Fnレイヤー（タップ=トグル/ホールド=一時）**:
  - ページ1: 数字(トップ行) + 記号(中段) + Ctrl+C/D/Z/矢印キー(ボトム行)
  - ページ2（Fn中にもう一度Fnタップ）: **F1-F12, Home, End, PgUp, PgDn, Insert, Delete**
- **長押し（350ms）**: 各キーに代替文字を割り当て可能

### V字角度

- デフォルト: 15度（人間工学研究で10-25度が有効。モバイル親指の弧にフィット）
- 片側7-10度が人間工学的に最も自然（合計14-20度）。15度はやや広めだがカスタマイズ可能
- 調整可能: 0度（水平）〜 30度
- 端末幅に基づくデフォルト値の自動調整（小型端末→角度大、大型端末→角度小）
- 実装: 個別座標計算（アフィン変換行列）。描画座標=タッチ判定座標で一貫性を確保
- **Phase 1から角度=0度のV字として実装** → Phase 2では角度パラメータを変えるだけ

### 片手モード
- 右手用/左手用の非分割レイアウト（キーを片側に寄せる）
- 設定 or エッジスワイプで切替
- V字角度は0度固定、キーサイズは維持
- **Phase 2で実装**（Phase 1はV字角度=0度の両手モードのみ）

### 省略するキー（Gboardとの差別化）
- 絵文字ボタン（不要）
- Google検索ボタン（不要）
- マイクボタン（Fnレイヤー or トラックボール上スワイプに統合）
- テーマ切替ボタン（設定画面で変更）
- 数字行（Fnレイヤーに移動）

## トラックボール仕様

### 実装方式
画面中央下部に直径60dpの円形タッチ領域。タッチパッド方式。
ヒットエリアは周囲+16dp拡張（見た目より大きな受付領域）。

### 実装: カスタムViewの `onTouchEvent` 直接処理
- スワイプ: 累積移動量+閾値ベースのステップ発火（自前処理）
- タップ/ダブルタップ/長押し: `GestureDetectorCompat` に委譲
- 加速度カーブ: ゆっくりスワイプ=1文字、素早くスワイプ=加速。S字カーブがデフォルト
- 操作中にドット（ポインタ）が追従する視覚フィードバック

### CDGain加速度カーブ

ポインタ速度に応じたゲインをシグモイド関数で制御:

```
G(v) = G_min + (G_max - G_min) / (1 + exp(-k * (v - v_mid)))
```

| パラメータ | 値 | 意味 |
|-----------|-----|------|
| G_min | 1.5 | 低速時ゲイン（精密操作） |
| G_max | 6.0 | 高速時ゲイン（長距離移動）— 10.0は60dpでは震えが増幅されるため6.0に |
| k | 0.015 | 遷移の急峻さ |
| v_mid | 300 | 遷移中心速度 (dp/s) |

- 低速（<100dp/s）: ほぼ1:1マッピング。精密カーソル操作
- 中速（100-500dp/s）: 滑らかにゲイン増加
- 高速（>500dp/s）: 最大6倍加速
- **ヒステリシス**: 高速→急停止時にゲインを急減させてオーバーシュート防止

### ジェスチャー体系

| 操作 | アクション | フィードバック |
|------|-----------|--------------|
| スワイプ | カーソル移動（上下左右） | 触覚フィードバック |
| タップ | タップ / 決定 | 短い振動（5ms） |
| ダブルタップ | 単語選択 | 振動 |
| ダブルタップ→スワイプ | 選択範囲拡張（Shift+矢印相当） | なし |
| 長押し（350ms） | コピペメニュー表示 | 中程度の振動（20ms） |
| 上スワイプ（フリック） | 音声入力起動 | 振動パターン |

**変更点（レビュー反映）:**
- ~~長長押し(1.0s)で音声入力~~ → 廃止。350msと1000msの区別がつかず誤操作多発
- 音声入力は **トラックボール上スワイプ（フリック）** に変更。明確に異なる操作で直感的
- ~~2本指スワイプでテキスト選択~~ → 廃止。60dpに2本指は物理的に困難
- テキスト選択は **ダブルタップ→そのままスワイプ** で選択範囲拡張（自然なUX）

### 感度設定
- カーソル移動速度: 調整可能（1x〜5x）
- 長押し判定時間: 調整可能（200ms〜800ms、デフォルト350ms）
- 加速度カーブ: 線形 / 指数関数 / S字カーブ（CDGain）

### カーソル移動の実装
- ターミナルアプリ（Termux等）: `sendDownUpKeyEvents(KEYCODE_DPAD_*)` でDPADキーイベント
- 通常アプリ: `inputConnection.setSelection()` で直接カーソル移動
- 対象アプリに応じて自動判定（`EditorInfo` で判別）
- **注意**: Termuxの `TerminalView` は独自 `BaseInputConnection` で `setSelection()` が効かない場合がある。Termux互換性テスト必須

## キーバインド設定

### 設定可能な項目
- 各キーの通常入力文字
- 各キーの長押し入力文字（350ms）
- Fnレイヤーの割り当て
- カスタムマクロ（キーシーケンス）
- ショートカット（Ctrl+系）

### プリセット
1. **Default** — 標準QWERTY + 一般的な記号配置
2. **Terminal** — Ctrl+C/D/Z, Tab補完, 矢印キー重視
3. **Vim** — Esc即座アクセス, hjkl移動, :コマンド
4. **Emacs** — Ctrl+A/E/K/Y, Meta+系
5. **Dvorak** — Dvorak配列（ギーク需要高）
6. **Colemak** — Colemak配列

### キーバインドの Import/Export
- 形式: `nacre-keymap.json`（キー配置 + スワイプ割り当て + マクロ + スニペット + 自動変換ルール）
- Export: ShareIntent or ファイル保存
- Import: ファイル選択 or ShareIntentで受信
- GitHubでコミュニティ共有可能（INTERNET権限なしでもブラウザ経由DL→インポート）

### マクロ例
- `git status` → 1キーで入力
- `cd ..` + Enter → ディレクトリ移動
- `|` + スペース + `grep` → パイプgrep
- `&&` → コマンド連結

### マクロ入力の実装
`InputConnection.beginBatchEdit()` / `endBatchEdit()` でまとめて送信。
中間状態のチラつきを防止。

### スニペット/テンプレート管理
マクロの上位版。カーソル位置指定付きのテンプレートを登録・挿入:
- `$0`: 最終カーソル位置
- `${1:placeholder}`: タブストップ（Tab で次のストップへ移動）
- 例: `git commit -m "$0"` → 挿入後、カーソルが引用符内に自動配置
- 設定UIで登録、JSON形式でImport/Export可能

### IME内コマンドパレット
VSCodeの `Ctrl+Shift+P` のIME版:
- **起動**: Fn+Space or トラックボール長押し
- ミニコマンドラインがIME内に出現
- マクロ/スニペット/設定をインクリメンタル検索で実行
- マクロが100個以上になっても素早くアクセス可能

### 正規表現ベースの自動変換ルール
テキスト展開ツール（espanso等）のIME内蔵版:
- 入力テキストに正規表現マッチ → 自動変換
- デフォルトルール例: `->` → `→`, `!=` → `≠`, `<=` → `≤`, `>=` → `≥`
- `:shrug:` → `¯\_(ツ)_/¯`, `:tableflip:` → `(╯°□°)╯︵ ┻━┻`
- ユーザーが自由にルール追加可能（設定UIで正規表現+置換文字列を登録）
- コーディング中はOFFにできる（EditorInfo で判定 or 手動トグル）

## 対応言語

### 方針
IMEとしての入力エンジンは **英語 + 日本語** に絞る。
UI言語も英語 + 日本語（i18n）。
多言語出力はAI変換機能（有料アドオン）で対応。

### 英語入力
- QWERTY V字分割レイアウト
- 予測変換（KenLM N-gram + プログラミング辞書）

### 日本語入力（v1.0から本格対応・精度最優先）

日本語入力はPhase 1から本格的に作り込む。「最低限」ではなく「常用できる精度」が目標。

#### v1.0（Phase 1）: ローマ字変換 + Mozc OSS辞書
- IME切替に依存せず、Nacre単体で完全な日本語入力を提供
- **ローマ字→かな変換テーブル**:
  - 基本50音 + 濁音/半濁音/拗音（きゃ/きゅ/きょ等）
  - 「ん」判定: n+子音 or nn で確定（n+母音は「な行」）
  - 促音「っ」: 子音の重ね打ち（kk→っk）
  - 小文字: x prefix（xa→ぁ, xtu→っ）
- **かな→漢字変換**: Mozc OSS辞書データ（NAIST辞書ベース、約50-80MB）をPhase 1から組み込み
- **変換アルゴリズム**: Viterbiベースの連文節変換
  - 文節区切りの最適化（コスト最小経路探索）
  - 単文節変換 + 連文節変換の両対応
- **変換候補ランキング**: 頻度ベース + 文脈N-gram（日本語3-gramモデル）
- **学習辞書**: ユーザーの変換履歴から候補順位を自動調整（端末ローカル保存）
- **候補バー**: 3候補表示 + 左右スワイプで候補送り
- **予測変換**: 読み途中からの予測候補表示（日本語N-gramモデル）

#### v2.0（Phase 4）: さらなる強化
- Mozc変換エンジンのフル統合（libmozc JNI）
- 文脈認識の強化（直前の文節を考慮した候補最適化）
- プログラミング用語の日本語辞書（「さーばー→サーバー」等カタカナ変換強化）

#### APKサイズへの影響
- Mozc辞書: 約50-80MB → Phase 1のAPKサイズは約80-100MB（許容範囲）
- 辞書はアプリ内アセットとして同梱（初回ダウンロード不要）

- キーボード内に `[GL]` IME切替ボタンも配置（他IMEへの切替は可能）

## AI変換機能 — Nacre AI（有料アドオン）

### コンセプト
IME内の音声入力 + ローカルLLM変換パイプライン。
「話して、変換して、入力」のワンストップ体験。

### ワークフロー
```
1. トラックボール上スワイプ → 音声入力開始
2. ユーザーが話す: 「サーバーを再起動して」
3. Whisper がテキスト化: 「サーバーを再起動して」
4. そのまま入力 or LLM変換指示:
   - 「英語にして」→ "Restart the server"
   - 「韓国語にして」→ "서버를 재시작해"
   - 「敬語にして」→ 「サーバーを再起動していただけますか」
   - 「要約して」→ 長文を短縮
   - 「コードにして」→ `systemctl restart nginx`
```

### なぜこの方式か
- 多言語IME（韓国語/中国語等）を個別に作り込むのはコスト膨大
- LLMに翻訳させれば、**IMEの入力エンジンは英日だけ**で多言語出力に対応
- 予測変換リランキングにLLMを使うのはバッテリーコスパが悪い → やめる
- 翻訳候補バーもUXが複雑 → やめる
- 音声入力→LLM変換という**明確なパイプライン**に絞る

### 実装

#### 音声認識（STT）
- **v1.0**: Android SpeechRecognizer API（無料版でも利用可能）
- **Nacre AI**: Whisper base (142MB, WER 5.0%) — 完全オフライン
  - whisper.cpp の JNI バインディング
  - **別プロセスで実行** (`android:process=":whisper"`)。JNIクラッシュ(SIGSEGV)時もIME本体は生存
  - AIDL/Messenger でプロセス間通信

#### LLM変換
- **モデル**: Gemma 3 1B (INT4, 約600MB) — llama.cpp Android バインディング
- **別プロセスで実行** (`android:process=":llm"`)。クラッシュ隔離
- **遅延ロード/アンロード戦略**:
  - 音声入力起動時にモデルロード開始（非同期、3-8秒）
  - 30秒無操作でメモリからアンロード
  - `onTrimMemory(TRIM_MEMORY_RUNNING_LOW)` で強制アンロード
  - ロード中は「変換準備中...」表示、STTのテキスト挿入は即座に可能
- **バッテリー保護**: バッテリー残量20%以下で自動停止（設定で閾値変更可）

#### モデル配布
- 有料購入後に設定画面からダウンロード（Whisper 142MB + Gemma 600MB = 約750MB）
- 内部ストレージ `/data/data/.../files/models/` に保存
- モデルの存在チェック → なければダウンロード案内
- Google Play の APK自体にはモデルを含めない → APKサイズは軽量に維持

### 価格と機能分離

| 機能 | 無料版 | Nacre AI（有料） |
|------|--------|-----------------|
| QWERTY + トラックボール | ○ | ○ |
| 英日入力（ローマ字変換） | ○ | ○ |
| マクロ/キーバインド | ○ | ○ |
| 打鍵音/触覚 | ○ | ○ |
| 音声入力（Android STT） | ○ | ○ |
| 音声入力（Whisperオフライン） | × | ○ |
| AI変換（翻訳/敬語/要約/コード化） | × | ○ |

## 予測変換

### KenLM N-gram + 辞書ベース（LLM不使用）
- **エンジン**: KenLM（C++、JNI経由）— 軽量N-gramモデル
- **辞書サイズ**: 3-gram モデル 約20-30MB
- **ドメイン辞書**: プログラミング用語辞書（5-10MB）— コマンド名、API名、一般的なコードパターン
- **パーソナル辞書**: ユーザーの入力履歴から学習（端末ローカル保存）
- 候補表示: キーボード上部に3候補バー（翻訳に枠を奪われることはない）
- **JNIクラッシュ保護**: フォールバック（辞書なし候補を返す）

~~v2.0でLLMリランキング~~ → 不要。KenLM + 辞書で十分。バッテリーコスパが悪い。

## 打鍵音 — 科学的設計

### 音響心理学に基づく打鍵音設計

目標: メカニカルキーボードの「Thock」感を再現。心地よさの鍵は低周波の豊かさ。

#### 周波数特性
| 要素 | 周波数帯域 | 役割 |
|------|-----------|------|
| 基音（Thock） | 120-250Hz | 心地よさの核。深みのある低音 |
| アタック | 1-4kHz | 打鍵の「カチッ」感。鋭すぎるとClacky（不快） |
| 空間感 | 4-8kHz | ケース共鳴のシミュレーション |

- 250Hz以下を十分に含めることで「Thock」感を実現
- 4kHz以上のアタック成分は控えめに（-6dB以下）
- 8kHz以上はカット（スマホスピーカーの再生限界＆耳疲れ防止）

#### ADSRエンベロープ（OGGファイル生成時のパラメータ）
```
Attack:  1-5ms   — 瞬時の立ち上がり（打鍵の即時性）
Decay:   15-40ms — 急速な減衰（余韻を残しつつ短く）
Sustain: 0       — 持続音なし
Release: 20-60ms — 自然な消音
```
- 総継続時間: 40-100ms
- **注意**: SoundPoolは事前エンコード済みOGGを再生するだけ。ADSRはランタイム制御ではなく、OGGファイル作成時のパラメータ
- 4プロファイル x 4キータイプ = **16個のOGGファイル**を事前合成（Audacity/SoXで作成）

#### サウンドプロファイル

| プロファイル | 基音周波数 | アタック | 全体の印象 |
|-------------|-----------|---------|-----------|
| Thock（デフォルト） | 150Hz | ソフト（-8dB） | 深く心地よい |
| Clicky | 200Hz | シャープ（-3dB） | メカニカルスイッチ風 |
| Silent | — | — | 振動のみ |
| Typewriter | 180Hz | ミディアム + ベル音 | レトロ |

#### 実装詳細
- **フォーマット**: OGG Vorbis、22050Hz、モノラル（ファイルサイズ最小化）
- **SoundPool**: `maxStreams=6`（高速タイプで同時再生が必要）
- **AudioAttributes**: `USAGE_ASSISTANCE_SONIFICATION` + `CONTENT_TYPE_SONIFICATION`
- **事前ロード**: `onCreate()` で全サウンドをロード（初回遅延防止）
- **キー別音**: 4種（通常キー / スペース / BS / Enter）。BSはやや高い音、Enterは低く重い音
- **音量**: システム音量連動 + アプリ内0-100%調整

#### レイテンシ要件
- タッチ→音再生: **<10ms** 必須（多感覚統合の同時性窓）
- SoundPoolは<5msで再生可能（事前ロード済みの場合）

## 触覚フィードバック — 科学的設計

### 振動パラメータ

スマートフォンのLRA（Linear Resonant Actuator）アクチュエータを前提に設計。

| イベント | 振動時間 | 振幅 | API |
|---------|---------|------|-----|
| キー押下 | 10-15ms | 80-120 (0-255) | `VibrationEffect.createOneShot()` |
| 長押し認識 | 20-30ms | 140-160 | `VibrationEffect.createOneShot()` |
| レイヤー切替 | 5ms + 5ms (ダブルパルス) | 100 | `VibrationEffect.createWaveform()` |
| エラー | 15ms-10ms-15ms パターン | 180 | `VibrationEffect.createWaveform()` |
| トラックボール各ステップ | 3-5ms | 40-60 | `VibrationEffect.createOneShot()` |

### API分岐
```kotlin
when {
    Build.VERSION.SDK_INT >= 33 -> {
        // API 33+: プリミティブ合成（最も洗練されたフィードバック）
        vibrator.vibrate(VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
            .compose())
    }
    Build.VERSION.SDK_INT >= 29 -> {
        // API 29+: プリセットエフェクト
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
    }
    else -> {
        // API 26-28: カスタムワンショット
        vibrator.vibrate(VibrationEffect.createOneShot(12, 100))
    }
}
// Vibrator.hasAmplitudeControl() チェック必須。非対応デバイスはDEFAULT_AMPLITUDEにフォールバック
```

### 多感覚同期
- 触覚と音の遅延差: **<10ms** を維持
- 視覚フィードバック（キースケール0.95x）も同フレーム内で発生
- 3つの感覚チャネルが同期することで「押した実感」を最大化

### 設定
- 強度: OFF / 弱(amplitude*0.5) / 中(amplitude*1.0) / 強(amplitude*1.5)
- 音と触覚の個別ON/OFF
- **バッテリーセーバー連動**: バッテリーセーバーON時は触覚/音を自動OFF

## セキュリティ・プライバシー

### パスワードフィールド対応（必須）
- `EditorInfo.TYPE_TEXT_VARIATION_PASSWORD` 検出時:
  - 予測変換を無効化
  - 入力履歴に記録しない
  - AI変換を無効化
  - 候補バーを非表示

### データ保護
- `android:allowBackup="false"` — パーソナル辞書のバックアップ経由流出を防止
- INTERNET権限なし（基本版）— キーロガーになりえない
- AI機能有効化時もモデルダウンロード後はオフライン動作

### 物理キーボード接続時
- Bluetooth/USBキーボード接続を `InputDevice` で検出
- 物理キーボード接続中はNacreのレイヤー/マクロを自動バイパス

## アクセシビリティ

### TalkBack対応（Google Play審査対応）
- 全キーに `contentDescription` を設定
- カスタムViewでの `AccessibilityNodeInfo` 提供
- フォーカスナビゲーション対応
- トラックボール操作のTalkBack読み上げ

## Z Fold / 折りたたみデバイス最適化

### 対応するフォームファクター

| デバイス | メインディスプレイ | サブディスプレイ |
|---------|-----------------|----------------|
| Galaxy Z Fold 5/6 | 7.6" (2176x1812) | 6.2" (2316x904) |
| Galaxy Z Flip 5/6 | 6.7" (2640x1080) | 3.4" (720x748) / 1.9" (512x260) |
| Pixel Fold / 9 Pro Fold | 7.6" (2208x1840) | 5.8" (2092x1080) |

### レイアウト戦略

#### メインディスプレイ（展開時: 7.6"）
- **フルV字分割レイアウト**をそのまま使用
- 画面幅が広いため、V字角度を自動で小さく調整（10-12度推奨）
- キーサイズを5-10%拡大可能（幅に余裕あるため）
- トラックボール領域も拡大（72dp）

#### サブディスプレイ（カバー画面: 6.2" 縦長）
Z Foldのサブディスプレイは **幅904px（約380dp）** と狭い。

**設定で3モードから選択（デフォルト: 日本語ベースのコンパクトQWERTY）:**

1. **コンパクトQWERTY（V字なし）** — 日本語ベースの開発者向け
   - ローマ字入力を前提に、かな変換・記号・`Tab`・`Fn` を手の届く位置に整理
   - 絵文字・記号パネルを底段から即起動できる
   - 12キーより学習コストが低く、カバー画面でも筋肉記憶を崩しにくい
   - V字角度0度固定、トラックボール小型化（40dp）

2. **12キーフリック** — かなフリックを明示的に使いたい人向け
   - フリック入力対応の12キー配置
   - 中央にミニトラックボール（40dp）
   - Nacre独自のマクロは12キーでも有効
   - 380dpでも各キー80dp x 56dpの大きなタッチターゲット確保

3. **QuickInputPad** — マクロ+音声入力のみ
   - よく使うマクロ/コマンドの6ボタン（カスタマイズ可能）
   - 中央に音声入力ボタン（大きく表示）
   - ミニトラックボール（40dp）

IMEアプリを状況で切り替えるのはUX最悪。Nacre内で完結させる。

#### Z Flip サブディスプレイ（1.9-3.4"）
- キーボード全体は表示しない（画面が小さすぎる）
- **クイック入力パッド**: よく使うマクロ/コマンドの4-6ボタン
- 音声入力ボタン（大きく表示）

### 画面サイズ検出と自動切替

```kotlin
// IME (Service) では WindowMetricsCalculator(Activity) は使えない
// WindowManager.getCurrentWindowMetrics() (API 30+) を使用
val wm = getSystemService(WindowManager::class.java)
val widthDp = if (Build.VERSION.SDK_INT >= 30) {
    wm.currentWindowMetrics.bounds.width() / resources.displayMetrics.density
} else {
    val dm = DisplayMetrics()
    @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(dm)
    dm.widthPixels / dm.density
}

// 折りたたみデバイス検出: PackageManager + SensorManager
val isFoldable = packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle")
val isFoldedSub = isFoldable && widthDp < 500
// userPrefersCompact: 設定「サブディスプレイで12キーを使う」（デフォルトtrue）

when {
    widthDp >= 500 -> FullVSplitLayout       // メインディスプレイ（展開）/ タブレット
    isFoldedSub -> userSubDisplayMode         // Foldサブ → 設定で選択（QWERTY/12キー/QuickPad）
    widthDp >= 380 -> StandardVSplitLayout   // 通常スマホ（360-420dp）
    widthDp >= 200 -> QuickInputPad          // 小型サブ（Z Flip等）
    else -> QuickInputPad                     // 極小（Z Flip旧型）
}
```

- `onConfigurationChanged()` で画面サイズ変更を検出
- 折り畳み/展開のトランジション: `onConfigurationChanged` から **300-500msのディレイ** 後にレイアウト変更（画面サイズ安定待ち）
- ヒンジ状態検出: `SensorManager` + `TYPE_HINGE_ANGLE`（Samsung端末対応）

### テーブルトップモード（Z Flip）— 将来検討
- Z Flipを半分に折った状態。ヒンジ検出のみ実装し、レイアウト対応は将来版で検討
- 優先度低: 対象ユーザーが極小

## 横画面（ランドスケープ）
- `onEvaluateFullscreenMode()` = false（フルスクリーン防止）
- 横画面ではV字分割の意味が薄れる（画面幅が十分広い）
- 横画面時: V字角度を自動で0度に、キーボード高さを縮小（画面高さの30%以下）
- 5行レイアウト（数字行追加）は将来検討

## UI/デザイン

### テーマ
- **Dark（デフォルト）**: OLED対応ダークテーマ。Shellyと統一感
- **Light**: 明るい環境用
- **AMOLED**: 純黒背景
- カスタムカラー: キーキャップ色、背景色、テキスト色を個別設定可能

### キーライティング（メカニカルキーボード風RGB）

物理メカニカルキーボードのRGBライティングをソフトウェアで再現。ギーク向けの視覚的楽しさ。

#### ライティングモード
| モード | 動作 | 印象 |
|--------|------|------|
| **Static** | 全キー単色発光 | シンプル |
| **Reactive** | 押したキーが光って波紋状に広がる | タイプが気持ちいい |
| **Wave** | 左→右 or 中央→外へ色が流れる | Razer風 |
| **Breathing** | 全体がゆっくり明滅 | 落ち着き |
| **Heatmap** | よく使うキーほど赤く、使わないキーは青く | 実用的＆ギーク心をくすぐる |
| **Matrix** | ランダムに文字が緑色で流れ落ちる | ネタ枠 |
| **OFF** | 発光なし（デフォルト） | バッテリー節約 |

#### 実装
- キーキャップの背景色/ボーダー色をアニメーション（Compose `animateColorAsState`）
- Reactiveモード: タッチ座標から距離に応じたディレイで隣接キーの色を変化（波紋）
- Heatmapモード: `topCommands` のキー頻度データを流用
- **AMOLED端末で映える**: 純黒背景+カラーキーでコントラスト最大
- バッテリーセーバー連動: バッテリーセーバーON時は自動OFF
- カスタムカラー: RGB値を自由に設定可能

### クリップボード履歴
- 直近20件のコピー履歴をIME内で表示・再挿入
- Fn+V or 専用ボタンで履歴パネル表示
- テキストのみ保持（画像は対象外）
- パスワードフィールドでのコピーは履歴に記録しない
- 端末ローカル保存、アプリ削除時に消去

### エラー回復
- BSキーを左にスワイプ → 単語削除（Gboard方式）
- Fnレイヤーに Ctrl+Z（Undo）を必ず含める

## Shelly連携（将来）
- Shellyから「Nacreのキーバインド設定を開く」
- Shellyのスニペットと連動（スニペット→Nacreマクロに変換）
- Shellyのテーマ設定をNacreに同期
- ime-configモジュールにContentProviderを追加して外部連携

## プロジェクト構成

```
nacre/
├── app/                          # アプリモジュール（設定Activity）
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── kotlin/.../
│           ├── NacreApplication.kt
│           └── ui/settings/       # 設定画面（Compose）
│
├── ime-core/                      # IME本体（ライブラリモジュール）
│   └── src/main/kotlin/.../
│       ├── NacreInputMethodService.kt
│       ├── keyboard/
│       │   ├── KeyboardView.kt    # ComposeView（Canvas移行しない）
│       │   ├── KeyLayout.kt       # キー座標計算（V字角度=0度対応の抽象化）
│       │   ├── KeyRenderer.kt     # 描画ロジック
│       │   └── TouchHandler.kt    # タッチイベント処理 + hitTest
│       ├── trackball/
│       │   ├── TrackballView.kt   # カスタムView + GestureDetector
│       │   └── GestureProcessor.kt # 累積移動量 + CDGainカーブ
│       ├── input/
│       │   ├── InputEngine.kt     # InputConnection操作（メインスレッド限定）
│       │   ├── LayerManager.kt    # レイヤー（通常/Fn/Fn2）管理
│       │   ├── MacroEngine.kt     # マクロ実行（batchEdit）
│       │   ├── JapaneseEngine.kt  # ローマ字→かな変換 + Viterbi連文節変換
│       │   ├── MozcDictionary.kt # Mozc OSS辞書アクセス (NAIST辞書)
│       │   ├── SnippetEngine.kt  # スニペット/テンプレート管理 ($0, ${1:placeholder})
│       │   └── AutoConvertEngine.kt # 正規表現ベース自動変換ルール
│       ├── feedback/
│       │   ├── SoundManager.kt    # SoundPool管理（Phase2: 5ファイル → Phase3: 16ファイル）
│       │   └── HapticManager.kt   # VibrationEffect管理 + API 26/29/33分岐
│       ├── prediction/
│       │   ├── PredictionEngine.kt   # 候補生成インターフェース
│       │   └── NgramPredictor.kt     # KenLM N-gram (JNI)
│       └── foldable/
│           ├── FoldableDetector.kt   # SensorManager ヒンジ角度 + WindowMetrics
│           └── LayoutSelector.kt     # 画面サイズ→レイアウト自動選択
│
├── ime-ai/                        # AI機能モジュール（有料アドオン、別プロセス）
│   └── src/main/kotlin/.../
│       ├── WhisperService.kt      # whisper.cpp JNI (android:process=":whisper")
│       ├── LlmService.kt         # llama.cpp JNI (android:process=":llm")
│       ├── AiPipelineManager.kt  # STT→LLM変換パイプライン
│       └── ModelDownloader.kt    # モデルダウンロード管理
│
├── ime-config/                    # 設定データモジュール
│   └── src/main/kotlin/.../
│       ├── KeymapConfig.kt        # キーバインド定義
│       ├── PresetProvider.kt      # プリセット（Default/Terminal/Vim/Emacs/Dvorak/Colemak）
│       ├── ConfigRepository.kt    # DataStore
│       └── ConfigImportExport.kt  # nacre-keymap.json Import/Export
│
└── build-logic/                   # Convention plugins
    └── src/main/kotlin/
        └── NacreBuildConvention.kt # 共通ビルド設定、NDK/ABI設定
```

### NDK/JNIビルド管理
- ABI対応: `arm64-v8a` 必須、`armeabi-v7a` 任意、`x86_64` テスト用
- CMakeLists.txt: KenLM のみ ime-core に含める。whisper/llama は ime-ai モジュール
- ime-ai を使わないユーザー向けに軽量APKフレーバーも検討

## InputMethodService ライフサイクル注意点
- `onCreateInputView()`: ComposeViewをキャッシュして返す（毎回newしない）
  - LifecycleOwner/SavedStateRegistryOwner/ViewModelStoreOwner を自前実装
- `onStartInputView(EditorInfo, restarting)`: EditorInfoで入力タイプに応じたレイアウト切替
  - パスワードフィールド検出 → 予測変換OFF、AI OFF
- `restarting=true` の場合は状態リセットしない
- `getCurrentInputConnection()` は毎回nullチェック必須
- `onConfigurationChanged()` で画面幅再計算 + ComposeView再生成検討
- `onDestroy()` でSoundPool/Vibrator等のリソース確実に解放
- `onTrimMemory()` でAIモデルのアンロード
- Serviceのthisをラムダでキャプチャしないこと（メモリリーク）

## テスト戦略

### ユニットテスト
- キー座標計算（V字回転のアフィン変換）
- CDGain加速度カーブの値検証
- ローマ字→かな変換ロジック
- レイヤー状態遷移

### インテグレーションテスト
- `InputConnection` モック（Robolectric or AndroidXテスト用InputConnection）
- EditorInfo の各入力タイプに対する回帰テスト

### E2Eテスト
- Maestro によるIME操作テスト
- **Termux互換性テスト**（最重要ターゲットアプリ）
- **Samsung Galaxy実機テスト**（シェア30%、独自IME挙動あり）

## 成長戦略

### 初期ユーザー獲得
1. **Reddit**: r/termux, r/androidapps, r/vim, r/emacs — 「IME with built-in trackball for terminal users」
2. **F-Droid掲載** — OSSキーボードユーザーの自然流入
3. **Hacker News** — 「Why I built a split keyboard IME with a virtual trackball」記事
4. **YouTube操作動画** — IMEの良さはテキストでは伝わらない。デモ動画が必須
5. **Shelly連携** — Shellyユーザーへの自然な導線

### KPI
- Phase 1公開後3ヶ月: F-Droidで500 DL
- Phase 2公開後6ヶ月: 1000 DL + GitHub Stars 100
- 長期: Unexpected Keyboard（10万DL）の10%シェアを目指す

## 開発フェーズ

### Phase 1: MVP — QWERTY + トラックボール + 日本語（2-3ヶ月で公開）
- [ ] Kotlinプロジェクト作成（InputMethodService）
- [ ] ComposeView + LifecycleOwner/SavedStateRegistryOwner 自前実装
- [ ] 基本QWERTYレイアウト（V字角度=0度だがV字対応可能なKeyLayout設計）
- [ ] **Tab/Escの常時1タップ配置**（Tab=専用キー、Esc=Fn長押し）
- [ ] **トラックボール**（カーソル移動 + CDGain + タップ/ダブルタップ/長押し）
- [ ] キー入力→テキスト出力（InputConnection）
- [ ] **スワイプ入力**（上スワイプ=数字、左右スワイプ=記号）
- [ ] バックスペース/Enter/スペース
- [ ] IME切替ボタン（GL）
- [ ] **Fnレイヤー**（ページ1: 数字+記号+Ctrl系 / ページ2: F1-F12+Home/End等）
- [ ] **ローマ字→かな変換 + Mozc OSS辞書**（本格的な日本語入力）
- [ ] **日本語予測変換**（日本語N-gramモデル + 読み途中予測）
- [ ] **日本語変換候補バー**（3候補 + 左右スワイプ候補送り）
- [ ] `onEvaluateFullscreenMode()` → false
- [ ] EditorInfo.inputType ハンドリング（パスワードフィールド対応含む）
- [ ] ダークテーマ
- [ ] 設定画面（基本設定）
- [ ] TalkBack対応（contentDescription）
- [ ] Termux互換性テスト
- [ ] F-Droid / GitHub Releases に公開

### Phase 2: V字分割 + フィードバック + カスタマイズ基盤
- [ ] V字分割レイアウト（角度パラメータ変更のみで実現）
- [ ] 片手モード（左右寄せ非分割）
- [ ] 打鍵音（SoundPool + Thock+Silentの **2プロファイル x 4キータイプ = 5ファイル**）
- [ ] 触覚フィードバック（VibrationEffect + API 26/29/33分岐）
- [ ] レイヤーインジケータ（色変化 + テキスト表示）
- [ ] キーボード高さ調整（スワイプで縮小/拡大）
- [ ] **キーライティング**（Reactive/Wave/Heatmap等6モード + カスタムRGB）
- [ ] **クリップボード履歴**（直近20件、パスワード除外）
- [ ] **マクロ登録**（batchEdit）
- [ ] **キーバインド設定UI**
- [ ] **予測変換（KenLM N-gram + プログラミング辞書）**（英語）
- [ ] **キーバインドImport/Export（JSON）**
- [ ] タッチヒートマップ検証 → コンフォートゾーン修正

### Phase 3: 高度なカスタマイズ
- [ ] プリセット（Default/Terminal/Vim/Emacs/**Dvorak/Colemak**）
- [ ] トラックボール感度設定
- [ ] テーマカスタマイズ
- [ ] BS左スワイプ=単語削除
- [ ] **スニペット/テンプレート管理**（$0カーソル位置指定、タブストップ）
- [ ] **IME内コマンドパレット**（Fn+Space、マクロ/スニペット検索実行）
- [ ] **正規表現ベースの自動変換ルール**
- [ ] 打鍵音追加プロファイル（Clicky/Typewriter）

### Phase 4: 折りたたみ + 日本語強化
- [ ] Z Fold / 折りたたみデバイス対応（SensorManager + WindowMetrics）
- [ ] サブディスプレイ3モード（コンパクトQWERTY / 12キーフリック / QuickInputPad）
- [ ] 日本語入力強化（Mozc変換エンジンフル統合 libmozc JNI）
- [ ] 横画面レイアウト最適化
- [ ] 物理キーボード接続時の自動バイパス

### Phase 5: Nacre AI（有料アドオン）
- [ ] Whisper ローカル音声認識（別プロセス）
- [ ] Gemma 3 LLM変換（別プロセス）
- [ ] 音声入力→LLM変換パイプライン（翻訳/敬語/要約/コード化）
- [ ] モデルダウンロード管理
- [ ] 課金統合（Google Play Billing）
- [ ] バッテリー保護（20%以下で自動停止）
- [ ] Shelly連携（ContentProvider）

## OSSリファレンス

| プロジェクト | 参考度 | 用途 |
|---|---|---|
| [Thumb-Key](https://github.com/dessalines/thumb-key) | ★★★★★ | Compose + IME統合の実例。LifecycleOwner実装 |
| [FlorisBoard](https://github.com/florisboard/florisboard) | ★★★★★ | アーキテクチャ、分割モード、テーマ |
| [Unexpected Keyboard](https://github.com/Julow/Unexpected-Keyboard) | ★★★★ | 開発者向けキー（Ctrl/Esc/Tab）の実装 |
| [HeliBoard](https://github.com/Helium314/HeliBoard) | ★★★★ | オフラインIMEの実装パターン |
| [Key Mapper](https://github.com/keymapperorg/KeyMapper) | ★★★ | マクロ/キーリマップ機能の参考 |
| [whisper.cpp](https://github.com/ggerganov/whisper.cpp) | ★★★★ | ローカル音声認識 (Phase 5) |
| [KenLM](https://github.com/kpu/kenlm) | ★★★ | N-gram言語モデル (Phase 3) |
| [Mozc](https://github.com/google/mozc) | ★★★★★ | 日本語変換エンジン・辞書 (Phase 1から使用) |
