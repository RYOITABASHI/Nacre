package space.manus.nacre.ime.input

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loanword candidates for ROMAJI-typed long vowels (regression: owner
 * report 2026-11-17 「カタカナ→英語が出ない」 on build-462).
 *
 * The loanword table (dict/loanwords_en.tsv) is keyed by the ー spelling
 * (こんぴゅーたー → computer), but a romaji typist spells long vowels by
 * doubling them: "konpyuutaa" composes to こんぴゅうたあ, which matched
 * neither the loanword key nor its promotion — the English candidate
 * never appeared on the QWERTY surface (the flick pad was unaffected:
 * it has a literal ー key, so the reading forms exactly). The fix looks
 * the English dictionary up under the long-vowel-normalized reading as
 * well; these tests pin that the candidate surfaces in predict() and
 * convert() for romaji-typed loanwords, and that ordinary Japanese
 * words with doubled vowels gain no English candidates.
 *
 * Same harness style as [EnglishSupportTest]: real bundled assets,
 * empty learning store, no KenLM, Mozc-native off.
 */
class LoanwordRomajiTest {

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
        @Volatile
        private var shared: NacreDictionary? = null

        @Synchronized
        fun dictionary(test: LoanwordRomajiTest): NacreDictionary {
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
    private val engine = JapaneseEngine()

    /** romaji as typed on QWERTY -> acceptable English surfaces. */
    private val romajiLoanwords = listOf(
        "konpyuutaa" to setOf("computer", "Computer"),
        "saabaa" to setOf("server", "Server"),
    )

    @Test
    fun romajiDoubledVowelLoanwordInPredictTop5() {
        for ((romaji, english) in romajiLoanwords) {
            val kana = engine.romajiToHiragana(romaji, finalize = true)
            val top5 = dict.predict(kana, romaji).take(5).map { it.surface }
            assertTrue(
                "predict($kana via $romaji) top5 must include $english: $top5",
                top5.any { it in english },
            )
        }
    }

    @Test
    fun romajiDoubledVowelLoanwordInConvert() {
        for ((romaji, english) in romajiLoanwords) {
            val kana = engine.romajiToHiragana(romaji, finalize = true)
            val surfaces = dict.convert(kana).map { it.surface }
            assertTrue(
                "convert($kana via $romaji) must include $english: ${surfaces.take(8)}",
                surfaces.any { it in english },
            )
        }
    }

    /**
     * build-480 on-device report (A-2): "konpyuutaa" showed コンピューター but
     * no "computer". The English candidate WAS in the list, but at rank 5
     * behind the emoji tail (⌨️ 💻 💽), i.e. past the visible width of the
     * candidate bar. When the top candidate is the katakana loanword, the
     * English spelling must sit directly after it (rank 2), in both the
     * live prediction list and the explicit conversion list, while the
     * katakana word keeps rank 1.
     */
    @Test
    fun romajiLoanwordEnglishSitsRightAfterKatakana() {
        for ((romaji, english) in romajiLoanwords) {
            val kana = engine.romajiToHiragana(romaji, finalize = true)
            for ((label, list) in listOf(
                "predict" to dict.predict(kana, romaji).map { it.surface },
                "convert" to dict.convert(kana).map { it.surface },
            )) {
                assertTrue("$label($kana) rank 1 must stay Japanese: ${list.take(5)}",
                    list.isNotEmpty() && list[0].any { it.code >= 128 })
                assertTrue("$label($kana) rank 2 must be $english: ${list.take(5)}",
                    list.size > 1 && list[1] in english)
            }
        }
    }

    @Test
    fun doubledVowelJapaneseWordGainsNoEnglishCandidate() {
        // おおきい is ordinary Japanese with a doubled vowel; the
        // normalized-key lookup must not invent English candidates for it.
        val preds = dict.predict("おおきい", "")
        val ascii = preds.filter { c -> c.surface.all { it.code < 128 } }
        assertTrue("おおきい must gain no ASCII candidates: $ascii", ascii.isEmpty())
    }
}
