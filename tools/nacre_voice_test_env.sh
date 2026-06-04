#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${NACRE_VOICE_OUT_DIR:-$ROOT_DIR/voice-test-runs}"
ADB_SERIAL="${ADB_SERIAL:-}"
PKG="${NACRE_PACKAGE:-space.manus.nacre}"
LOG_FILES=(
  "/sdcard/Download/nacre-whisper-debug.txt"
  "/sdcard/Download/nacre-voice-debug.txt"
  "/sdcard/Download/nacre-llm-debug.txt"
)

usage() {
  cat <<'EOF'
Usage:
  tools/nacre_voice_test_env.sh start [session-name]
  tools/nacre_voice_test_env.sh collect [session-name]
  tools/nacre_voice_test_env.sh tail
  tools/nacre_voice_test_env.sh reset
  tools/nacre_voice_test_env.sh status

Environment:
  ADB_SERIAL             adb serial, e.g. 192.168.3.5:34051
  NACRE_VOICE_OUT_DIR    output directory, default ./voice-test-runs
  NACRE_PACKAGE          package name, default space.manus.nacre

Workflow:
  1. start   clears stale logs and prints the utterance set.
  2. Speak the test utterances through Nacre voice input.
  3. collect saves logcat, /sdcard debug files, and a stage summary.
  4. tail    optionally watches live voice-related logcat lines.
EOF
}

adb_base() {
  if [[ -n "$ADB_SERIAL" ]]; then
    adb -s "$ADB_SERIAL" "$@"
    return
  fi
  adb "$@"
}

require_device() {
  if [[ -n "$ADB_SERIAL" ]]; then
    adb -s "$ADB_SERIAL" get-state >/dev/null
    return
  fi

  local count
  count="$(adb devices | awk 'NR > 1 && $2 == "device" { n++ } END { print n + 0 }')"
  if [[ "$count" == "0" ]]; then
    echo "No adb device is connected. Set ADB_SERIAL or run adb connect first." >&2
    exit 1
  fi
  if [[ "$count" != "1" ]]; then
    echo "Multiple adb devices are connected. Set ADB_SERIAL." >&2
    adb devices -l >&2
    exit 1
  fi
}

session_name() {
  local raw="${1:-$(date +%Y%m%d-%H%M%S)}"
  printf '%s' "$raw" | tr -c 'A-Za-z0-9._-' '_'
}

session_dir() {
  local name
  name="$(session_name "${1:-}")"
  echo "$OUT_DIR/$name"
}

filter_logcat() {
  grep -Ei \
    'Nacre|VoiceInput|WhisperService|SherpaRecognizer|LlmService|LlamaJni|NacreLlamaJNI|CloudRefiner|SpeechRecognizer|RecognitionServiceImpl|NetworkSpeechRecognizer|SodaSpeechRecognizer|AudioRecord|MediaProvider|Permission to access file' \
    || true
}

write_utterances() {
  cat <<'EOF'
Speak these utterances, one per voice-input session:

1. これは音声入力による入力ですログを見て解析してください
2. git rebaseして
3. npm installリアクト
4. npm install React
5. 右のエラー直して
6. TypeScriptのエラーを直して
7. 制度、句読点、言い間違い補正要約について精度を上げてください
EOF
}

cmd_status() {
  require_device
  echo "== adb devices =="
  adb devices -l
  echo
  echo "== device time =="
  adb_base shell date
  echo
  echo "== Nacre package =="
  adb_base shell "dumpsys package '$PKG' | grep -E 'versionName|versionCode|firstInstallTime|lastUpdateTime' | head -n 20" || true
  echo
  echo "== Nacre processes =="
  adb_base shell "pidof '$PKG' 2>/dev/null; ps -A | grep -F '$PKG' || true"
  echo
  echo "== debug files =="
  adb_base shell "ls -l ${LOG_FILES[*]} 2>&1 || true"
}

