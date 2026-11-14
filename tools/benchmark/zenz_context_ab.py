#!/usr/bin/env python3
"""zenz left-context A/B harness (desktop, azooKey-fork llama-server).

Reproduces the EVALUATION.md method: fork llama-server + the production
GGUF, greedy (temperature 0, seed 42), n_predict 96, cache_prompt off.

Modes:
  context     Run context_sentences.tsv twice per row — once with the
              no-context prompt (U+EE00 reading U+EE01) and once with the
              zenz-v3 left-context prompt (U+EE02 context U+EE00 reading
              U+EE01) — and score both against the expected surface.
  regression  Run sentences.tsv (the standard 210) with the no-context
              prompt only; the baseline to reproduce is strict 81.0% /
              normalized 85.7% (2026-11-14 evaluation). Guards the claim
              that the no-context behavior is unchanged.

Env overrides: LLAMA_SERVER_BIN, ZENZ_MODEL, ZENZ_PORT.
Defaults point at the local evaluation environment:
  ~/workspace/tools/llama-azookey/build/bin/llama-server
  ~/workspace/models/zenz/zenz-v3.2-small-Q5_K_M.gguf
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
HOME = os.path.expanduser("~")
SERVER_BIN = os.environ.get(
    "LLAMA_SERVER_BIN", f"{HOME}/workspace/tools/llama-azookey/build/bin/llama-server")
MODEL = os.environ.get(
    "ZENZ_MODEL", f"{HOME}/workspace/models/zenz/zenz-v3.2-small-Q5_K_M.gguf")
PORT = int(os.environ.get("ZENZ_PORT", "18082"))
BASE = f"http://127.0.0.1:{PORT}"


def hiragana_to_katakana(text: str) -> str:
    return "".join(
        chr(ord(c) + 0x60) if 0x3041 <= ord(c) <= 0x3096 else c for c in text)


_KANJI_DIGITS = {"〇": 0, "一": 1, "二": 2, "三": 3, "四": 4,
                 "五": 5, "六": 6, "七": 7, "八": 8, "九": 9}
_KANJI_MULT = {"十": 10, "百": 100, "千": 1000}
_KANJI_RUN = re.compile(r"[〇一二三四五六七八九十百千万]+")


def _kanji_number_value(run: str) -> int:
    total, section, current = 0, 0, None
    for ch in run:
        if ch in _KANJI_DIGITS:
            current = _KANJI_DIGITS[ch]
        elif ch in _KANJI_MULT:
            section += (current if current is not None else 1) * _KANJI_MULT[ch]
            current = None
        elif ch == "万":
            total += (section + (current or 0)) * 10000
            section, current = 0, None
    return total + section + (current or 0)


def normalize(text: str) -> str:
    """Notation-insensitive comparison form (EVALUATION.md policy):
    kanji numerals -> arabic digits, full-width alphanumerics -> half-width."""
    text = _KANJI_RUN.sub(lambda m: str(_kanji_number_value(m.group(0))), text)
    return "".join(
        chr(ord(c) - 0xFEE0) if 0xFF01 <= ord(c) <= 0xFF5E else c for c in text)


def load_tsv(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            rows.append(line.split("\t"))
    return rows


def complete(prompt: str) -> str:
    body = json.dumps({
        "prompt": prompt,
        "temperature": 0,
        "seed": 42,
        "n_predict": 96,
        "cache_prompt": False,
    }).encode("utf-8")
    req = urllib.request.Request(
        f"{BASE}/completion", data=body,
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.load(resp)["content"].strip()


def score(rows, outputs):
    strict = sum(1 for (_, exp, _), out in zip(rows, outputs) if out == exp)
    norm = sum(1 for (_, exp, _), out in zip(rows, outputs)
               if normalize(out) == normalize(exp))
    return strict, norm


def main() -> int:
    mode = sys.argv[1] if len(sys.argv) > 1 else "context"
    server = subprocess.Popen(
        [SERVER_BIN, "-m", MODEL, "--port", str(PORT), "-c", "1024"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for _ in range(120):
            try:
                with urllib.request.urlopen(f"{BASE}/health", timeout=2) as r:
                    if r.status == 200:
                        break
            except Exception:
                time.sleep(1)
        else:
            print("llama-server did not become healthy", file=sys.stderr)
            return 1

        if mode == "context":
            raw = load_tsv(os.path.join(HERE, "context_sentences.tsv"))
            rows = [(r[1], r[2], r[3]) for r in raw]  # reading, expected, category
            contexts = [r[0] for r in raw]
            plain, with_ctx = [], []
            for (reading, expected, cat), ctx in zip(rows, contexts):
                kata = hiragana_to_katakana(reading)
                out_plain = complete(f"\uEE00{kata}\uEE01")
                out_ctx = complete(f"\uEE02{ctx}\uEE00{kata}\uEE01")
                plain.append(out_plain)
                with_ctx.append(out_ctx)
                mark = lambda o: "OK " if o == expected else "NG "
                print(f"[{cat}] {reading}")
                print(f"  no-context : {mark(out_plain)}{out_plain}")
                print(f"  with-contex: {mark(out_ctx)}{out_ctx}")
                print(f"  expected    :    {expected}")
            for name, outs in (("no-context", plain), ("with-context", with_ctx)):
                s, n = score(rows, outs)
                print(f"{name}: strict {s}/{len(rows)} = {s / len(rows):.1%}, "
                      f"normalized {n}/{len(rows)} = {n / len(rows):.1%}")
            return 0

        if mode == "regression":
            raw = load_tsv(os.path.join(HERE, "sentences.tsv"))
            rows = [(r[0], r[1], r[2]) for r in raw]
            outs = [complete(f"\uEE00{hiragana_to_katakana(r[0])}\uEE01")
                    for r in rows]
            s, n = score(rows, outs)
            print(f"210 no-context: strict {s}/{len(rows)} = {s / len(rows):.1%} "
                  f"(baseline 81.0%), normalized {n}/{len(rows)} = {n / len(rows):.1%} "
                  f"(baseline 85.7%)")
            for (reading, expected, cat), out in zip(rows, outs):
                if out != expected and normalize(out) != normalize(expected):
                    print(f"  miss [{cat}] {reading} -> {out} (expected {expected})")
            return 0

        print(f"unknown mode: {mode}", file=sys.stderr)
        return 2
    finally:
        server.terminate()
        try:
            server.wait(timeout=10)
        except subprocess.TimeoutExpired:
            server.kill()


if __name__ == "__main__":
    sys.exit(main())
