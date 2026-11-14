# llama.cpp golden-output regression harness

The :llm native runtime (llama.cpp) is pinned in CI
(`.github/workflows/build-android.yml` clones a tag and flattens the core
sources; `ime-ai/src/main/cpp/CMakeLists.txt` applies the zenz
pre-tokenizer patch at configure time). Two production models run on it:

- **zenz-v3.2-small** (conversion) — depends on the pre-tokenizer patch;
  a pin bump that loses the patch or changes tokenization silently kills
  neural conversion.
- **Qwen 2.5 1.5B Instruct** (voice refinement) — the dictation cleanup
  quality is prompt- and runtime-sensitive.

This harness is the acceptance gate for any pin change: it runs the
**current** runtime and a **candidate** runtime over fixed prompt sets
with production-identical generation semantics and diffs the outputs.

## What is compared

`golden_runner.cpp` replicates `nacre_llama_jni.cpp`'s loops exactly:
greedy decoding (argmax), production tokenize flags, production stop
rules (zenz stops on EOG / token 3 / `</s>` piece), production max-token
formulas, KV clear per prompt, 4 threads. Per prompt it records the
prompt token ids, the generated token ids, and the generated text.

- `golden/zenz.tsv` — 210 prompts (the conversion benchmark readings from
  `tools/benchmark/sentences.tsv`, no left context). **Gate: prompt token
  ids and generated token ids must match the recorded golden exactly.**
  (Baseline sanity: the recorded golden reproduces the known zenz
  top-1 accuracy of 81.0% on the benchmark.)
- `golden/qwen.tsv` — 20 prompts (the production ChatML refinement
  prompt: `DICTATION_CLEANUP_INSTRUCTION` extracted from
  `LlmPostProcessor.kt` by `make_prompts.py`, plus fixed raw-ASR samples
  in `qwen_samples.txt`). **Gate: outputs must match; a diff is only
  acceptable as documented numeric noise** (a near-tie argmax flip),
  never waved through unexplained.

## Running

```sh
# 1. Build the two runtimes you want to compare (each a llama.cpp tree
#    with the zenz pre-tokenizer patch applied — see CMakeLists.txt for
#    the anchor; at b3500 it lives in src/llama.cpp, at b5092+ in
#    src/llama-vocab.cpp), e.g.:
cmake -S <tree> -B <tree>/build -DCMAKE_BUILD_TYPE=Release \
      -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TESTS=OFF \
      -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_SERVER=OFF \
      -DBUILD_SHARED_LIBS=OFF
cmake --build <tree>/build -j2 --target llama

# 2. Generate the prompt sets (deterministic; no network):
python3 tools/golden/make_prompts.py /tmp/golden-work

# 3. Build the runner against a tree and run both models:
sh tools/golden/run_golden.sh <tree> /tmp/golden-work <out-dir>
#    (uses -DNACRE_LLAMA_NEW_API automatically when the tree's llama.h
#     has llama_model_load_from_file)

# 4. Compare a candidate run against the recorded goldens:
diff tools/golden/golden/zenz.tsv <out-dir>/zenz.txt
diff tools/golden/golden/qwen.tsv <out-dir>/qwen.txt
```

Models (not in git): `zenz-v3.2-small-Q5_K_M.gguf` and
`qwen2.5-1.5b-instruct-q4_k_m.gguf` — the same files the app downloads
(see `ModelDownloader` for URLs). Override paths with
`GOLDEN_ZENZ_MODEL` / `GOLDEN_QWEN_MODEL`.

## Recording new goldens

Only when a pin change is *intended* to change outputs and the change
has been reviewed: regenerate the prompt sets, run the new runtime,
eyeball every diff, then replace `golden/*.tsv`. Never regenerate
goldens just to make a diff go away.
