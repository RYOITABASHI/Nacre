package space.manus.nacre.ime.input

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Main-dictionary candidates for ROMAJI-typed loanword readings
 * (follow-up to [LoanwordRomajiTest], which pinned the ENGLISH side).
 *
 * The main dictionary entry コンピューター lives under the ー spelling
 * こんぴゅーたー, but romaji input composes the vowel-doubled spelling
 * こんぴゅうたあ, whose own lookups return only Viterbi
 * mis-conversions — the Japanese surface never appeared at all.
 * convert()/predict() now also merge the normalized (ー) spelling's
 * exact matches, re-keyed to the typed reading with a cost penalty
 * and a floor below the typed reading's own best exact candidate.
 * These tests pin: the Japanese surfaces become reachable, the merged
 * candidate keeps the TYPED reading (so selection learning keys on
 * what the user entered), and native doubled-vowel words keep their
 * correct top candidates.
 *
 * Each test builds a FRESH dictionary (unlike LoanwordRomajiTest's
 * shared instance) so the learning test starts from an empty store.
 */
class LoanwordReadingNormalizationTest {

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

    private fun freshDictionary(): NacreDictionary {
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
        return dict
    }

    private val engine = JapaneseEngine()

    @Test
    fun doubledVowelLoanwordJapaneseSurfaceInConvert() {
        val dict = freshDictionary()
        val kana = engine.romajiToHiragana("konpyuutaa", finalize = true)
        assertEquals("こんぴゅうたあ", kana)
        val results = dict.convert(kana)
        val rank = results.indexOfFirst { it.surface == "コンピューター" }
        assertTrue(
            "convert($kana) must include コンピューター: ${results.take(10).map { it.surface }}",
            rank >= 0,
        )
        assertTrue(
            "コンピューター must be a real candidate (top 10), was rank ${rank + 1}: " +
                results.take(10).map { it.surface },
            rank < 10,
        )
    }

    @Test
    fun doubledVowelLoanwordJapaneseSurfaceInPredict() {
        val dict = freshDictionary()
        val kana = engine.romajiToHiragana("konpyuutaa", finalize = true)
        val top5 = dict.predict(kana, "konpyuutaa").take(5).map { it.surface }
        assertTrue(
            "predict($kana) top5 must include コンピューター: $top5",
            "コンピューター" in top5,
        )
    }

    @Test
    fun saabaaServerReachable() {
        val dict = freshDictionary()
        val kana = engine.romajiToHiragana("saabaa", finalize = true)
        assertEquals("さあばあ", kana)
        val convertTop3 = dict.convert(kana).take(3).map { it.surface }
        assertTrue("convert($kana) top3 must include サーバー: $convertTop3", "サーバー" in convertTop3)
        val predictTop3 = dict.predict(kana, "saabaa").take(3).map { it.surface }
        assertTrue("predict($kana) top3 must include サーバー: $predictTop3", "サーバー" in predictTop3)
    }

    @Test
    fun mergedCandidateKeepsTypedReading() {
        val dict = freshDictionary()
        val candidate = dict.convert("こんぴゅうたあ").firstOrNull { it.surface == "コンピューター" }
        assertTrue("コンピューター must be present", candidate != null)
        assertEquals(
            "merged candidate must keep the typed reading (learning key)",
            "こんぴゅうたあ", candidate!!.reading,
        )
        val predCandidate = dict.predict("こんぴゅうたあ", "konpyuutaa")
            .firstOrNull { it.surface == "コンピューター" }
        assertTrue("コンピューター must be present in predict()", predCandidate != null)
        assertEquals(
            "merged predict() candidate must keep the typed reading",
            "こんぴゅうたあ", predCandidate!!.reading,
        )
    }

    @Test
    fun normalizedCollisionDoesNotDisplaceNativeTop() {
        val dict = freshDictionary()
        // Collision regression: くせい (native — 区政 at raw cost 6467)
        // normalizes to くせー, whose dictionary entries are the
        // unrelated colloquial くせー "smelly" at raw cost 293. The
        // fixed penalty alone would rank くせー first; the floor below
        // the typed reading's own best exact candidate must keep 区政
        // on top in both paths, while くせー stays available below.
        val converted = dict.convert("くせい")
        assertEquals("区政", converted.first().surface)
        assertTrue(
            "くせー must still be offered below the top: ${converted.take(6).map { it.surface }}",
            converted.drop(1).any { it.surface == "くせー" },
        )
        val predicted = dict.predict("くせい", "")
        assertEquals("区政", predicted.first().surface)
        assertTrue(
            "くせー must still be offered below the top in predict(): ${predicted.take(6).map { it.surface }}",
            predicted.drop(1).any { it.surface == "くせー" },
        )
    }

    @Test
    fun normalizedCollisionHoldsWithPosContext() {
        val dict = freshDictionary()
        // Same collision as above, but with committed POS context:
        // selecting の sets lastRightGroup from its entry, after which
        // the final POS pass penalizes 区政 (an unnatural transition)
        // while the merged candidates — absent from dict[くせい] —
        // escape that adjustment entirely. The floor is therefore
        // enforced again on context-adjusted costs; 区政 must stay
        // on top in both paths.
        val noCandidate = dict.convert("の").first { it.surface == "の" }
        dict.recordSelection(noCandidate)
        assertEquals("区政", dict.convert("くせい").first().surface)
        assertEquals("区政", dict.predict("くせい", "").first().surface)
    }

