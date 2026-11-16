package space.manus.nacre.ime.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

/**
 * SwipeDecoder fixtures: traces are generated geometrically from known
 * words — a polyline through the word's key centers on a unit QWERTY grid,
 * corner-cut the way a real finger cuts corners, sampled densely, with
 * seeded noise added. The decoder must place the intended word in its
 * top 3 for the fixture vocabularies (English and romaji).
 */
class SwipeDecoderTest {

    // --- Fixture geometry: unit QWERTY grid, Gboard-style row offsets ---
    private val keyBoxes: List<SwipeKeyBox> = run {
        val rows = listOf(
            Triple("qwertyuiop", 0f, 0f),
            Triple("asdfghjkl", 0.5f, 1f),
            Triple("zxcvbnm", 1.5f, 2f),
        )
        val out = ArrayList<SwipeKeyBox>()
        for ((letters, x0, y) in rows) {
            letters.forEachIndexed { i, ch ->
                out.add(SwipeKeyBox(ch, x0 + i, y, 1f, 1f))
            }
        }
        out
    }
    private val keyByChar = keyBoxes.associateBy { it.char }

    /** Polyline through the word's key centers, corner-cut, noised, sampled. */
    private fun traceFor(word: String, seed: Int): List<SwipeTracePoint> {
        val centers = word.map { ch ->
            val box = keyByChar.getValue(ch)
            SwipeTracePoint(box.centerX, box.centerY)
        }
        // Chaikin corner cutting (one pass) — real glides round the corners.
        val cut = ArrayList<SwipeTracePoint>()
        cut.add(centers.first())
        for (i in 1 until centers.size) {
            val a = centers[i - 1]
            val b = centers[i]
            if (hypot(b.x - a.x, b.y - a.y) < 0.01f) continue // doubled letter
            cut.add(SwipeTracePoint(a.x + (b.x - a.x) * 0.72f, a.y + (b.y - a.y) * 0.72f))
            cut.add(SwipeTracePoint(a.x + (b.x - a.x) * 0.92f, a.y + (b.y - a.y) * 0.92f))
        }
        cut.add(centers.last())
        // Sample every ~0.2 units along the polyline.
        val sampled = ArrayList<SwipeTracePoint>()
        var prev = cut.first()
        sampled.add(prev)
        for (i in 1 until cut.size) {
            val b = cut[i]
            val len = hypot(b.x - prev.x, b.y - prev.y)
            var d = 0.2f
            while (d < len) {
                val t = d / len
                sampled.add(SwipeTracePoint(prev.x + (b.x - prev.x) * t, prev.y + (b.y - prev.y) * t))
                d += 0.2f
            }
            sampled.add(b)
            prev = b
        }
        // Seeded noise, ±0.09 key units — a steady but human finger.
        val random = Random(seed)
        return sampled.map { p ->
            val nx = (random.nextFloat() + random.nextFloat() - 1f) * 0.09f
            val ny = (random.nextFloat() + random.nextFloat() - 1f) * 0.09f
            SwipeTracePoint(p.x + nx, p.y + ny)
        }
    }

    private fun decoderFor(words: List<SwipeLexiconWord>) =
        SwipeDecoder(SwipeLexicon(words))

    private fun lexiconOf(vararg words: Pair<String, Int>): List<SwipeLexiconWord> =
        words.map { (w, c) -> SwipeLexiconWord(w, c) }

