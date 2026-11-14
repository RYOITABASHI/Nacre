#!/bin/sh
# Build golden_runner against a llama.cpp tree and run both golden sets.
# Usage: run_golden.sh <llama-tree> <work-dir> <out-dir>
#   <work-dir> must contain the output of make_prompts.py.
# Models default to ~/workspace/models/...; override with
# GOLDEN_ZENZ_MODEL / GOLDEN_QWEN_MODEL.
set -eu

TREE="$1"
WORK="$2"
OUT="$3"
HERE="$(cd "$(dirname "$0")" && pwd)"
ZENZ_MODEL="${GOLDEN_ZENZ_MODEL:-$HOME/workspace/models/zenz/zenz-v3.2-small-Q5_K_M.gguf}"
QWEN_MODEL="${GOLDEN_QWEN_MODEL:-$HOME/workspace/models/qwen/qwen2.5-1.5b-instruct-q4_k_m.gguf}"

mkdir -p "$OUT"

# API generation: b5092+ removed llama_load_model_from_file.
NEW_API=""
if grep -q "llama_model_load_from_file" "$TREE/include/llama.h"; then
    NEW_API="-DNACRE_LLAMA_NEW_API"
fi

# Locate the built static libs (layout differs slightly across versions).
LIBLLAMA="$(find "$TREE/build" -name "libllama.a" | head -n 1)"
LIBGGML="$(find "$TREE/build" -name "libggml.a" | head -n 1)"
LIBGGML_BASE="$(find "$TREE/build" -name "libggml-base.a" | head -n 1)"
LIBGGML_CPU="$(find "$TREE/build" -name "libggml-cpu.a" | head -n 1)"

g++ -std=c++17 -O2 -fopenmp $NEW_API "$HERE/golden_runner.cpp" \
    -I"$TREE/include" -I"$TREE/src" -I"$TREE/ggml/include" \
    "$LIBLLAMA" $LIBGGML $LIBGGML_BASE $LIBGGML_CPU \
    -lpthread -ldl -o "$OUT/golden_runner"

"$OUT/golden_runner" --model "$ZENZ_MODEL" --ctx 1024 \
    --manifest "$WORK/manifest_zenz.tsv" > "$OUT/zenz.txt"
"$OUT/golden_runner" --model "$QWEN_MODEL" --ctx 2048 \
    --manifest "$WORK/manifest_qwen.tsv" > "$OUT/qwen.txt"
echo "wrote $OUT/zenz.txt and $OUT/qwen.txt"
