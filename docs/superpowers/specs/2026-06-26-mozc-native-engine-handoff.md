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
- **M2**: `android/jni/` の JNI API を調査（クラス名・メソッド・session/converter インターフェイス）。Mozc のエンジンデータ（system dictionary 等）の取り出し方を確定。
- **M3**: libmozc.so + データを `ime-core` の jniLibs/assets に同梱。`NacreMozcJni`（Kotlin↔native）を実装し `convert(かな)→候補` を取得。
- **M4**: `NacreDictionary.convert()` を Mozc ネイティブ呼び出しに切替（**フォールバックで現 Kotlin 版を残す**＝退行ゼロ）。設定トグル `useMozcNative`。
- **M5**: 学習（Mozc user history）を内部ストレージに永続化。ユーザー辞書(#単語登録)を Mozc user dictionary にブリッジ。

## 不変条件
- 現 Kotlin エンジンは**フォールバックとして残す**。Mozc 統合は設定/段階で切替、退行を出さない。
- 実機検証（誤変換例ベース）を各マイルストーンで通す。プッシュ前エージェントレビュー必須（native/JNI 変更）。

## 検証用の誤変換例（収集中）
- きしゃのきしゃがきしゃできしゃした → 貴社の記者が汽車で帰社した（現状: 先頭2つが「記者」寄り）
- （ユーザーから追加収集）

## 参考
- google/mozc: https://github.com/google/mozc
- build_mozc_for_android.md: https://github.com/google/mozc/blob/master/docs/build_mozc_for_android.md
