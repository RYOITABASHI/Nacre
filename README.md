# Nacre

Developer-focused Japanese keyboard IME for Android.

Current release: **0.2.0**

## Features

- **Split layout** with adjustable angle for ergonomic thumb typing
- **Foldable cover-screen layout** tuned for Galaxy Z Fold class narrow displays, using a compact Japanese-first QWERTY layout instead of forcing 12-key flick input
- **Japanese romaji input** with real-time kana-kanji conversion
- **Mozc dictionary** for Japanese conversion with Viterbi, phrase memory, mobile/developer vocabulary, and optional KenLM reranking
- **KenLM model support** with compact 3-gram auto-provisioning and full 5-gram in-app download; full 5-gram is preferred when installed
- **Voice input** with continuous recognition, smarter partial commit boundaries, and local/cloud post-processing fallback
- **Local LLM cleanup** defaults to Gemma 4 E2B GGUF, with legacy Qwen 2.5 fallback if present
- **English autocomplete** with frequency-based prediction
- **Developer shortcuts** including Ctrl+key combos, tab key, escape, and macro triggers
- **Emoji picker** and symbol candidate bar with alternatives
- **Password field protection** disables prediction, history, and voice input

## Release Notes

### 0.2.0

- Changed the foldable cover display default to a compact Japanese QWERTY layout.
- Added cover-display-friendly emoji and symbol access.
- Improved Japanese conversion with phrase memory, recency boosts, mobile vocabulary, and KenLM-assisted ranking.
- Added in-app downloads for compact KenLM 3-gram and full KenLM 5-gram.
- Added automatic background provisioning for compact KenLM and the default local LLM.
- Switched the default local LLM model path to Gemma 4 E2B, while keeping Qwen as a fallback.
- Moved model discovery off the settings UI path to avoid black-screen startup stalls.

## Architecture

| Module | Description |
|---|---|
| `app` | Main application, settings UI, billing |
| `ime-core` | InputMethodService, keyboard layout, input engine, dictionary, voice input |
| `ime-config` | Key layout definitions and configuration |
| `ime-ai` | AI services and native bridges for sherpa-onnx, llama.cpp, and KenLM |

## Models

Nacre does not bundle large models in the APK. The settings screen can download or discover them on-device.

| Model | Purpose | Default behavior |
|---|---|---|
| KenLM 3-gram compact (`japanese-compact.klm`) | Lightweight Japanese conversion reranking | Auto-provisioned in the background |
| KenLM 5-gram full (`japanese-5gram.klm`) | Higher-quality Japanese conversion reranking | Downloadable from Settings; preferred over compact when present |
| Gemma 4 E2B GGUF (`gemma-4-E2B-it-Q4_K_M.gguf`) | Local voice-input cleanup and text transformation | Auto-provisioned in the background |
| Qwen 2.5 1.5B GGUF | Legacy local LLM fallback | Used only if present and Gemma is unavailable/unloadable |
| SenseVoice + Silero VAD | Offline speech recognition | Auto-discovered from app/external model locations |

## Build

```bash
tools/setup_nacre_release_signing.sh
./gradlew installNacre
```

The recommended local build is the signed release variant. `installNacre` updates the installed app in place as long as the installed app was signed with the same Nacre release key. If the device currently has an older debug-signed build installed, uninstall it once, then use `installNacre` for normal updates.

`tools/setup_nacre_release_signing.sh` creates a local signing key under `~/.nacre/` and writes ignored `signing.properties` metadata in the repo. Do not commit signing keys or `signing.properties`.

CI builds the release variant. It uploads the `nacre-apk` artifact only when `NACRE_RELEASE_KEYSTORE_BASE64`, `NACRE_RELEASE_STORE_PASSWORD`, `NACRE_RELEASE_KEY_ALIAS`, and `NACRE_RELEASE_KEY_PASSWORD` repository secrets are configured, so unsigned APKs are not published as installable builds.

Requires Android SDK with compileSdk 34. The GitHub Actions build harness builds the release APK artifact and also clones KenLM and llama.cpp sources before building the native `ime-ai` library.

## Security

- **On-device first**: Japanese conversion and local model inference run on the device.
- **Localhost-only LLM**: The optional LLM reranker is restricted to loopback addresses (127.0.0.1, localhost, ::1) to prevent keystroke exfiltration.
- **Password field protection**: Prediction, candidate history, voice input, and AI features are automatically disabled in password fields.
- **No input logging**: User keystrokes, voice transcriptions, and LLM prompts are never written to logcat, even in debug builds.
- **Process isolation**: LLM and speech services run in separate Android processes for crash isolation.

## License

Copyright 2026 RYO ITABASHI. Licensed under the [Apache License 2.0](LICENSE).

Third-party components are listed in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
