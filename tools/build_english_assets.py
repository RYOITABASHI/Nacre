#!/usr/bin/env python3
"""
Build the English dictionary assets for the Nacre IME.

Outputs (under ime-core/src/main/assets/dict/):

1. loanwords_en.tsv — hiragana reading -> English spelling for common
   loanwords (katakana -> English conversion candidates).
   Source: JMdict_e (EDRDG), Creative Commons Attribution-ShareAlike 4.0.
   https://www.edrdg.org/jmdict/j_jmdict.html
   Selection: entries whose reading is fully katakana, whose English gloss
   is a plain 1-3 word term, and whose katakana form is a *common* word in
   the shipped main dictionary (mozc_dict.bin entry cost <= 4500, taking
   the better of the reading and its long-vowel-dropped variant, since
   Mozc splits e.g. コンピューター / コンピュータ). Onomatopoeia senses are
   excluded. At most 2 glosses per reading (single-word glosses first).
   Cost: 1800 for JMdict strong-priority entries (news1/ichi1/spec1/gai1),
   else 2300.

2. english_full.tsv — lowercase key -> surface -> cost for ABC-mode
   completion, frequency-ordered.
   Source: FrequencyWords (hermitdave), content/2018/en/en_50k.txt,
   derived from OpenSubtitles 2018; content licensed CC-BY-SA 4.0.
   https://github.com/hermitdave/FrequencyWords
   Top 15,000 words by corpus frequency; cost = 1500 + 5000 *
   ln(rank)/ln(15000), so common words complete first. A small hand
   supplement adds modern/tech terms that subtitle frequency under-ranks
   (smartphone, GitHub, ...). The previous english_full.tsv (built by
   build_english_dict.py from a Google-20k list plus a /usr/share/dict
   backfill) had synthetic costs, junk entries ("aa", "abc") and no
   single-letter words ("a", "I") at all; this replaces it.

Usage:
    python3 build_english_assets.py --jmdict JMdict_e.xml \
        --freqwords en_50k.txt \
        --mozc ../ime-core/src/main/assets/dict/mozc_dict.bin \
        --outdir ../ime-core/src/main/assets/dict

JMdict_e: https://www.edrdg.org/pub/Nihongo/JMdict_e.gz (gunzip first)
en_50k:   https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/en/en_50k.txt
"""

import argparse
import gzip
import math
import re
import sys
import xml.etree.ElementTree as ET

# ---------------------------------------------------------------- loanwords

KATA_READING = re.compile(r'^[ァ-ヶー・ヽヾ]+$')
GLOSS_OK = re.compile(r"^[A-Za-z][A-Za-z0-9 '&./-]*$")
STRONG_PRI = {'news1', 'ichi1', 'spec1', 'gai1'}
ORACLE_MAX_COST = 4500
COST_STRONG = 1800
COST_NORMAL = 2300


def kata_to_hira(s):
    return ''.join(
        chr(ord(c) - 0x60) if 0x30A1 <= ord(c) <= 0x30F6 or c in 'ヽヾ' else c
        for c in s if c != '・'
    )


def hira_to_kata(s):
    return ''.join(
        chr(ord(c) + 0x60) if 0x3041 <= ord(c) <= 0x3096 or c in 'ゝゞ' else c
        for c in s
    )


def load_oracle(mozc_path):
    """reading -> {surface: min cost} from the shipped mozc_dict.bin (gzip TSV)."""
    oracle = {}
    with gzip.open(mozc_path, 'rt', encoding='utf-8') as f:
        for line in f:
            p = line.rstrip('\n').split('\t')
            if len(p) < 5:
                continue
            try:
                cost = int(p[4])
            except ValueError:
                continue
            d = oracle.setdefault(p[0], {})
            if p[1] not in d or cost < d[p[1]]:
                d[p[1]] = cost
    return oracle


def oracle_cost(oracle, key):
    """Cost of the katakana form of `key`, allowing the long-vowel variant."""
    best = oracle.get(key, {}).get(hira_to_kata(key))
    if key.endswith('ー'):
        alt = oracle.get(key[:-1], {}).get(hira_to_kata(key[:-1]))
        if alt is not None:
            best = alt if best is None else min(best, alt)
    return best


