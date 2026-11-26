#!/usr/bin/env python3
"""Restore the part-of-speech variants that mozc_dict.bin lost.

The original conversion (tools/convert_mozc_dict_v3.py) de-duplicated Mozc
entries by (reading, surface), keeping only the cheapest one. Mozc lists the
same surface once per part of speech (て as 接続助詞 AND as 助動詞, 持っ in
several conjugation classes ...), and its connection matrix is only meaningful
with the right POS id in each context — with the variants gone, 持っ→て cost
13605 instead of 366 and the lattice could not build ordinary sentences.

This script keeps every existing line of mozc_dict.bin (including the merged
NEologd entries and earlier filtering) and appends, for each (reading, surface)
already present, every Mozc (left_id, right_id) variant that is missing.

The Mozc data MUST be the same revision the bundled connection.bin was built
from (the POS ids are revision-specific): google/mozc
b17e284a9f3f5ccda31841ffb78c089d602ea8ba (2670 ids). Download
src/data/dictionary_oss/dictionary00.txt .. dictionary09.txt of that commit
into one directory, then:

  python3 tools/dict/restore_mozc_pos_variants.py \
      ime-core/src/main/assets/dict/mozc_dict.bin <mozc_dir> out.bin

Running it on an already-restored file is a no-op (nothing is missing).
"""
import gzip
import sys


def main():
    asset, src, out = sys.argv[1:4]
    lines = []
    have = set()   # (reading, surface, left_id, right_id)
    pairs = set()  # (reading, surface)
    with gzip.open(asset, "rt", encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            lines.append(p)
            if len(p) >= 5:
                have.add((p[0], p[1], p[2], p[3]))
                pairs.add((p[0], p[1]))
    best = {}
    for k in range(10):
        with open(f"{src}/dictionary0{k}.txt", encoding="utf-8") as f:
            for line in f:
                p = line.rstrip("\n").split("\t")
                if len(p) < 5:
                    continue
                reading, lid, rid, cost, surface = p[0], p[1], p[2], int(p[3]), p[4]
                if (reading, surface) not in pairs:
                    continue
                key = (reading, surface, lid, rid)
                if key in have:
                    continue
                if key not in best or cost < best[key]:
                    best[key] = cost
    for (reading, surface, lid, rid), cost in best.items():
        lines.append([reading, surface, lid, rid, str(cost)])
    lines.sort(key=lambda p: (p[0], int(p[4]) if len(p) >= 5 else 0))
    with gzip.open(out, "wt", encoding="utf-8", compresslevel=9) as f:
        for p in lines:
            f.write("\t".join(p) + "\n")
    print(f"restored {len(best)} POS variants", file=sys.stderr)


if __name__ == "__main__":
    main()
