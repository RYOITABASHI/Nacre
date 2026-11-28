package space.manus.nacre.ime.input

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import kotlin.math.ln

/**
 * Noun + case particle + predicate co-occurrence counts, used to pick
 * between homophones that only the sentence can tell apart: 髪を切る vs
 * 神を切る, 猫を飼う vs 猫を買う, 風邪をひく vs 風をひく, お湯が熱い vs
 * お湯が暑い. Mozc solves this with collocation data its open-source build
 * does not ship (collocation.txt there is a 40-line sample); this table
 * is counted from Japanese Wikipedia (tools/dict/build_collocations.py).
 *
 * Two kinds of key, both "noun, particle, predicate stem":
 *  - K: the predicate's leading kanji plus its reading stem as written
 *    (飼:か, 切:き, 熱:あつ) — chooses the verb's kanji and, given it, the
 *    noun. The reading keeps 良い (read よい in the corpus) from being
 *    rewarded where the user typed いい;
 *  - R: the predicate's reading stem as written (ひ for ひき/ひい) —
 *    chooses the noun when the verb is written in kana (風邪をひきました).
 *
 * Stored as a sorted array of 64-bit key hashes with saturating short
 * counts, looked up by binary search: 10 bytes per entry (~6 MB for the
 * shipped ~0.62M keys), where a HashMap of strings would cost several
 * times that in the IME's heap. A 64-bit hash collision would only ever
 * add a small ranking bonus, never a candidate.
 */
class Collocations private constructor(
    /** Sorted key hashes. */
    private val keys: LongArray,
    /** Count per key, saturated at Short.MAX_VALUE (the bonus is logarithmic). */
    private val counts: ShortArray,
) {
    val size: Int get() = keys.size

    /** Count for a predicate written with [kanjiStem] AND read with [readingStem] (飼 + か). */
    fun kanjiCount(noun: String, particle: String, kanjiStem: String, readingStem: String): Int =
        get(hash('K', noun, particle, "$kanjiStem:$readingStem"))

    fun readingCount(noun: String, particle: String, readingStem: String): Int =
        get(hash('R', noun, particle, readingStem))

    private fun get(h: Long): Int {
        val i = java.util.Arrays.binarySearch(keys, h)
        return if (i >= 0) counts[i].toInt() else 0
    }

    companion object {
        /** Case particles the table was counted for. */
        val PARTICLES = setOf("を", "が", "に", "で", "へ", "と", "から")

        /** Ranking bonus (cost units) for a collocation seen [count] times. */
        fun bonus(count: Int, weight: Int): Int =
            if (count <= 0) 0 else (weight * ln(1.0 + count)).toInt()

        fun hash(kind: Char, noun: String, particle: String, stem: String): Long {
            // FNV-1a 64 over "kind \u0001 noun \u0001 particle \u0001 stem"; 0 is reserved for "empty".
            var h = -0x340d631b7bdddcdbL
            fun mix(c: Char) { h = (h xor c.code.toLong()) * 0x100000001b3L }
            mix(kind); mix('\u0001')
            for (c in noun) mix(c); mix('\u0001')
            for (c in particle) mix(c); mix('\u0001')
            for (c in stem) mix(c)
            return if (h == 0L) 1L else h
        }

        /**
         * Parse the gzipped TSV asset (see the class doc for the row format)
         * straight into primitive arrays — no per-row objects, so loading
         * next to the ~250 MB dictionary adds no allocation spike.
         */
        fun load(gzipped: InputStream): Collocations {
            var keys = LongArray(1 shl 16)
            var counts = IntArray(1 shl 16)
            var n = 0
            BufferedReader(InputStreamReader(GZIPInputStream(gzipped), Charsets.UTF_8), 1 shl 16).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isEmpty() || line[0] == '#') continue
                    val p = line.split('	')
                    if (p.size != 5) continue
                    val count = p[4].toIntOrNull() ?: continue
                    if (n == keys.size) {
                        keys = keys.copyOf(n * 2)
                        counts = counts.copyOf(n * 2)
                    }
                    keys[n] = hash(p[0][0], p[1], p[2], p[3])
                    counts[n] = count
                    n++
                }
            }
            return build(keys, counts, n)
        }

        /** Sort [n] (hash, count) pairs by hash, merge duplicates, saturate counts. */
        fun build(keys: LongArray, counts: IntArray, n: Int): Collocations {
            sortPairs(keys, counts, 0, n - 1)
            val outKeys = LongArray(n)
            val outCounts = ShortArray(n)
            var m = 0
            for (i in 0 until n) {
                if (m > 0 && outKeys[m - 1] == keys[i]) {
                    outCounts[m - 1] = minOf(Short.MAX_VALUE.toInt(), outCounts[m - 1] + counts[i]).toShort()
                } else {
                    outKeys[m] = keys[i]
                    outCounts[m] = minOf(Short.MAX_VALUE.toInt(), counts[i]).toShort()
                    m++
                }
            }
            return Collocations(outKeys.copyOf(m), outCounts.copyOf(m))
        }

        /** In-place quicksort of parallel arrays by key (iterative on the larger half). */
        private fun sortPairs(k: LongArray, c: IntArray, from: Int, to: Int) {
            var lo = from
            var hi = to
            while (lo < hi) {
                val pivot = k[(lo + hi) ushr 1]
                var i = lo
                var j = hi
                while (i <= j) {
                    while (k[i] < pivot) i++
                    while (k[j] > pivot) j--
                    if (i <= j) {
                        val tk = k[i]; k[i] = k[j]; k[j] = tk
                        val tc = c[i]; c[i] = c[j]; c[j] = tc
                        i++; j--
                    }
                }
                if (j - lo < hi - i) { sortPairs(k, c, lo, j); lo = i } else { sortPairs(k, c, i, hi); hi = j }
            }
        }
    }
}