    // A small frequency-ordered English vocabulary, including the classic
    // confusable sets (hello/hell/help/held, world/word/work, good/god).
    private val englishLexicon = lexiconOf(
        "the" to 100, "of" to 110, "and" to 120, "a" to 130, "to" to 140,
        "in" to 150, "is" to 160, "you" to 170, "that" to 180, "it" to 190,
        "he" to 200, "was" to 210, "for" to 220, "on" to 230, "are" to 240,
        "with" to 250, "his" to 260, "they" to 270, "i" to 280, "at" to 290,
        "be" to 300, "this" to 310, "have" to 320, "from" to 330, "or" to 340,
        "one" to 350, "had" to 360, "by" to 370, "word" to 3200, "but" to 390,
        "not" to 400, "what" to 410, "all" to 420, "were" to 430, "we" to 440,
        "when" to 450, "your" to 460, "can" to 470, "said" to 480, "there" to 490,
        "use" to 500, "an" to 510, "each" to 520, "which" to 530, "she" to 540,
        "do" to 550, "how" to 560, "their" to 570, "if" to 580, "will" to 590,
        "up" to 600, "other" to 610, "about" to 620, "out" to 630, "many" to 640,
        "then" to 650, "them" to 660, "these" to 670, "so" to 680, "some" to 690,
        "her" to 700, "would" to 710, "make" to 720, "like" to 730, "him" to 740,
        "into" to 750, "time" to 760, "has" to 770, "look" to 780, "two" to 790,
        "more" to 800, "write" to 810, "go" to 820, "see" to 830, "number" to 840,
        "no" to 850, "way" to 860, "could" to 870, "people" to 880, "my" to 890,
        "than" to 900, "first" to 910, "water" to 920, "been" to 930, "call" to 940,
        "who" to 950, "oil" to 960, "its" to 970, "now" to 980, "find" to 990,
        "long" to 1000, "down" to 1010, "day" to 1020, "did" to 1030, "get" to 1040,
        "come" to 1050, "made" to 1060, "may" to 1070, "part" to 1080,
        "hello" to 2400, "world" to 2300, "hell" to 6000, "help" to 2600,
        "held" to 7000, "good" to 1500, "god" to 4000, "work" to 2100,
        "quick" to 3400, "brown" to 3600, "fox" to 3800, "jumps" to 4200,
        "over" to 1900, "lazy" to 4400, "dog" to 2800, "morning" to 2500,
        "thank" to 2000, "please" to 2200, "keyboard" to 3900, "swipe" to 4100,
        "typing" to 4000, "android" to 3500, "nacre" to 5000, "just" to 1400,
        "know" to 1300, "take" to 1200, "year" to 1100, "think" to 1450,
        "king" to 4600, "kind" to 3300, "thing" to 2700, "thanks" to 2300,
    )

    private val englishTargets = listOf(
        "hello", "world", "quick", "brown", "fox", "jumps", "over", "lazy",
        "dog", "good", "morning", "thank", "please", "keyboard", "swipe",
        "typing", "android", "the", "they", "them", "then", "this", "that",
        "with", "have", "from", "time", "people", "about", "would", "there",
        "their", "which", "when", "make", "like", "just", "know", "take",
        "into", "year", "your", "some", "could", "work", "word", "think",
    )

    @Test
    fun `english fixtures land in the top 3`() {
        val decoder = decoderFor(englishLexicon)
        var top1 = 0
        var top3 = 0
        val misses = ArrayList<String>()
        for ((index, word) in englishTargets.withIndex()) {
            val results = decoder.decode(traceFor(word, seed = 1000 + index), keyBoxes, limit = 5)
            val rank = results.indexOfFirst { it.word == word }
            if (rank == 0) top1++
            if (rank in 0..2) top3++ else misses.add("$word -> ${results.map { it.word }}")
        }
        println("English fixtures: top1 $top1/${englishTargets.size}, top3 $top3/${englishTargets.size}")
        if (misses.isNotEmpty()) println("Misses: $misses")
        assertTrue("top-3 misses: $misses", misses.isEmpty())
        assertTrue("top-1 rate too low: $top1/${englishTargets.size}", top1 >= englishTargets.size * 4 / 5)
    }

    // Romaji fixture vocabulary (decoded forms), with plausible costs.
    private val romajiLexicon = lexiconOf(
        "ohayou" to 3000, "arigatou" to 2500, "konnnichiha" to 2800,
        "sensei" to 2600, "gakkou" to 2700, "toukyou" to 2400,
        "sushi" to 2900, "sakura" to 2700, "densha" to 2800,
        "shinbun" to 3000, "neko" to 3100, "inu" to 3200, "ame" to 3300,
        "yuki" to 3100, "umi" to 3200, "sora" to 3100, "hikari" to 3400,
        "kazoku" to 3300, "ashita" to 2900, "kyou" to 3000,
        "watashi" to 2600, "anata" to 2800, "taberu" to 3200, "nomu" to 3400,
        "iku" to 3300, "kuru" to 3300, "tenki" to 3100, "denwa" to 3200,
        "eki" to 3000, "mise" to 3400, "hana" to 3200, "hoshi" to 3300,
        "tsuki" to 3100, "taiyou" to 3400, "jitensha" to 3600,
        "sensei" to 2600, "seito" to 3500, "tomodachi" to 2900,
        "nihon" to 2700, "nihongo" to 2800, "eigo" to 3000,
        "ryouri" to 3400, "ryokou" to 3300, "shigoto" to 2800,
        "kaisha" to 2900, "denshi" to 3600, "denchi" to 3700,
    )

