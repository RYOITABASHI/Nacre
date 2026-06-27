# Mozc ネイティブエンジン統合 (#13) — ハンドオフ

**ブランチ**: `codex/mozc-native-engine`（main 基点、キーボードUIの `feat/ios-12key-layout` とは別系統）
**目的**: かな→漢字変換の精度をローカルで最大化（APIコストゼロ）。現状の Kotlin 再実装 Viterbi を、Mozc 本体の C++ デコーダ `libmozc.so` に置き換える。

---

## 現状（出発点）

変換は既に **Mozc のデータ**を使っている:
- `assets/dict/mozc_dict.bin`(12.5MB) ＋ `connection.bin`(14.3MB = 2670×2670 接続コスト)
- `NacreDictionary.kt` が Kotlin で Viterbi 最適分割 + 部分分割 + KenLM 再スコア + bigram/trigram 学習 + ユーザー辞書

不足 = **Mozc 本体の変換エンジン**（n-best・文節区切り・予測・学習の完全品質）。これが本タスク。

---

## ビルド経路（調査済み）

Mozc 公式は **Android 向け `libmozc.so` を Bazel でビルド可能**（`android/jni:native_libs`、"Android lib" CI あり）:

```bash
git clone https://github.com/google/mozc.git
cd mozc/src
python3 build_tools/update_deps.py            # protobuf/abseil/NDK r29 を取得
bazelisk build package --config oss_android --config release_build
```

- 前提: Linux/macOS, bazelisk, Python 3.12+, C++ compiler。NDK r29 は自動取得。
- 出力: `libmozc.so`（エンジン + JNI 層）。`android/jni/` に既存の JNI 定義あり → ここに Kotlin から呼ぶ API がある。
- ライセンス: BSD（移植可）。

**最大リスク**: Bazel ビルドは重い（依存大・30–60分・ディスク逼迫の恐れ）。Nacre 本体は Gradle/CMake なので、**libmozc.so を別 CI ジョブで Bazel ビルド → prebuilt として同梱**する二段構成が現実解。

---

## マイルストーン

- **M1（今ここ）**: `.github/workflows/build-mozc.yml` で `libmozc.so`(arm64) を Bazel ビルド → artifact。まず「ビルドが通るか」を立証する。失敗を反復して通す。
- **M2（調査済み）**: JNI API 確定（下記「JNI API」節）。残り = Bazel ビルドが出力する **`mozc.data`**（エンジンデータ束）の取得経路と、Mozc protobuf（`commands.proto` 他）の Java 生成。
- **M3**: libmozc.so + データを `ime-core` の jniLibs/assets に同梱。`NacreMozcJni`（Kotlin↔native）を実装し `convert(かな)→候補` を取得。
- **M4**: `NacreDictionary.convert()` を Mozc ネイティブ呼び出しに切替（**フォールバックで現 Kotlin 版を残す**＝退行ゼロ）。設定トグル `useMozcNative`。
- **M5**: 学習（Mozc user history）を内部ストレージに永続化。ユーザー辞書(#単語登録)を Mozc user dictionary にブリッジ。

## JNI API（`src/android/jni/mozcjni.cc` 調査済み）

`libmozc.so` は以下を **固定の完全修飾クラス名**に RegisterNatives する。Nacre 側もこの
パッケージ/クラスで宣言しないと bind されない:

- **クラス**: `com.google.android.apps.inputmethod.libs.mozc.session.MozcJNI`
- `onPostLoad(userProfileDir: String, engineDataPath: String): Boolean`
  初期化。`engineDataPath` を `DataManager::CreateFromFile()` に渡す → **`mozc.data`** が必要。
  `userProfileDir` = 学習/履歴の保存先（内部ストレージの dir）。
- `evalCommand(command: ByteArray): ByteArray`
  **本体**。`mozc::commands::Command` protobuf をシリアライズして渡し、`Output`（候補列）を
  シリアライズして受け取る。かな入力→候補はここ。
- `getDataVersion(): String`

### M3 で必要なもの
1. `MozcJNI`（上記固定パッケージ `com.google.android.apps.inputmethod.libs.mozc.session`）の
   Kotlin/Java クラス＋`System.loadLibrary("mozc")`。`external fun evalCommand/onPostLoad/getDataVersion`。
2. **`mozc.data`** = `//data_manager/oss:mozc_dataset_for_oss` の出力（M1.5 artifact `mozc-native/data/mozc.data`）。
   assets に同梱 → filesDir にコピー → `onPostLoad(profileDir, dataPath)`。
3. **libmozc.so** = M1.5 artifact `mozc-native/arm64-v8a/libmozc.so`（strip済）→ `ime-core` の jniLibs/arm64-v8a/。
4. **Mozc protobuf を Java 生成**（protobuf gradle plugin `com.google.protobuf`）。**5ファイルを vendor**:
   `commands.proto` ＋ import `candidate_window.proto` / `config.proto` / `engine_builder.proto` /
   `user_dictionary_storage.proto`（Mozc `src/protocol/`）。java package = `org.mozc.android.inputmethod.japanese.protobuf`、outer class `ProtoCommands`。
5. `NacreMozcEngine.convert(かな): List<候補>`:
   - 起動時: `onPostLoad(filesDir/mozc, filesDir/mozc.data)` → `Input(type=CREATE_SESSION)` で session id 取得。
   - 変換: `Input(type=SEND_KEY, id=session, key=KeyEvent(key_string=かな), request=Request(mixed_conversion=true …))`
     → `Command(input=…).toByteArray()` → `evalCommand` → `Output` パース → `all_candidate_words` を候補化。
6. `NacreDictionary.convert()` を Mozc 経由に切替、**例外/未ロード時は現 Kotlin 版へフォールバック**。設定 `useMozcNative`。

## 不変条件
- 現 Kotlin エンジンは**フォールバックとして残す**。Mozc 統合は設定/段階で切替、退行を出さない。
- 実機検証（誤変換例ベース）を各マイルストーンで通す。プッシュ前エージェントレビュー必須（native/JNI 変更）。

## 検証用の誤変換例（収集中）
- きしゃのきしゃがきしゃできしゃした → 貴社の記者が汽車で帰社した（現状: 先頭2つが「記者」寄り）
- （ユーザーから追加収集）

## 参考
- google/mozc: https://github.com/google/mozc
- build_mozc_for_android.md: https://github.com/google/mozc/blob/master/docs/build_mozc_for_android.md
