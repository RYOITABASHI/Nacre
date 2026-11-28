#!/usr/bin/env python3
"""Build ime-core/src/main/assets/dict/collocations.tsv.gz (see Collocations.kt).

Counts noun + case particle + predicate (verb / adjective) collocations in
Japanese Wikipedia and writes the table the conversion lattice uses to choose
between homophones (髪を切る / 神を切る, 猫を飼う / 猫を買う).

Source: Hugging Face dataset wikimedia/wikipedia, config 20231101.ja (all 15
parquet shards), CC BY-SA 4.0 — see THIRD_PARTY_LICENSES.md.

  pip install fugashi unidic-lite pyarrow
  python3 tools/dict/build_collocations.py extract raw.tsv train-*.parquet
  python3 tools/dict/build_collocations.py table raw.tsv \
      ime-core/src/main/assets/dict/collocations.tsv.gz <min_count>
"""
import sys

# ---- extract ----
import sys, re, multiprocessing as mp, collections
import pyarrow.parquet as pq

PARTICLES = {"を", "が", "に", "で", "へ", "と", "から"}
KANA2HIRA = {chr(c): chr(c - 0x60) for c in range(0x30A1, 0x30F7)}
def hira(s): return "".join(KANA2HIRA.get(ch, ch) for ch in s)
LEAD_KANJI = re.compile(r"^[一-鿿々]+")
SPLIT = re.compile(r"[。！？\n]")

_tagger = None
def init():
    global _tagger
    import fugashi
    _tagger = fugashi.Tagger()

def work(texts):
    c = collections.Counter()
    for text in texts:
        for sent in SPLIT.split(text):
            if len(sent) < 4 or len(sent) > 300: continue
            toks = _tagger(sent)
            n = len(toks)
            for i in range(n - 2):
                a, p, v = toks[i], toks[i + 1], toks[i + 2]
                fa = a.feature
                if fa.pos1 != "名詞" or fa.pos2 == "数詞": continue
                if p.surface not in PARTICLES or p.feature.pos1 != "助詞": continue
                fv = v.feature
                if fv.pos1 not in ("動詞", "形容詞"): continue
                vkana = hira(fv.kana or "")
                if not vkana or vkana == "*": continue
                # Kanji stem from the SURFACE (unidic lemmas normalise 速い→早い).
                m = LEAD_KANJI.match(v.surface)
                kstem = m.group(0) if m else ""
                # Reading stem of the predicate AS WRITTEN (ひい/ひき → ひ, いい → い):
                # the lemma reading would file いい under よ (良い).
                rstem = vkana[:-1] if len(vkana) > 1 else vkana
                noun = a.surface
                nread = hira(fa.kana or "")
                if not rstem or not nread or len(noun) > 8: continue
                c[(noun, nread, p.surface, kstem, rstem)] += 1
    return c

def batches(paths, size=200):
    for path in paths:
        pf = pq.ParquetFile(path)
        for rb in pf.iter_batches(batch_size=size, columns=["text"]):
            yield rb.column(0).to_pylist()

def extract_main(argv):
    out, paths = argv[0], argv[1:]
    total = collections.Counter()
    with mp.Pool(max(1, mp.cpu_count() - 2), initializer=init) as pool:
        for k, c in enumerate(pool.imap_unordered(work, batches(paths), chunksize=1)):
            total.update(c)
            if k % 200 == 0:
                print(f"batches={k} keys={len(total)}", flush=True)
    with open(out, "w", encoding="utf-8") as f:
        for key, cnt in total.items():
            if cnt >= 2:
                f.write("\t".join(key) + f"\t{cnt}\n")
    print("done keys", len(total), flush=True)

# ---- table ----
import gzip, collections
def table_main(argv):
    raw, out, minc = argv[0], argv[1], int(argv[2])
    K = collections.Counter(); R = collections.Counter()
    for line in open(raw, encoding="utf-8"):
        noun, nread, p, kstem, rstem, c = line.rstrip("\n").split("\t")
        c = int(c)
        if kstem: K[(noun, p, kstem + ":" + rstem)] += c
        R[(noun, p, rstem)] += c
    rows = [("K",) + k + (c,) for k, c in K.items() if c >= minc] + [("R",) + k + (c,) for k, c in R.items() if c >= minc]
    rows.sort()
    with gzip.open(out, "wt", encoding="utf-8", compresslevel=9) as f:
        f.write("# Noun + case particle + predicate collocation counts from Japanese Wikipedia\n")
        f.write("# (wikimedia/wikipedia 20231101.ja, CC BY-SA 4.0; tokenised with fugashi + unidic-lite).\n")
        f.write("# Built by tools/dict/build_collocations.py. K = predicate kanji stem:reading stem, R = predicate reading stem.\n")
        for r in rows: f.write("\t".join(map(str, r)) + "\n")
    print("K", sum(1 for r in rows if r[0] == "K"), "R", sum(1 for r in rows if r[0] == "R"))


if __name__ == "__main__":
    cmd, rest = sys.argv[1], sys.argv[2:]
    if cmd == "extract":
        extract_main(rest)
    elif cmd == "table":
        table_main(rest)
    else:
        sys.exit("usage: build_collocations.py extract|table ...")
