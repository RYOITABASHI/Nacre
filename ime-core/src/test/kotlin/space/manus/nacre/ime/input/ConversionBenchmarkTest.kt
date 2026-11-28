package space.manus.nacre.ime.input

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full-sentence conversion benchmark for the Kotlin dictionary engine.
 *
 * Runs [NacreDictionary.convert] on a plain JVM against the real bundled
 * dictionary assets, with an empty learning store and no KenLM scorer —
 * i.e. the engine exactly as it ships for a fresh install with no learned
 * data. The sentence set lives in tools/benchmark/sentences.tsv
 * (reading <TAB> expected surface <TAB> category).
 *
 * This is a measurement harness, not a quality gate: it asserts only that
 * the harness actually ran. Results (overall/per-category top-1, top-3,
 * MRR, and the full miss list) are written to build/conversion-benchmark.txt
 * and printed to stdout.
 */
class ConversionBenchmarkTest {

    /** Serves asset names (e.g. "dict/mozc_dict.bin") from a directory on disk. */
    private class FileAssetOpener(private val root: File) : AssetOpener {
        override fun open(name: String): InputStream = FileInputStream(File(root, name))
    }

    /** Empty in-memory learning store: no user boost, no history, no phrases. */
    private class InMemoryStore : DictionaryKeyValueStore {
        private val strings = HashMap<String, String>()
        private val ints = HashMap<String, Int>()
        private val bools = HashMap<String, Boolean>()
        override fun getString(key: String): String? = strings[key]
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun getInt(key: String, defaultValue: Int): Int = ints[key] ?: defaultValue
        override fun putInt(key: String, value: Int) { ints[key] = value }
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
            bools[key] ?: defaultValue
    }

    /** Walk up from the test working directory until [relPath] exists. */
    private fun locateUpwards(relPath: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, relPath)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        throw IllegalStateException("Could not locate $relPath upwards from ${System.getProperty("user.dir")}")
    }

    private data class Row(val reading: String, val expected: String, val category: String)

    private class Stats {
        var n = 0
        var top1 = 0
        var strictTop1 = 0
        var top3 = 0
        var reciprocalRankSum = 0.0
    }

    @Test
    fun conversionBenchmark() {
        // Assets: ime-core/src/main/assets (module layout), resolved robustly
        // for both "run from repo root" and "run from module dir" cases.
        val assetsDir = try {
            locateUpwards("ime-core/src/main/assets")
        } catch (e: IllegalStateException) {
            locateUpwards("src/main/assets")
        }
        val sentencesFile = locateUpwards("tools/benchmark/sentences.tsv")

        val rows = sentencesFile.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split('\t')
                require(parts.size == 3) { "Bad benchmark row: $line" }
                Row(parts[0], parts[1], parts[2])
            }
        assertTrue("sentence set must not be empty", rows.isNotEmpty())

        val stores = HashMap<String, InMemoryStore>()
        val dict = NacreDictionary(
            FileAssetOpener(assetsDir),
            { name -> stores.getOrPut(name) { InMemoryStore() } },
            null,
        )
        dict.load()
        assertTrue("dictionary must have loaded entries", dict.entryCount > 0)

        val overall = Stats()
        val perCategory = LinkedHashMap<String, Stats>()
        val misses = StringBuilder()

        for (row in rows) {
            val candidates = dict.convert(row.reading)
            // '|' separates equally correct surfaces; the first one is the strict answer.
            val accepted = row.expected.split('|')
            val rank = candidates.indexOfFirst { it.surface in accepted } // -1 = absent
            val strictRank = candidates.indexOfFirst { it.surface == accepted[0] }
            val stats = perCategory.getOrPut(row.category) { Stats() }
            for (s in listOf(overall, stats)) {
                s.n++
                if (rank == 0) s.top1++
                if (strictRank == 0) s.strictTop1++
                if (rank in 0..2) s.top3++
                if (rank >= 0) s.reciprocalRankSum += 1.0 / (rank + 1)
            }
            if (rank != 0) {
                val actualTop3 = candidates.take(3).joinToString(" / ") { it.surface }
                misses.append(
                    "[${row.category}] reading=${row.reading} expected=${row.expected} " +
                        "rank=${if (rank < 0) "absent" else rank + 1} actualTop3=$actualTop3\n"
                )
            }
        }

        fun pct(x: Int, n: Int): String = if (n == 0) "-" else "%.1f%%".format(100.0 * x / n)
        fun line(label: String, s: Stats): String =
            "%-10s n=%3d  top1=%s  top3=%s  MRR=%.3f  strictTop1=%s".format(
                label, s.n, pct(s.top1, s.n), pct(s.top3, s.n),
                if (s.n == 0) 0.0 else s.reciprocalRankSum / s.n, pct(s.strictTop1, s.n),
            )

        val report = buildString {
            appendLine("Nacre conversion benchmark (Kotlin engine, empty learning, no KenLM)")
            appendLine("sentences=${rows.size} dictEntries=${dict.entryCount}")
            appendLine(line("overall", overall))
            for ((cat, s) in perCategory) appendLine(line(cat, s))
            appendLine()
            appendLine("--- misses (expected not at rank 1): ${overall.n - overall.top1} ---")
            append(misses)
        }

        val outFile = File("build/conversion-benchmark.txt")
        outFile.parentFile?.mkdirs()
        outFile.writeText(report, Charsets.UTF_8)
        println(report)
    }
}
