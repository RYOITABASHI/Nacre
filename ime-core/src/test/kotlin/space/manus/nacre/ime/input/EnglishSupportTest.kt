package space.manus.nacre.ime.input

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * English support: katakana->English loanword candidates in the Japanese
 * pipeline, and case-aware English completion in ABC mode.
 *
 * Runs the real bundled assets on a plain JVM (same harness style as
 * ConversionBenchmarkTest): empty learning store, no KenLM, Mozc-native
 * off, so the legacy predict()/convert() paths are exercised.
 */
class EnglishSupportTest {

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

    companion object {
        // Dictionary load parses ~1M entries; share one instance per class.
        @Volatile
        private var shared: NacreDictionary? = null

        @Synchronized
        fun dictionary(test: EnglishSupportTest): NacreDictionary {
            shared?.let { return it }
            val assetsDir = try {
                test.locateUpwards("ime-core/src/main/assets")
            } catch (e: IllegalStateException) {
                test.locateUpwards("src/main/assets")
            }
            val stores = HashMap<String, InMemoryStore>()
            val dict = NacreDictionary(
                FileAssetOpener(assetsDir),
                { name -> stores.getOrPut(name) { InMemoryStore() } },
                null,
            )
            dict.load()
            shared = dict
            return dict
        }
    }

    private val dict: NacreDictionary get() = dictionary(this)

    /** reading -> acceptable English surfaces for that loanword. */
    private val loanwords = listOf(
        "こんぴゅーたー" to setOf("computer", "Computer"),
        "すまーとふぉん" to setOf("smartphone", "Smartphone"),
        "かめら" to setOf("camera", "Camera"),
        "ぱそこん" to setOf("PC", "personal computer"),
        "いんたーねっと" to setOf("Internet", "internet"),
        "てれび" to setOf("TV", "television"),
    )

    @Test
    fun loanwordEnglishSurfacesInPredictTop5() {
        for ((reading, english) in loanwords) {
            val candidates = dict.predict(reading, "")
            val rank = candidates.indexOfFirst { it.surface in english }
            assertTrue(
                "$reading: English $english must be in predict top 5, was rank $rank " +
                    "in ${candidates.take(8).map { it.surface }}",
                rank in 0..4,
            )
        }
    }

    @Test
    fun loanwordEnglishSurfacesInConvert() {
        for ((reading, english) in loanwords) {
            val candidates = dict.convert(reading)
            val rank = candidates.indexOfFirst { it.surface in english }
            assertTrue(
                "$reading: English $english must be in convert top 10, was rank $rank " +
                    "in ${candidates.take(10).map { it.surface }}",
                rank in 0..9,
            )
        }
    }

    @Test
    fun japaneseSurfaceStaysFirstInPredict() {
        // The katakana form remains the primary candidate; English is an
        // addition, not a replacement.
        assertEquals("コンピューター", dict.predict("こんぴゅーたー", "").first().surface)
        assertEquals("スマートフォン", dict.predict("すまーとふぉん", "").first().surface)
        assertEquals("カメラ", dict.predict("かめら", "").first().surface)
    }

    @Test
    fun nativeWordsAreNotDisturbed() {
        // かんじ has no loanword reading: ranking is purely Japanese.
        assertEquals("感じ", dict.predict("かんじ", "").first().surface)
        assertEquals("感じ", dict.convert("かんじ").first().surface)
        // さくら: no English candidate crowds the top of the bar.
        val sakura = dict.predict("さくら", "").take(5).map { it.surface }
        assertTrue("さくら top 5 must stay Japanese: $sakura",
            sakura.none { s -> s.all { it.code < 128 } })
        // あい is ambiguous with アイ, but its top ranks stay Japanese.
        val ai = dict.predict("あい", "").take(3).map { it.surface }
        assertEquals(listOf("あい", "合い", "相"), ai)
    }

    @Test
    fun englishCompletionRanksByFrequency() {
        val th = dict.predictEnglish("th", 15).map { it.surface }
        assertEquals("the", th.first())
        assertTrue("th top 5: $th", th.take(5).containsAll(listOf("that", "this")))
        val he = dict.predictEnglish("he", 15).map { it.surface }
        assertEquals("he", he.first())
        assertTrue("he top 6: $he", he.take(6).containsAll(listOf("here", "help")))
        val comp = dict.predictEnglish("comp", 15).map { it.surface }
        assertTrue("comp top 5 must include computer: $comp",
            comp.take(5).contains("computer"))
    }

    @Test
    fun englishCompletionSingleLetters() {
        assertEquals("a", dict.predictEnglish("a", 15).first().surface)
        assertEquals("I", dict.predictEnglish("I", 15).first().surface)
        val an = dict.predictEnglish("an", 15).map { it.surface }
        assertEquals("an", an.first())
        assertTrue("an top 3 must include and: $an", an.take(3).contains("and"))
    }

    @Test
    fun englishCompletionIsCaseAware() {
        val lower = dict.predictEnglish("th", 15).map { it.surface }
        assertTrue("lowercase prefix -> lowercase top: $lower",
            lower.take(5).all { it.first().isLowerCase() })

        val capitalized = dict.predictEnglish("Th", 15).map { it.surface }
        assertEquals("The", capitalized.first())
        assertTrue("capitalized prefix -> capitalized top: $capitalized",
            capitalized.take(5).all { it.first().isUpperCase() })

        val upper = dict.predictEnglish("TH", 10).map { it.surface }
        assertEquals("THE", upper.first())
        assertTrue("upper prefix -> upper top: $upper",
            upper.take(3).all { s -> s.all { !it.isLetter() || it.isUpperCase() } })
    }

    @Test
    fun englishCompletionKeepsIntrinsicCase() {
        // Brand forms keep their stored case even for a lowercase prefix.
        val goo = dict.predictEnglish("goo", 15).map { it.surface }
        assertTrue("goo must offer google: $goo", goo.contains("google"))
        val iph = dict.predictEnglish("iph", 15).map { it.surface }
        assertTrue("iph must offer iPhone with its intrinsic case: $iph",
            iph.contains("iPhone"))
    }
}