def clean_gloss(raw):
    t = re.sub(r'\s*\([^)]*\)\s*$', '', (raw or '').strip()).strip()
    if not GLOSS_OK.match(t):
        return None
    words = t.split()
    if len(words) > 3 or len(t) > 32 or words[0].lower() == 'to':
        return None
    if len(words) > 1 and words[0].lower() in ('a', 'an', 'the'):
        t = ' '.join(words[1:])
    return t


def build_loanwords(jmdict_path, oracle):
    # (hiragana key, gloss.lower()) -> (gloss, cost)
    pairs = {}
    for _event, elem in ET.iterparse(jmdict_path, events=('end',)):
        if elem.tag != 'entry':
            continue
        pris = {p.text for p in elem.iter('re_pri')} | {p.text for p in elem.iter('ke_pri')}
        rebs = [r.findtext('reb') for r in elem.findall('r_ele')]
        katas = [r for r in rebs if r and KATA_READING.match(r)]
        if katas:
            glosses = []
            for sense in elem.findall('sense'):
                if any('onomatopoe' in (p.text or '') for p in sense.findall('pos')):
                    continue
                for g in sense.findall('gloss'):
                    if g.get('g_type') not in (None, 'lit'):
                        continue
                    t = clean_gloss(g.text)
                    if t:
                        glosses.append(t)
                if len(glosses) >= 2:
                    break
            cost = COST_STRONG if pris & STRONG_PRI else COST_NORMAL
            for reb in katas:
                key = kata_to_hira(reb)
                if len(key) < 2:
                    continue
                oc = oracle_cost(oracle, key)
                if oc is None or oc > ORACLE_MAX_COST:
                    continue
                for t in glosses[:2]:
                    k = (key, t.lower())
                    if k not in pairs or cost < pairs[k][1]:
                        pairs[k] = (t, cost)
        elem.clear()

    # At most 2 glosses per reading, single-word glosses first, shorter
    # forms before longer ones on ties ("TV" before "television"). A spaced
    # variant is dropped when the same reading also has the unspaced form
    # ("smart phone" loses to "smartphone").
    by_reading = {}
    for (key, _lower), (gloss, cost) in pairs.items():
        by_reading.setdefault(key, []).append((gloss, cost))
    rows = []
    for key, gs in by_reading.items():
        def is_spaced_dup(g):
            if ' ' not in g:
                return False
            squashed = g.replace(' ', '').lower()
            return any(' ' not in o and o.lower() == squashed for o, _ in gs)
        gs = [(g, c) for g, c in gs if not is_spaced_dup(g)]
        gs.sort(key=lambda gc: (len(gc[0].split()), gc[1], len(gc[0]), gc[0].lower()))
        for gloss, cost in gs[:2]:
            rows.append((key, gloss, cost))
    rows.sort(key=lambda r: (r[0], r[2], r[1].lower()))
    return rows


# ------------------------------------------------------- english word list

TOKEN_OK = re.compile(r"^[A-Za-z]+(['-][A-Za-z]+)*$")
TOP_N = 15000
SPECIAL_SURFACES = {'i': 'I'}  # the pronoun is intrinsically capitalized

