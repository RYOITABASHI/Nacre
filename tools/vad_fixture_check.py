#!/usr/bin/env python3
"""Fixture check for the Silero VAD swap (legacy sherpa-dist vs v6.2.3).

Runs both models through sherpa-onnx's VoiceActivityDetector with the
exact VadModelConfig Nacre ships in SherpaRecognizer (threshold 0.5,
min_silence 0.3, min_speech 0.25, window 512, max_speech 15) and asserts
the endpointing invariants the voice pipeline relies on:

  1. 3 s of silence            -> 0 segments (both models)
  2. 3 s of white noise RMS .05 -> 0 segments (both models)
  3. one speech utterance       -> >= 1 segment, same count for both
  4. utterances joined by 0.4 s / 0.9 s gaps -> 3 segments (both)
  5. speech mixed with noise    -> v6 keeps at least as much speech
                                   (within 0.1 s) as the legacy model

Fixtures 3-5 need real speech: pass --speech-dir with >= 3 wav files
(any sample rate; resampled to 16 kHz). The 2026-11-16 verification
used JSUT basic5000 utterances 000-002.

Usage:
  pip install sherpa-onnx==1.12.34 soundfile numpy scipy
  python3 tools/vad_fixture_check.py \
      --legacy silero_vad.onnx --v6 silero_vad_v6.onnx \
      --speech-dir /path/to/wavs

Exit code 0 = every invariant that ran holds (a run without
--speech-dir is reported as PARTIAL), 1 = a regression was found.
"""
import argparse
import sys

import numpy as np
import soundfile as sf
import sherpa_onnx

SR = 16000
WINDOW = 512


def load_wav(path):
    x, sr = sf.read(path, dtype="float32")
    if x.ndim > 1:
        x = x[:, 0]
    if sr != SR:
        from scipy.signal import resample_poly
        x = resample_poly(x, SR, sr).astype(np.float32)
    return x


def segments(model, samples, threshold=0.5, min_sil=0.3, min_sp=0.25,
             max_sp=15.0):
    cfg = sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model = model
    cfg.silero_vad.threshold = threshold
    cfg.silero_vad.min_silence_duration = min_sil
    cfg.silero_vad.min_speech_duration = min_sp
    cfg.silero_vad.window_size = WINDOW
    cfg.silero_vad.max_speech_duration = max_sp
    cfg.sample_rate = SR
    vad = sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=120)
    segs = []

    def drain():
        while not vad.empty():
            s = vad.front
            segs.append((s.start / SR, len(s.samples) / SR))
            vad.pop()

    # Stream in window-sized chunks, exactly like WhisperService does.
    for i in range(0, len(samples), WINDOW):
        vad.accept_waveform(samples[i:i + WINDOW])
        drain()
    vad.flush()
    drain()
    return segs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--legacy", required=True, help="legacy (v5-era) model")
    ap.add_argument("--v6", required=True, help="Silero v6 model")
    ap.add_argument("--speech-dir", help="dir with >= 3 speech wavs")
    args = ap.parse_args()

    failures = []

    def check(name, ok, detail=""):
        print(f"  [{'OK' if ok else 'FAIL'}] {name} {detail}")
        if not ok:
            failures.append(name)

    rng = np.random.default_rng(42)
    silence = np.zeros(3 * SR, dtype=np.float32)
    noise = (rng.standard_normal(3 * SR) * 0.05).astype(np.float32)

    for label, model in (("legacy", args.legacy), ("v6", args.v6)):
        check(f"{label}: silence -> 0 segments",
              segments(model, silence) == [])
        check(f"{label}: noise -> 0 segments",
              segments(model, noise) == [])

    if args.speech_dir:
        import glob
        wavs = sorted(glob.glob(f"{args.speech_dir}/*.wav"))[:3]
        if len(wavs) < 3:
            print(f"FAIL: need >= 3 wavs in {args.speech_dir}")
            return 1
        sp = [load_wav(w) for w in wavs]
        gap = lambda s: np.zeros(int(s * SR), dtype=np.float32)
        pauses = np.concatenate([sp[1], gap(0.4), sp[2], gap(0.9), sp[0]])
        noisy = sp[0] + (rng.standard_normal(len(sp[0])) * 0.05).astype(np.float32)

        a, b = segments(args.legacy, sp[0]), segments(args.v6, sp[0])
        print(f"  speech: legacy={a} v6={b}")
        check("speech: both >= 1 segment, same count",
              len(a) >= 1 and len(a) == len(b))

        a, b = segments(args.legacy, pauses), segments(args.v6, pauses)
        print(f"  pauses: legacy={a} v6={b}")
        check("pauses: both split into 3 segments",
              len(a) == 3 and len(b) == 3)

        a, b = segments(args.legacy, noisy), segments(args.v6, noisy)
        da = sum(d for _, d in a)
        db = sum(d for _, d in b)
        print(f"  speech+noise: legacy speech={da:.2f}s v6 speech={db:.2f}s")
        check("speech+noise: v6 keeps >= legacy - 0.1 s", db >= da - 0.1)

    if failures:
        print(f"\n{len(failures)} invariant(s) FAILED")
        return 1
    if not args.speech_dir:
        print("\nPARTIAL verification: only the silence/noise invariants ran. "
              "Re-run with --speech-dir for the full check.")
        return 0
    print("\nAll invariants hold (full verification).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
