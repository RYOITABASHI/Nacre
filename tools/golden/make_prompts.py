#!/usr/bin/env python3
"""Generate the golden prompt sets for golden_runner.

zenz : readings from tools/benchmark/sentences.tsv (hiragana) converted to
       katakana with the production rule (KanaUtils.hiraganaToKatakana:
       U+3041..U+3096 -> +0x60), prompt = U+EE00 + katakana + U+EE01
       (ZenzPrompt, no left context), max_tokens =
       clamp(len(katakana) * 2 + 16, 16, 192) — LlmService.zenzConvert.
qwen : the production ChatML prompt from LlmService.buildPrompt
       (PromptFormat.QWEN) with system = DICTATION_CLEANUP_INSTRUCTION,
       extracted from LlmPostProcessor.kt so the golden set can never drift
       from the shipped instruction, and user = one line of qwen_samples.txt.
       max_tokens = 512 (LlmService.MAX_TOKENS).

Outputs (under the given out dir):
  prompts/zenz-NNN.txt, prompts/qwen-NN.txt, manifest_zenz.tsv,
  manifest_qwen.tsv  (rows: id <TAB> mode <TAB> max_tokens <TAB> prompt_file)
"""
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]


def hiragana_to_katakana(text: str) -> str:
    return "".join(
        chr(ord(c) + 0x60) if "\u3041" <= c <= "\u3096" else c for c in text
    )


def extract_cleanup_instruction() -> str:
    src = (
        REPO
        / "ime-ai/src/main/kotlin/space/manus/nacre/ai/LlmPostProcessor.kt"
    ).read_text(encoding="utf-8")
    start = src.index("const val DICTATION_CLEANUP_INSTRUCTION")
    block = src[start:]
    segments = []
    for line in block.splitlines()[1:]:
        line = line.strip()
        m = re.match(r'^"(.*)"\s*\+?$', line)
        if not m:
            break
        seg = m.group(1)
        seg = seg.replace("\\n", "\n").replace('\\"', '"').replace("\\\\", "\\")
        segments.append(seg)
        if not line.endswith("+"):
            break
    instruction = "".join(segments)
    assert "校正エディタ" in instruction and "出力は整形後のテキストのみ" in instruction, (
        "instruction extraction looks wrong"
    )
    return instruction


def main() -> int:
    out = Path(sys.argv[1] if len(sys.argv) > 1 else HERE / "work")
    prompts = out / "prompts"
    prompts.mkdir(parents=True, exist_ok=True)

    # --- zenz ---
    rows = []
    tsv = (REPO / "tools/benchmark/sentences.tsv").read_text(encoding="utf-8")
    n = 0
    for line in tsv.splitlines():
        if not line or line.startswith("#"):
            continue
        reading = line.split("\t")[0]
        katakana = hiragana_to_katakana(reading)
        n += 1
        pid = f"zenz-{n:03d}"
        pf = prompts / f"{pid}.txt"
        pf.write_text("\uEE00" + katakana + "\uEE01", encoding="utf-8")
        max_tokens = min(192, max(16, len(katakana) * 2 + 16))
        rows.append(f"{pid}\tzenz\t{max_tokens}\t{pf}")
    (out / "manifest_zenz.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8")
    print(f"zenz prompts: {n}")

    # --- qwen ---
    instruction = extract_cleanup_instruction()
    samples = [
        line
        for line in (HERE / "qwen_samples.txt").read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#")
    ]
    rows = []
    for i, sample in enumerate(samples, 1):
        pid = f"qwen-{i:02d}"
        pf = prompts / f"{pid}.txt"
        prompt = (
            f"<|im_start|>system\n{instruction}<|im_end|>\n"
            f"<|im_start|>user\n{sample}<|im_end|>\n"
            f"<|im_start|>assistant\n"
        )
        pf.write_text(prompt, encoding="utf-8")
        rows.append(f"{pid}\tqwen\t512\t{pf}")
    (out / "manifest_qwen.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8")
    print(f"qwen prompts: {len(samples)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
