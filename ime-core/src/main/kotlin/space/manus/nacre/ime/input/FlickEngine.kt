package space.manus.nacre.ime.input

/**
 * Maps 12-key flick directions to kana characters.
 * Also handles dakuten, handakuten, and small kana transformations.
 */
object FlickEngine {

    enum class Direction { Tap, Left, Up, Right, Down }

    data class FlickKey(
        val id: String,
        val label: String,
        val tap: String,
        val left: String? = null,
        val up: String? = null,
        val right: String? = null,
        val down: String? = null,
        /** Full tap-cycle list. If null, cycle is built from tap/left/up/right/down. */
        val tapCycle: List<String>? = null,
    )

    val kanaKeys: List<FlickKey> = listOf(
        FlickKey("あ", "あ", "あ", "い", "う", "え", "お",
            tapCycle = listOf("あ", "い", "う", "え", "お", "ぁ", "ぃ", "ぅ", "ぇ", "ぉ")),
        FlickKey("か", "か", "か", "き", "く", "け", "こ"),
        FlickKey("さ", "さ", "さ", "し", "す", "せ", "そ"),
        FlickKey("た", "た", "た", "ち", "つ", "て", "と",
            tapCycle = listOf("た", "ち", "つ", "て", "と", "っ")),
        FlickKey("な", "な", "な", "に", "ぬ", "ね", "の"),
        FlickKey("は", "は", "は", "ひ", "ふ", "へ", "ほ"),
        FlickKey("ま", "ま", "ま", "み", "む", "め", "も"),
        // iOS: up=ゆ, down=よ (や row has no i/e forms); left/right are repurposed
        // for the corner-bracket shortcut iOS added in iOS 8 (flick left = 「, right = 」).
        // Previously left=ゆ/up=よ, which put both vowels on the wrong direction
        // and dropped the bracket shortcut entirely.
        FlickKey("や", "や", "や", "「", "ゆ", "」", "よ",
            tapCycle = listOf("や", "ゆ", "よ", "ゃ", "ゅ", "ょ")),
        FlickKey("ら", "ら", "ら", "り", "る", "れ", "ろ"),
        FlickKey("わ", "わ", "わ", "を", "ん", "ー", "〜",
            // Was missing 〜 (down-flick target) and ゎ (わ's small form) —
            // every other multi-key row's tapCycle covers all 5 flick slots
            // plus any small form(s); this one previously stopped after ー.
            tapCycle = listOf("わ", "を", "ん", "ー", "〜", "ゎ")),
    )

    data class SymbolFlickKey(
        val id: String,
        val tap: String,
        val left: String? = null,
        val up: String? = null,
        val right: String? = null,
        val down: String? = null,
    )

    val symbolKeys: List<SymbolFlickKey> = listOf(
        SymbolFlickKey("@", "@", "#", "$"),
        SymbolFlickKey("%", "%", "&", "*"),
        SymbolFlickKey("-", "-", "+", "="),
        SymbolFlickKey("(", "(", ")", "[", "]"),
        SymbolFlickKey("{", "{", "}", "<", ">"),
        SymbolFlickKey("/", "/", "\\", "|"),
        SymbolFlickKey("!", "!", "?", "."),
        SymbolFlickKey(":", ":", ";", ","),
        SymbolFlickKey("'", "'", "\"", "`"),
        SymbolFlickKey("~", "~", "^", "_"),
        SymbolFlickKey("「", "「", "」", "『", "』"),
    )

    fun resolveFlick(key: FlickKey, direction: Direction): String? = when (direction) {
        Direction.Tap -> key.tap
        Direction.Left -> key.left
        Direction.Up -> key.up
        Direction.Right -> key.right
        Direction.Down -> key.down
    }

    fun resolveSymbolFlick(key: SymbolFlickKey, direction: Direction): String? = when (direction) {
        Direction.Tap -> key.tap
        Direction.Left -> key.left
        Direction.Up -> key.up
        Direction.Right -> key.right
        Direction.Down -> key.down
    }

    private val dakutenMap = mapOf(
        'か' to 'が', 'き' to 'ぎ', 'く' to 'ぐ', 'け' to 'げ', 'こ' to 'ご',
        'さ' to 'ざ', 'し' to 'じ', 'す' to 'ず', 'せ' to 'ぜ', 'そ' to 'ぞ',
        'た' to 'だ', 'ち' to 'ぢ', 'つ' to 'づ', 'て' to 'で', 'と' to 'ど',
        'は' to 'ば', 'ひ' to 'び', 'ふ' to 'ぶ', 'へ' to 'べ', 'ほ' to 'ぼ',
        'う' to 'ゔ',
    )

    private val handakutenMap = mapOf(
        'は' to 'ぱ', 'ひ' to 'ぴ', 'ふ' to 'ぷ', 'へ' to 'ぺ', 'ほ' to 'ぽ',
    )

    private val smallMap = mapOf(
        'つ' to 'っ', 'や' to 'ゃ', 'ゆ' to 'ゅ', 'よ' to 'ょ',
        'あ' to 'ぁ', 'い' to 'ぃ', 'う' to 'ぅ', 'え' to 'ぇ', 'お' to 'ぉ',
        'わ' to 'ゎ',
    )

    private val reverseDakuten = dakutenMap.entries.associate { (k, v) -> v to k }
    private val reverseHandakuten = handakutenMap.entries.associate { (k, v) -> v to k }
    private val reverseSmall = smallMap.entries.associate { (k, v) -> v to k }

    fun applyDakuten(lastKana: Char): Char? =
        dakutenMap[lastKana] ?: reverseDakuten[lastKana]

    fun applyHandakuten(lastKana: Char): Char? =
        handakutenMap[lastKana] ?: reverseHandakuten[lastKana]

    fun applySmall(lastKana: Char): Char? =
        smallMap[lastKana] ?: reverseSmall[lastKana]

    /**
     * iOS-style ゛小゜ tap cycle: base → small → dakuten → handakuten → base.
     * "Small first, dakuten next" — e.g. つ→っ→づ→つ, は→ば→ぱ→は, あ→ぁ→あ, か→が→か.
     * Returns the next form, or null if the char has no variants.
     */
    fun cycleKanaForm(c: Char): Char? {
        val base = reverseSmall[c] ?: reverseDakuten[c] ?: reverseHandakuten[c] ?: c
        val cycle = buildList {
            add(base)
            smallMap[base]?.let { add(it) }
            dakutenMap[base]?.let { add(it) }
            handakutenMap[base]?.let { add(it) }
        }
        if (cycle.size <= 1) return null
        val idx = cycle.indexOf(c)
        return if (idx >= 0) cycle[(idx + 1) % cycle.size] else cycle[1]
    }
}

enum class DakutenType { Dakuten, Handakuten, Small }
