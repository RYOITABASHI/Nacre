package space.manus.nacre.ime.input

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * English commit history: alphabet words committed in the QWERTY English
 * layer, the 12-key ABC pad, or from the Japanese candidate bar resurface
 * as candidates for alphabet prefixes — the parity the Japanese path has
 * always had via recentHistory/historyMatchesFor.
 *
 * Pure-logic tests cover [EnglishHistory] directly; integration tests run
 * the real bundled assets on a plain JVM (same harness style as
 * EnglishSupportTest). Test words are chosen to be absent from the bundled
 * English dictionary ("zzqxw", "Nacre", ...) so any appearance in
 * predictEnglish output can only come from history.
 */
class EnglishHistoryTest {

    // ---------- Pure logic: isRecordable ----------

    @Test
    fun recordableWords() {
        assertTrue(EnglishHistory.isRecordable("computer"))
        assertTrue(EnglishHistory.isRecordable("don't"))
        assertTrue(EnglishHistory.isRecordable("PC"))
        assertFalse("single letter", EnglishHistory.isRecordable("a"))
        assertFalse("single letter", EnglishHistory.isRecordable("I"))
        assertFalse("empty", EnglishHistory.isRecordable(""))
        assertFalse("digits", EnglishHistory.isRecordable("ab1"))
        assertFalse("hyphen", EnglishHistory.isRecordable("well-known"))
        assertFalse("mixed script", EnglishHistory.isRecordable("abcあ"))
        assertFalse("apostrophes only", EnglishHistory.isRecordable("''"))
    }

    // ---------- Pure logic: record ----------

    @Test
    fun recordKeepsNewestFirstAndDedupesCaseInsensitively() {
        val history = mutableListOf<String>()
        assertTrue(EnglishHistory.record(history, "apple", max = 10))
        assertTrue(EnglishHistory.record(history, "banana", max = 10))
        assertEquals(listOf("banana", "apple"), history)
        // Re-committing moves the word to the front; the newest case wins.
        assertTrue(EnglishHistory.record(history, "Apple", max = 10))
        assertEquals(listOf("Apple", "banana"), history)
    }

    @Test
    fun recordCapsAndRejects() {
        val history = mutableListOf<String>()
        EnglishHistory.record(history, "one", max = 2)
        EnglishHistory.record(history, "two", max = 2)
        EnglishHistory.record(history, "three", max = 2)
        assertEquals(listOf("three", "two"), history)
        assertFalse(EnglishHistory.record(history, "x", max = 2))
        assertEquals(listOf("three", "two"), history)
    }

    // ---------- Pure logic: matchesFor ----------

    @Test
    fun matchesKeepCommittedCaseAndRankExactFirst() {
        val history = listOf("GitHub", "gist", "go")
        val hits = EnglishHistory.matchesFor(history, "gi")
        assertEquals(listOf("GitHub", "gist"), hits.map { it.surface })
        // Exact match on the full word costs base + recency only.
        val exact = EnglishHistory.matchesFor(history, "gist")
        assertEquals("gist", exact.first().surface)
        assertEquals(
            EnglishHistory.BASE_COST + 1 * EnglishHistory.RECENCY_STEP,
            exact.first().cost,
        )
    }

    @Test
    fun matchesPenalizeUntypedRemainder() {
        val history = listOf("theater")
        // Short prefix (<= 2 chars): 2000 per untyped char — a long history
        // word must stay far below frequent dictionary completions.
        val shortPrefix = EnglishHistory.matchesFor(history, "th").single()
        assertEquals(
            EnglishHistory.BASE_COST + 5 * 2000,
            shortPrefix.cost,
        )
        // Longer prefix: 1200 per untyped char ("theater" - "thea" = 3).
        val longPrefix = EnglishHistory.matchesFor(history, "thea").single()
        assertEquals(
            EnglishHistory.BASE_COST + 3 * 1200,
            longPrefix.cost,
        )
        // Case-insensitive matching, prefix case irrelevant.
        assertEquals(1, EnglishHistory.matchesFor(history, "TH").size)
        // Non-matching prefix and empty inputs yield nothing.
        assertTrue(EnglishHistory.matchesFor(history, "xy").isEmpty())
        assertTrue(EnglishHistory.matchesFor(history, "").isEmpty())
        assertTrue(EnglishHistory.matchesFor(emptyList(), "th").isEmpty())
    }

    @Test
    fun matchesAreCappedAtThree() {
        val history = listOf("test", "testing", "tested", "tester")
        assertEquals(3, EnglishHistory.matchesFor(history, "test").size)
    }