    @Test
    fun normalizedMergeDoesNotOutrankPassThroughDemotedTypedBest() {
        val dict = freshDictionary()
        // ああ normalizes to あー, whose own as-is entry is nearly
        // free — but convert()'s pass-through penalty demotes the
        // typed reading's best exact candidate ああ by +1500 while
        // the merged あー escapes that penalty. The floor is
        // re-enforced after the penalty, so ああ must stay above
        // あー (which remains offered right below it).
        val converted = dict.convert("ああ")
        val aaIndex = converted.indexOfFirst { it.surface == "ああ" }
        val aaLongIndex = converted.indexOfFirst { it.surface == "あー" }
        assertTrue("ああ must be present: ${converted.take(6).map { it.surface }}", aaIndex >= 0)
        assertTrue("あー must still be offered: ${converted.take(6).map { it.surface }}", aaLongIndex >= 0)
        assertTrue(
            "merged あー must not outrank ああ: ${converted.take(6).map { it.surface }}",
            aaIndex < aaLongIndex,
        )
    }

    @Test
    fun normalizedFloorUsesClampedDeterministicCost() {
        val dict = freshDictionary()
        // ええ's own as-is entry is cheap (raw 886), so the length
        // bonus drives its exactMatch-stage cost negative (-114) —
        // but applyBoost() clamps every final cost to at least 100,
        // and convert()'s pass-through penalty then lifts ええ by
        // +1500. If the floor comparison used the unclamped stage
        // cost, the merged えー (from えー, raw 0) would land at
        // 1387 and outrank ええ at 1600; with the clamp reflected
        // in the comparison space, ええ stays on top.
        val converted = dict.convert("ええ")
        val eeIndex = converted.indexOfFirst { it.surface == "ええ" }
        val eeLongIndex = converted.indexOfFirst { it.surface == "えー" }
        assertTrue("ええ must be present: ${converted.take(6).map { it.surface }}", eeIndex >= 0)
        assertTrue("えー must still be offered: ${converted.take(6).map { it.surface }}", eeLongIndex >= 0)
        assertTrue(
            "merged えー must not outrank ええ: ${converted.take(6).map { "${it.surface}:${it.cost}" }}",
            eeIndex < eeLongIndex,
        )
        assertEquals("ええ", dict.predict("ええ", "").first().surface)
    }

    @Test
    fun nativeDoubledVowelGuardsUnchanged() {
        val dict = freshDictionary()
        // おおきい: correct top candidate stays; the ー spelling's
        // entries (オーキー etc.) must not break into the top ranks.
        val ookii = dict.convert("おおきい")
        assertEquals("大きい", ookii.first().surface)
        assertTrue(
            "オーキー must stay out of おおきい top5: ${ookii.take(5).map { it.surface }}",
            ookii.take(5).none { it.surface == "オーキー" },
        )
        assertEquals("大きい", dict.predict("おおきい", "").first().surface)
        // こおり: same guard for convert() and predict(). (predict()
        // used to put the as-is kana こおり first; since the lattice
        // engine both lists agree on 氷.) The normalized spelling's
        // コーリ must stay nowhere near the top.
        val koori = dict.convert("こおり")
        assertEquals("氷", koori.first().surface)
        assertTrue(
            "コーリ must stay out of こおり top5: ${koori.take(5).map { it.surface }}",
            koori.take(5).none { it.surface == "コーリ" },
        )
        val kooriPred = dict.predict("こおり", "")
        assertEquals("氷", kooriPred.first().surface)
        assertTrue(
            "氷 must stay in predict(こおり) top3: ${kooriPred.take(3).map { it.surface }}",
            kooriPred.take(3).any { it.surface == "氷" },
        )
        assertTrue(
            "コーリ must stay out of predict(こおり) top5: ${kooriPred.take(5).map { it.surface }}",
            kooriPred.take(5).none { it.surface == "コーリ" },
        )
    }

    @Test
    fun learningKeysOnTypedReading() {
        val dict = freshDictionary()
        val candidate = dict.convert("こんぴゅうたあ").first { it.surface == "コンピューター" }
        val rankBefore = dict.predict("こんぴゅうたあ", "konpyuutaa")
            .indexOfFirst { it.surface == "コンピューター" }
        repeat(3) { dict.recordSelection(candidate) }
        assertEquals(
            "learning must be keyed by the typed reading",
            3, dict.selectionCountFor("こんぴゅうたあ", "コンピューター"),
        )
        assertEquals(
            "learning must NOT be keyed by the normalized (ー) reading",
            0, dict.selectionCountFor("こんぴゅーたー", "コンピューター"),
        )
        val rankAfter = dict.predict("こんぴゅうたあ", "konpyuutaa")
            .indexOfFirst { it.surface == "コンピューター" }
        assertTrue(
            "learned surface must not fall in predict(): before=${rankBefore + 1} after=${rankAfter + 1}",
            rankAfter in 0..rankBefore,
        )
        assertEquals(
            "learned surface must top predict() after selection",
            0, rankAfter,
        )
    }
}
