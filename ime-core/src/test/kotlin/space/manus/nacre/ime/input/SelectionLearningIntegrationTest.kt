package space.manus.nacre.ime.input

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration regression tests for selection learning against the real
 * dictionary (same JVM harness style as [ConversionBenchmarkTest]):
 *
 *  - a surface the user picks repeatedly must climb to rank 1 in both
 *    predict() and convert() — with predict() called BEFORE the learning,
 *    so a stale memoized result would fail the test;
 *  - clearSelectionLearning() must restore the exact baseline ranking
 *    and zero the counts — again through the memoized predict(), so a
 *    result cached across the clear would fail the test. This is the
 *    sequential contract behind the predict-cache generation guard:
 *    results computed from pre-invalidation state must never be served
 *    after the invalidation.
 */
class SelectionLearningIntegrationTest {

    private class FileAssetOpener(private val root: File) : AssetOpener {
        override fun open(name: String): InputStream = FileInputStream(File(root, name))
    }

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

    private fun locateUpwards(relPath: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, relPath)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        throw IllegalStateException("Could not locate $relPath upwards from ${System.getProperty("user.dir")}")
    }

    @Test
    fun learnThenClearRoundTripsThroughMemoizedPredict() {
        val assetsDir = try {
            locateUpwards("ime-core/src/main/assets")
        } catch (e: IllegalStateException) {
            locateUpwards("src/main/assets")
        }
        val stores = HashMap<String, InMemoryStore>()
        val dict = NacreDictionary(
            FileAssetOpener(assetsDir),
            { name -> stores.getOrPut(name) { InMemoryStore() } },
            null,
        )
        dict.load()
        assertTrue(dict.entryCount > 0)

        val reading = "あい"
        val baseline = dict.predict(reading, "")
        assertTrue("need at least 5 baseline candidates", baseline.size >= 5)
        val target = baseline[4]
        val baselineRankPredict = 5
        val baselineRankConvert = dict.convert(reading).indexOfFirst { it.surface == target.surface } + 1
        assertTrue("target must exist in convert()", baselineRankConvert > 1)

        // Teach it: five selections of the rank-5 surface.
        repeat(5) { dict.recordSelection(target) }
        assertEquals(5, dict.selectionCountFor(reading, target.surface))
        assertEquals(
            "learned surface must top predict()",
            0, dict.predict(reading, "").indexOfFirst { it.surface == target.surface },
        )
        assertEquals(
            "learned surface must top convert()",
            0, dict.convert(reading).indexOfFirst { it.surface == target.surface },
        )

        // Clear: counts zeroed, baseline ranking restored exactly — the
        // predict() calls below hit the memoized path, so they fail if a
        // pre-clear result survived the invalidation.
        dict.clearSelectionLearning()
        assertEquals(0, dict.selectionCountFor(reading, target.surface))
        assertEquals(
            "predict() rank must return to baseline after clear",
            baselineRankPredict - 1,
            dict.predict(reading, "").indexOfFirst { it.surface == target.surface },
        )
        assertEquals(
            "convert() rank must return to baseline after clear",
            baselineRankConvert - 1,
            dict.convert(reading).indexOfFirst { it.surface == target.surface },
        )
    }
}
