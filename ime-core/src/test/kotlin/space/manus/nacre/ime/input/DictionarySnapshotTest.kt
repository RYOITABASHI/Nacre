package space.manus.nacre.ime.input

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Invariance proof for the dictionary snapshot (fast restart) and the
 * predict() memoization.
 *
 * Loads the dictionary twice against the real bundled assets with empty
 * learning stores: once via the full asset parse (which writes the on-disk
 * snapshot) and once restored from that snapshot. Every observable output —
 * convert(), predict(), predictNextWord(), before and
 * after identical learning operations — must be exactly identical between
 * the two instances. A third load against a corrupted snapshot file must
 * fall back to the full parse and still produce identical output.
 *
 * Runs on a plain JVM through the same seam as [ConversionBenchmarkTest].
 */
class DictionarySnapshotTest {

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

    private fun newDict(assetsDir: File, snapshotDir: File?): NacreDictionary {
        val stores = HashMap<String, InMemoryStore>()
        return NacreDictionary(
            FileAssetOpener(assetsDir),
            { name -> stores.getOrPut(name) { InMemoryStore() } },
            null,
            snapshotDir,
        )
    }

    /** Everything the test compares between two dictionary instances. */
    private fun captureOutputs(
        dict: NacreDictionary,
        readings: List<String>,
    ): Map<String, List<ConversionCandidate>> {
        val out = LinkedHashMap<String, List<ConversionCandidate>>()
        for (reading in readings) {
            out["convert:$reading"] = dict.convert(reading)
            out["predict:$reading"] = dict.predict(reading, "")
        }
        // Romaji probes exercise romajiEnglishMatch, which scans
        // romajiEnglishIndex in map iteration order with an early exit —
        // the case where snapshot restore must preserve iteration order.
        for ((kana, romaji) in ROMAJI_PROBES) {
            out["predict-romaji:$romaji"] = dict.predict(kana, romaji)
        }
        // NOTE: predictEnglish() is not exercised here — it consults the
        // Shelly bridge, whose lazy reader requires a non-null Android
        // Context (androidContext!!), so it cannot run in this JVM harness
        // at all (pre-existing, unrelated to the snapshot). The English full
        // dict it reads is restored through the same readEntryMap path as
        // the maps compared here and is cross-validated by entry counts.
        out["predictNextWord"] = dict.predictNextWord(8)
        return out
    }

    private fun assertOutputsEqual(
        label: String,
        a: Map<String, List<ConversionCandidate>>,
        b: Map<String, List<ConversionCandidate>>,
    ) {
        assertEquals("$label: probe sets differ", a.keys, b.keys)
        val diffs = mutableListOf<String>()
        for ((probe, expected) in a) {
            val actual = b.getValue(probe)
            if (expected != actual) {
                diffs += "$probe:\n  A=$expected\n  B=$actual"
                if (diffs.size >= 3) break
            }
        }
        if (diffs.isNotEmpty()) {
            fail("$label: ${diffs.size}+ output(s) differ:\n" + diffs.joinToString("\n"))
        }
    }

    @Test
    fun snapshotRestoreIsIdentical() {
        val assetsDir = try {
            locateUpwards("ime-core/src/main/assets")
        } catch (e: IllegalStateException) {
            locateUpwards("src/main/assets")
        }
        val sentencesFile = locateUpwards("tools/benchmark/sentences.tsv")
        val readings = sentencesFile.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t')[0] }
        assertTrue("sentence set must not be empty", readings.isNotEmpty())

        val snapshotDir = createTempDir("nacre-dict-snapshot")
        try {
            // A: full parse (writes the snapshot).
            val dictA = newDict(assetsDir, snapshotDir)
            val startA = System.nanoTime()
            dictA.load()
            val loadMsA = (System.nanoTime() - startA) / 1_000_000
            assertFalse("first load must be a full parse", dictA.loadedFromSnapshot)
            assertTrue("dictionary must have loaded entries", dictA.entryCount > 0)
            val snapshotFile = File(snapshotDir, "dict_snapshot.bin")
            assertTrue("snapshot must have been written", snapshotFile.isFile)

            // B: restore from the snapshot A wrote.
            val dictB = newDict(assetsDir, snapshotDir)
            val startB = System.nanoTime()
            dictB.load()
            val loadMsB = (System.nanoTime() - startB) / 1_000_000
            assertTrue("second load must come from the snapshot", dictB.loadedFromSnapshot)
            assertEquals("entryCount", dictA.entryCount, dictB.entryCount)
            println(
                "snapshot: parse=${loadMsA}ms restore=${loadMsB}ms " +
                    "file=${snapshotFile.length() / 1024}KB entries=${dictA.entryCount}",
            )

            // Stage 1: identical outputs with empty learning.
            val beforeA = captureOutputs(dictA, readings)
            val beforeB = captureOutputs(dictB, readings)
            assertOutputsEqual("empty learning", beforeA, beforeB)

            // Stage 2: identical learning operations on both, then compare
            // again. This also proves predict() memo invalidation: the
            // post-learning predictions must reflect the learning on BOTH
            // instances (a stale cache would freeze pre-learning output).
            val probeReading = "みずをください"
            val targetA = dictA.convert(probeReading).firstOrNull { it.surface == "水をください" }
            val targetB = dictB.convert(probeReading).firstOrNull { it.surface == "水をください" }
            assertTrue("probe candidate must exist", targetA != null && targetB != null)
            dictA.recordSelection(targetA!!)
            dictB.recordSelection(targetB!!)
            dictA.recordPhrase("みずをくださいです", "水をくださいです")
            dictB.recordPhrase("みずをくださいです", "水をくださいです")
            dictA.updateContext("水")
            dictB.updateContext("水")

            val afterA = captureOutputs(dictA, readings)
            val afterB = captureOutputs(dictB, readings)
            assertOutputsEqual("after learning", afterA, afterB)

            // The learning must actually have changed something observable —
            // otherwise the stage-2 equality above proves nothing.
            assertTrue(
                "learning must change predict() output for the probe reading",
                beforeA["predict:$probeReading"] != afterA["predict:$probeReading"],
            )
            // And the recorded candidate must now lead its prediction list
            // (recent-history match) on the snapshot-restored instance too.
            assertEquals(
                "recorded candidate must top predict() after recordSelection",
                "水をください",
                afterB["predict:$probeReading"]?.firstOrNull()?.surface,
            )

            // C: corrupted snapshot falls back to a full parse, same output.
            val corruptDir = createTempDir("nacre-dict-snapshot-corrupt")
            try {
                File(corruptDir, "dict_snapshot.bin").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
                val dictC = newDict(assetsDir, corruptDir)
                dictC.load()
                assertFalse("corrupt snapshot must fall back to parse", dictC.loadedFromSnapshot)
                assertOutputsEqual("corrupt fallback", beforeA, captureOutputs(dictC, readings))
            } finally {
                corruptDir.deleteRecursively()
            }
        } finally {
            snapshotDir.deleteRecursively()
        }
    }

    private companion object {
        val ROMAJI_PROBES = listOf(
            "ぐーぐる" to "goo",
            "あっぷる" to "app",
            "てすと" to "te",
            "ぷろぐらむ" to "pro",
            "すまーとふぉん" to "sm",
        )
    }
}