    // ---------- Integration with the real dictionary ----------

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

    private fun newDictionary(stores: HashMap<String, InMemoryStore>): NacreDictionary {
        val assetsDir = try {
            locateUpwards("ime-core/src/main/assets")
        } catch (e: IllegalStateException) {
            locateUpwards("src/main/assets")
        }
        val dict = NacreDictionary(
            FileAssetOpener(assetsDir),
            { name -> stores.getOrPut(name) { InMemoryStore() } },
            null,
        )
        dict.load()
        return dict
    }

    companion object {
        // Dictionary load parses ~1M entries; share one instance per class.
        @Volatile
        private var shared: NacreDictionary? = null
        private val sharedStores = HashMap<String, InMemoryStore>()

        @Synchronized
        fun dictionary(test: EnglishHistoryTest): NacreDictionary {
            shared?.let { return it }
            val dict = test.newDictionary(sharedStores)
            shared = dict
            return dict
        }
    }

    private val dict: NacreDictionary get() = dictionary(this)

    @Test
    fun committedWordSurfacesForAlphabetPrefix() {
        // "zzqxw" is not in the bundled dictionary: only history can surface it.
        assertTrue(dict.predictEnglish("zzq", 15).none { it.surface == "zzqxw" })
        dict.recordEnglishSelection("zzqxw")
        val hits = dict.predictEnglish("zzq", 15)
        assertTrue("history word must surface: ${hits.map { it.surface }}",
            hits.any { it.surface == "zzqxw" })
        // Fully typed, the history word is the top candidate.
        assertEquals("zzqxw", dict.predictEnglish("zzqxw", 15).first().surface)
    }

    @Test
    fun historyKeepsCommittedCase() {
        // "nacre" is not in the bundled dictionary; the committed form is
        // capitalized, and history must offer it back exactly as committed
        // even for a lowercase prefix (dictionary candidates would be
        // re-cased to the typed pattern instead).
        dict.recordEnglishSelection("Nacre")
        val hits = dict.predictEnglish("nac", 15)
        assertTrue("committed case must be preserved: ${hits.map { it.surface }}",
            hits.any { it.surface == "Nacre" })
    }

    @Test
    fun historyDoesNotBuryDictionaryCompletions() {
        dict.recordEnglishSelection("theater")
        val th = dict.predictEnglish("th", 15).map { it.surface }
        assertEquals("the", th.first())
        // The history hit is penalized for its 5 untyped chars, so it
        // either ranks below the dictionary completions or falls off the
        // displayed list entirely — both prove it does not bury them.
        val theaterIdx = th.indexOf("theater")
        assertTrue("theater must not bury dictionary completions: $th",
            theaterIdx == -1 || theaterIdx > th.indexOf("the"))
    }

    @Test
    fun englishCommitFromJapaneseBarFeedsAlphabetHistory() {
        // Committing an English surface via the Japanese path (recordSelection
        // with a kana reading) also records it as an alphabet commit.
        dict.recordSelection(
            ConversionCandidate(surface = "zzqprog", reading = "ぷろぐらむ", cost = 1000)
        )
        val hits = dict.predictEnglish("zzq", 15)
        assertTrue("Japanese-bar English commit must surface: ${hits.map { it.surface }}",
            hits.any { it.surface == "zzqprog" })
    }

    @Test
    fun nonRecordableCommitsStayOutOfHistory() {
        dict.recordEnglishSelection("a")
        dict.recordEnglishSelection("x9z")
        // Neither can ever be a history surface (integration-level smoke:
        // the pure tests pin the rule itself).
        val hits = dict.predictEnglish("x9", 15)
        assertTrue(hits.none { it.surface == "x9z" })
    }

    @Test
    fun historyPersistsAcrossInstances() {
        val stores = HashMap<String, InMemoryStore>()
        val first = newDictionary(stores)
        first.recordEnglishSelection("zzpersist")
        first.flushPendingSave()
        val second = newDictionary(stores)
        val hits = second.predictEnglish("zzp", 15)
        assertTrue("history must survive a reload: ${hits.map { it.surface }}",
            hits.any { it.surface == "zzpersist" })
    }

    @Test
    fun japanesePredictionIsUnaffectedByEnglishHistory() {
        dict.recordEnglishSelection("computer")
        dict.recordEnglishSelection("smartphone")
        // The katakana form remains the primary Japanese candidate and no
        // alphabet history leaks into the Japanese ranking.
        assertEquals("コンピューター", dict.predict("こんぴゅーたー", "").first().surface)
        assertEquals("感じ", dict.predict("かんじ", "").first().surface)
    }
}