# Modern/tech terms that subtitle frequency under-ranks or misses entirely.
# key -> (surface, cost). Only added when the key is not already listed.
SUPPLEMENT = {
    'smartphone': ('smartphone', 3000), 'wifi': ('wifi', 3200),
    'email': ('email', 2600), 'website': ('website', 3000),
    'internet': ('internet', 2400), 'app': ('app', 2800),
    'blog': ('blog', 3200), 'vlog': ('vlog', 4200),
    'podcast': ('podcast', 3400), 'streaming': ('streaming', 3400),
    'download': ('download', 3000), 'upload': ('upload', 3200),
    'login': ('login', 3400), 'screenshot': ('screenshot', 3800),
    'touchscreen': ('touchscreen', 4200), 'laptop': ('laptop', 2800),
    'desktop': ('desktop', 3000), 'server': ('server', 3000),
    'browser': ('browser', 3200), 'password': ('password', 2600),
    'account': ('account', 2400), 'profile': ('profile', 2800),
    'software': ('software', 2800), 'hardware': ('hardware', 3400),
    'keyboard': ('keyboard', 3000), 'github': ('GitHub', 3400),
    'gitlab': ('GitLab', 3800), 'kotlin': ('Kotlin', 3800),
    'android': ('Android', 2600), 'iphone': ('iPhone', 2600),
    'google': ('Google', 2200), 'youtube': ('YouTube', 2200),
    'netflix': ('Netflix', 3000), 'spotify': ('Spotify', 3400),
    'discord': ('Discord', 3400), 'slack': ('Slack', 3600),
    'zoom': ('Zoom', 3000), 'tiktok': ('TikTok', 3200),
    'instagram': ('Instagram', 2800), 'facebook': ('Facebook', 2600),
    'amazon': ('Amazon', 2600), 'apple': ('Apple', 2400),
    'microsoft': ('Microsoft', 2800), 'ai': ('AI', 3000),
    'api': ('API', 3400), 'usb': ('USB', 3400),
    'url': ('URL', 3600), 'pdf': ('PDF', 3200),
}


def build_english_full(freqwords_path):
    rows = []          # (key, surface, cost) in frequency order
    seen = set()
    with open(freqwords_path, encoding='utf-8') as f:
        for line in f:
            parts = line.split()
            if not parts:
                continue
            token = parts[0]
            if not TOKEN_OK.match(token) or len(token) > 24:
                continue
            if re.match(r'^[a-z]-[a-z]$', token):
                continue  # subtitle tokenization junk ("a-a", "i-i")
            key = token.lower()
            if key in seen:
                continue
            seen.add(key)
            rank = len(rows) + 1
            cost = 1500 + round(5000 * math.log(rank) / math.log(TOP_N))
            rows.append((key, SPECIAL_SURFACES.get(key, token), cost))
            if len(rows) >= TOP_N:
                break
    for key, (surface, cost) in SUPPLEMENT.items():
        if key not in seen:
            rows.append((key, surface, cost))
    return rows


# --------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--jmdict', required=True)
    ap.add_argument('--freqwords', required=True)
    ap.add_argument('--mozc', required=True)
    ap.add_argument('--outdir', required=True)
    args = ap.parse_args()

    oracle = load_oracle(args.mozc)
    loan = build_loanwords(args.jmdict, oracle)
    with open(f'{args.outdir}/loanwords_en.tsv', 'w', encoding='utf-8') as f:
        f.write('# Hiragana reading -> English spelling for common loanwords.\n')
        f.write('# Format: reading\\tenglish\\tcost\n')
        f.write('# Source: JMdict_e (EDRDG), CC BY-SA 4.0 — see THIRD_PARTY_LICENSES.md.\n')
        f.write('# Generated by tools/build_english_assets.py; do not edit by hand.\n')
        for key, gloss, cost in loan:
            f.write(f'{key}\t{gloss}\t{cost}\n')
    print(f'loanwords_en.tsv: {len(loan)} pairs, '
          f'{len({k for k, _, _ in loan})} readings', file=sys.stderr)

    full = build_english_full(args.freqwords)
    with open(f'{args.outdir}/english_full.tsv', 'w', encoding='utf-8') as f:
        f.write('# English completion wordlist: lowercase key\\tsurface\\tcost,\n')
        f.write('# frequency-ordered (rank order in this file).\n')
        f.write('# Source: FrequencyWords en_50k (OpenSubtitles 2018), CC BY-SA 4.0,\n')
        f.write('# plus a small hand-written tech supplement. See THIRD_PARTY_LICENSES.md.\n')
        f.write('# Generated by tools/build_english_assets.py; do not edit by hand.\n')
        for key, surface, cost in full:
            f.write(f'{key}\t{surface}\t{cost}\n')
    print(f'english_full.tsv: {len(full)} words', file=sys.stderr)


if __name__ == '__main__':
    main()