cmd_reset() {
  require_device
  adb_base logcat -c
  adb_base shell "rm -f ${LOG_FILES[*]}"
  echo "Cleared logcat and removed stale /sdcard/Download/nacre-*.txt files."
}

cmd_start() {
  require_device
  local dir
  dir="$(session_dir "${1:-}")"
  mkdir -p "$dir"
  cmd_reset
  {
    echo "session_dir=$dir"
    echo "host_time=$(date -Is)"
    echo "device_time=$(adb_base shell date | tr -d '\r')"
    echo "adb_serial=${ADB_SERIAL:-auto}"
    echo "package=$PKG"
  } > "$dir/session.txt"
  write_utterances | tee "$dir/utterances.txt"
  echo
  echo "After speaking, run:"
  echo "  tools/nacre_voice_test_env.sh collect $(basename "$dir")"
}

cmd_tail() {
  require_device
  adb_base logcat -v time | filter_logcat
}

cmd_collect() {
  require_device
  local dir
  dir="$(session_dir "${1:-}")"
  mkdir -p "$dir"

  adb_base shell date > "$dir/device-date.txt" || true
  adb devices -l > "$dir/adb-devices.txt" 2>&1 || true
  adb_base shell "dumpsys package '$PKG' | grep -E 'versionName|versionCode|firstInstallTime|lastUpdateTime' | head -n 20" \
    > "$dir/package.txt" 2>&1 || true
  adb_base shell "pidof '$PKG' 2>/dev/null; ps -A | grep -F '$PKG' || true" \
    > "$dir/processes.txt" 2>&1 || true
  adb_base shell "ls -l ${LOG_FILES[*]} 2>&1 || true" > "$dir/debug-file-list.txt" || true

  for path in "${LOG_FILES[@]}"; do
    local base
    base="$(basename "$path")"
    adb_base shell "cat '$path' 2>/dev/null" > "$dir/$base" || true
  done

  adb_base logcat -d -v time > "$dir/logcat-full.txt" 2>&1 || true
  filter_logcat < "$dir/logcat-full.txt" > "$dir/logcat-voice.txt"

  {
    echo "# Nacre Voice Test Summary"
    echo
    echo "## Debug File Status"
    cat "$dir/debug-file-list.txt"
    echo
    echo "## Pipeline Events"
    grep -Ei \
      'startListening|WHISPER STARTED|FALLBACK|SpeechRecognizer started|RecognitionService#logStartListening|NetworkSpeechRecognizer|SodaSpeechRecognizer|handleFinalResult|whisperCallback.onResult|quickClean|tryLlmRefinement|refine\(\) called|Refined:|LlmService: Loading|NacreLlamaJNI|LLM model loaded|model ready|did not become ready|Permission to access file' \
      "$dir/logcat-voice.txt" || true
    echo
    echo "## Interpretation Hints"
    echo "- SpeechRecognizer started means Android/Google fallback was used."
    echo "- WHISPER STARTED means Nacre SenseVoice path was used."
    echo "- Failed to load LLM model or local LLM not loaded means Gemma/Qwen local refinement did not run."
    echo "- Refined: A -> B means the LLM/refiner changed committed text."
    echo "- MediaProvider permission errors mean /sdcard debug files are not usable for this run."
  } > "$dir/summary.md"

  echo "Collected voice test run:"
  echo "  $dir"
  echo
  sed -n '1,220p' "$dir/summary.md"
}

main() {
  local cmd="${1:-}"
  shift || true
  case "$cmd" in
    start) cmd_start "${1:-}" ;;
    collect) cmd_collect "${1:-}" ;;
    tail) cmd_tail ;;
    reset) cmd_reset ;;
    status) cmd_status ;;
    -h|--help|help|"") usage ;;
    *)
      echo "Unknown command: $cmd" >&2
      usage >&2
      exit 2
      ;;
  esac
}

main "$@"
