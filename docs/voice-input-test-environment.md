# Nacre Voice Input Test Environment

This repo includes a small ADB-based test harness for separating voice-input
quality issues by pipeline stage.

## Why This Exists

Nacre can take several different paths for one voice input:

- SenseVoice/Silero via `WhisperService`
- Android/Google `SpeechRecognizer` fallback
- Rule cleanup via `LlmPostProcessor.quickClean`
- Local/cloud LLM refinement

When quality drops, the important question is which stage changed the text.
The test harness captures logcat and the app's `/sdcard/Download/nacre-*.txt`
debug files, then writes a short summary.

## Start A Session

Connect wireless debugging first, then run:

```bash
export ADB_SERIAL=192.168.3.5:34051
tools/nacre_voice_test_env.sh start voice-001
```

The `start` command:

- clears `adb logcat`
- removes stale `/sdcard/Download/nacre-*.txt` files
- creates `voice-test-runs/voice-001/`
- prints the standard utterance set

Speak each utterance as a separate Nacre voice-input session.

## Collect Results

After the utterances:

```bash
export ADB_SERIAL=192.168.3.5:34051
tools/nacre_voice_test_env.sh collect voice-001
```

Output files:

- `voice-test-runs/voice-001/logcat-full.txt`
- `voice-test-runs/voice-001/logcat-voice.txt`
- `voice-test-runs/voice-001/nacre-whisper-debug.txt`
- `voice-test-runs/voice-001/nacre-voice-debug.txt`
- `voice-test-runs/voice-001/nacre-llm-debug.txt`
- `voice-test-runs/voice-001/summary.md`

## Live Monitor

```bash
export ADB_SERIAL=192.168.3.5:34051
tools/nacre_voice_test_env.sh tail
```

This streams only voice-related logcat lines.

## Interpretation

Use these markers in `summary.md` or `logcat-voice.txt`:

- `WHISPER STARTED`: Nacre's SenseVoice path was used.
- `SpeechRecognizer started`: Android/Google fallback was used.
- `NetworkSpeechRecognizer` / `SodaSpeechRecognizer`: Google recognition
  service handled the audio.
- `whisperCallback.onResult: text=...`: raw SenseVoice result received by the
  IME side.
- `quickClean: A -> B`: rule cleanup changed text from A to B.
- `Refined: A -> B`: an LLM/refiner changed text from A to B.
- `NacreLlamaJNI: Failed to load LLM model`: local Gemma/Qwen inference did not
  load.
- `Permission to access file ... is denied`: `/sdcard/Download/nacre-*.txt`
  logs are not usable for that run; rely on logcat.

## Standard Utterances

Use these, one per voice-input session:

```text
これは音声入力による入力ですログを見て解析してください
git rebaseして
npm installリアクト
npm install React
右のエラー直して
TypeScriptのエラーを直して
制度、句読点、言い間違い補正要約について精度を上げてください
```

## Current Known Device Issue

On the current Fold device, the app has recently been reinstalled and its UID
changed. Old `/sdcard/Download/nacre-*.txt` files can block new app writes with:

```text
MediaProvider: Permission to access file: /storage/emulated/0/Download/nacre-voice-debug.txt is denied
```

The `start` command deletes these stale files before each run. If permission
errors continue after deletion, the app build should be changed to write
diagnostic files under `context.getExternalFilesDir(null)` or to emit structured
stage logs to logcat for debug builds.