    private val romajiTargets = listOf(
        "ohayou", "arigatou", "konnnichiha", "sensei", "gakkou", "toukyou",
        "sushi", "sakura", "densha", "shinbun", "ashita", "watashi",
        "tenki", "eki", "hana", "tsuki", "nihongo", "shigoto", "kaisha",
    )

    @Test
    fun `romaji fixtures land in the top 3`() {
        val decoder = decoderFor(romajiLexicon)
        var top1 = 0
        var top3 = 0
        val misses = ArrayList<String>()
        for ((index, word) in romajiTargets.withIndex()) {
            val results = decoder.decode(traceFor(word, seed = 5000 + index), keyBoxes, limit = 5)
            val rank = results.indexOfFirst { it.word == word }
            if (rank == 0) top1++
            if (rank in 0..2) top3++ else misses.add("$word -> ${results.map { it.word }}")
        }
        println("Romaji fixtures: top1 $top1/${romajiTargets.size}, top3 $top3/${romajiTargets.size}")
        if (misses.isNotEmpty()) println("Misses: $misses")
        assertTrue("top-3 misses: $misses", misses.isEmpty())
        assertTrue("top-1 rate too low: $top1/${romajiTargets.size}", top1 >= romajiTargets.size * 4 / 5)
    }

    @Test
    fun `a stationary press is not a glide`() {
        val decoder = decoderFor(englishLexicon)
        // All points inside the H key — a tap, not a word.
        val h = keyByChar.getValue('h')
        val trace = (0..12).map { SwipeTracePoint(h.centerX + 0.03f, h.centerY - 0.02f) }
        assertTrue(decoder.decode(trace, keyBoxes).isEmpty())
    }

    @Test
    fun `lettersAlongPath collapses the crossed keys`() {
        val letters = SwipeDecoder.lettersAlongPath(traceFor("hi", seed = 7), keyBoxes)
        assertEquals("hi", letters)
    }

    // --- KanaRomaji ---

    @Test
    fun `kana converts to canonical romaji`() {
        assertEquals("ohayou", KanaRomaji.hiraganaToRomaji("おはよう"))
        assertEquals("arigatou", KanaRomaji.hiraganaToRomaji("ありがとう"))
        assertEquals("sensei", KanaRomaji.hiraganaToRomaji("せんせい"))
        assertEquals("gakkou", KanaRomaji.hiraganaToRomaji("がっこう"))
        assertEquals("toukyou", KanaRomaji.hiraganaToRomaji("とうきょう"))
        assertEquals("densha", KanaRomaji.hiraganaToRomaji("でんしゃ"))
        assertEquals("kyou", KanaRomaji.hiraganaToRomaji("きょう"))
        assertEquals("zutto", KanaRomaji.hiraganaToRomaji("ずっと"))
        assertEquals("ippai", KanaRomaji.hiraganaToRomaji("いっぱい"))
        assertEquals("shinbun", KanaRomaji.hiraganaToRomaji("しんぶん"))
        // ん before a vowel / n-row needs "nn" under this engine's rules:
        // こんにちは = ko + nn(ん) + ni + chi + ha.
        assertEquals("konnnichiha", KanaRomaji.hiraganaToRomaji("こんにちは"))
        // Katakana and unknown characters are not convertible.
        assertNull(KanaRomaji.hiraganaToRomaji("カタカナ"))
    }

    @Test
    fun `generated romaji round-trips through the engine`() {
        val engine = JapaneseEngine()
        val kanaWords = listOf(
            "おはよう", "ありがとう", "こんにちは", "せんせい", "がっこう",
            "とうきょう", "でんしゃ", "しんぶん", "きょう", "ずっと",
            "いっぱい", "すし", "さくら", "あした", "わたし", "てんき",
            "にほんご", "しごと", "かいしゃ", "じてんしゃ", "ともだち",
        )
        for (kana in kanaWords) {
            val romaji = KanaRomaji.hiraganaToRomaji(kana)
            assertTrue("no romaji for $kana", romaji != null)
            assertEquals(
                "round-trip failed for $kana via $romaji",
                kana,
                engine.romajiToHiragana(romaji!!, finalize = true),
            )
        }
    }
}
