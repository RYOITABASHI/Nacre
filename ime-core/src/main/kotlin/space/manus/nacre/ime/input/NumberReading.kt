package space.manus.nacre.ime.input

/**
 * Kana numeral readings → numbers, for the conversion lattice.
 *
 * Mozc's dictionary only lists single numerals (に → 2, じゅう → 十), so a
 * composite numeral like にじゅうご had no node of its own and the lattice
 * split it into words that happen to share the sounds (二重 | 5). Mozc
 * itself composes numbers in a dedicated rewriter; this is the equivalent
 * for Nacre's lattice: [parse] recognises a whole span as one number and
 * the lattice adds it as a single word in Arabic digits (Gboard's first
 * choice: 25歳, 3月3日) and in kanji numerals (二十五歳).
 *
 * Grammar: up to 兆, with the usual sound changes (さんびゃく, ろっぴゃく,
 * はっせん, さんぜん) and, at the end of a span, the geminated forms used
 * before a counter (いっ, ろっ, はっ, じゅっ / じっ, ひゃっ → 一, 六, 八, 十,
 * 百: いっかい, じゅっぷん, にじゅういっさい).
 */
object NumberReading {

    /** A parsed span: its value and whether it ends geminated (needs a counter next). */
    data class Parsed(val value: Long, val geminated: Boolean)

    private val DIGITS = listOf(
        "きゅう" to 9L, "いち" to 1L, "さん" to 3L, "よん" to 4L, "ろく" to 6L,
        "なな" to 7L, "しち" to 7L, "はち" to 8L, "に" to 2L, "し" to 4L, "ご" to 5L, "く" to 9L,
    )
    private val GEMINATED_DIGITS = listOf("いっ" to 1L, "ろっ" to 6L, "はっ" to 8L)

    /**
     * Unit readings, by the digit that precedes them: the voiced forms
     * only occur after さん (さんびゃく, さんぜん) and the p-form after a
     * geminated digit (ろっぴゃく, はっぴゃく). Accepting them anywhere read
     * ごぜん (午前) as 5 × 千.
     */
    private fun tenUnits(digit: Long?, geminated: Boolean) = listOf("じゅう")
    private fun hundredUnits(digit: Long?, geminated: Boolean) = when {
        geminated -> listOf("ぴゃく")
        digit == 3L -> listOf("びゃく")
        else -> listOf("ひゃく")
    }
    private fun thousandUnits(digit: Long?, geminated: Boolean) = when {
        geminated -> listOf("せん")
        digit == 3L -> listOf("ぜん")
        else -> listOf("せん")
    }
    private val BIG = listOf("ちょう" to 1_000_000_000_000L, "おく" to 100_000_000L, "まん" to 10_000L)

    /**
     * The value of [kana] when the WHOLE string reads as one composite
     * number (at least two numeral words, e.g. にじゅう, せんにひゃく), else
     * null. Single numerals are left to the dictionary.
     */
    fun parse(kana: String): Parsed? {
        if (kana.length < 2) return null
        var pos = 0
        var total = 0L
        var words = 0
        var geminated = false

        // One section below 10^4: [d]せん [d]ひゃく [d]じゅう [d]
        fun section(): Long? {
            var value = 0L
            var any = false
            fun digitAt(): Pair<Long, Int>? {
                for ((r, v) in DIGITS) if (kana.startsWith(r, pos)) return v to r.length
                return null
            }
            fun unit(unitsFor: (Long?, Boolean) -> List<String>, scale: Long, gemUnit: String? = null): Boolean {
                val save = pos
                val savedWords = words
                val d = digitAt()
                var mult = 1L
                var digit: Long? = null
                var gemDigit = false
                if (d != null) { mult = d.first; digit = d.first; pos += d.second; words++ }
                // Geminated digit directly before a unit (はっせん, ろっぴゃく).
                if (d == null) {
                    for ((r, v) in GEMINATED_DIGITS) if (kana.startsWith(r, pos)) {
                        val after = pos + r.length
                        if (unitsFor(v, true).any { kana.startsWith(it, after) }) {
                            mult = v; digit = v; gemDigit = true; pos = after; words++
                        }
                        break
                    }
                }
                val units = unitsFor(digit, gemDigit)
                val u = units.firstOrNull { kana.startsWith(it, pos) }
                if (u != null) {
                    pos += u.length; words++; value += mult * scale; any = true; return true
                }
                // Unit in its geminated form, only at the very end (じゅっぷん → span ends at じゅっ).
                if (gemUnit != null) {
                    for (g in gemUnit.split(',')) if (kana.startsWith(g, pos) && pos + g.length == kana.length) {
                        pos += g.length; words++; value += mult * scale; any = true; geminated = true; return true
                    }
                }
                pos = save; words = savedWords
                return false
            }
            unit(::thousandUnits, 1000)
            unit(::hundredUnits, 100, "ひゃっ")
            unit(::tenUnits, 10, "じゅっ,じっ")
            if (!geminated) {
                val d = digitAt()
                if (d != null) { value += d.first; pos += d.second; words++; any = true }
                else for ((r, v) in GEMINATED_DIGITS) if (kana.startsWith(r, pos) && pos + r.length == kana.length) {
                    value += v; pos += r.length; words++; any = true; geminated = true; break
                }
            }
            return if (any) value else null
        }

        while (pos < kana.length) {
            val start = pos
            val sec = section()
            val big = BIG.firstOrNull { kana.startsWith(it.first, pos) }
            if (big != null && !geminated) {
                pos += big.first.length; words++
                total += (sec ?: 1L) * big.second
                continue
            }
            if (sec == null || pos == start) return null
            total += sec
            break
        }
        if (pos != kana.length || words < 2 || total <= 0) return null
        return Parsed(total, geminated)
    }

    /** 25 → 二十五, 1200 → 千二百, 2026 → 二千二十六, 12000 → 一万二千. */
    fun toKanji(value: Long): String {
        if (value == 0L) return "〇"
        val digits = "〇一二三四五六七八九"
        fun below10k(n: Long): String = buildString {
            val parts = listOf(1000L to "千", 100L to "百", 10L to "十")
            var rest = n
            for ((scale, unit) in parts) {
                val d = (rest / scale).toInt()
                if (d > 0) { if (d > 1) append(digits[d]); append(unit) }
                rest %= scale
            }
            if (rest > 0) append(digits[rest.toInt()])
        }
        val big = listOf(1_000_000_000_000L to "兆", 100_000_000L to "億", 10_000L to "万")
        return buildString {
            var rest = value
            for ((scale, unit) in big) {
                val q = rest / scale
                if (q > 0) { append(if (q == 1L) "一" else below10k(q)); append(unit) }
                rest %= scale
            }
            if (rest > 0) append(below10k(rest))
        }
    }

    /** 12000 → 1万2000 (only for values with a 万/億 part; null otherwise). */
    fun toMixed(value: Long): String? {
        if (value < 10_000L) return null
        val big = listOf(1_000_000_000_000L to "兆", 100_000_000L to "億", 10_000L to "万")
        return buildString {
            var rest = value
            for ((scale, unit) in big) {
                val q = rest / scale
                if (q > 0) { append(q); append(unit) }
                rest %= scale
            }
            if (rest > 0) append(rest)
        }
    }
}
