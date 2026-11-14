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
        // The tap cycle follows the same cross order (tap→left→up→right→down),
        // so the brackets sit at their flick positions — a tap-only user can
        // still reach 「/」 by tapping through (や→「→ゆ→」→よ), then the smalls.
        FlickKey("や", "や", "や", "「", "ゆ", "」", "よ",
            tapCycle = listOf("や", "「", "ゆ", "」", "よ", "ゃ", "ゅ", "ょ")),
        FlickKey("ら", "ら", "ら", "り", "る", "れ", "ろ"),
        FlickKey("わ", "わ", "わ", "を", "ん", "ー", "〜",
            // iOS / feature-phone multi-tap order: after the 5 cross slots the
            // わ key keeps cycling into the punctuation set 、。！？・… — this is
            // the second iPhone path to 。 (7 taps). ゎ is NOT in the cycle:
            // on iOS the small form is reached via the ゛゜小 key, like every
            // other row's smalls that have a dedicated transform.
            tapCycle = listOf("わ", "を", "ん", "ー", "〜", "、", "。", "！", "？", "・", "…")),
    )

    /**
     * The 、。？！ key (bottom-right of the kana grid) — iPhone stock assignment:
     * tap=、, left=。, up=？, right=！, down=… (the key's cross order, the same
     * tap→left→up→right→down rule as the kana rows). The tap cycle follows
     * that cross order, so 。 is 2 taps from fresh — the primary iPhone path.
     * ・ is appended at the cycle tail for tap reachability (on iOS it lives
     * on this key's long-press, which the tap-centric pad has no equivalent of).
     *
     * History: this key previously had up/right swapped (up=！, right=？),
     * down=・, and a tap cycle that started at 。 while tap inserted 、 —
     * the cycle index of 、 was 1, so 。 only came back around after a full
     * wrap: 10 taps. See PR "12-key layout and input order to iPhone stock parity".
     */
    val punctKey: FlickKey = FlickKey(
        id = "punct", label = "、。", tap = "、",
        left = "。", up = "？", right = "！", down = "…",
        tapCycle = listOf("、", "。", "？", "！", "…", "・"),
    )

    /**
     * The effective multi-tap cycle for a key: the explicit [FlickKey.tapCycle]
     * when present, otherwise the flick cross in tap→left→up→right→down order.
     * Mirrors the fallback chain in InputEngine.processFlickKana.
     */
    fun tapCycleOf(key: FlickKey): List<String> =
        key.tapCycle ?: listOfNotNull(key.tap, key.left, key.up, key.right, key.down)

    /**
     * How many taps on [key], starting fresh, until [target] is the current
     * character — simulating InputEngine's toggle rule exactly (first tap
     * inserts [FlickKey.tap]; each further tap advances within [tapCycleOf]
     * from the current character's index, wrapping). Returns -1 when [target]
     * is unreachable by tapping.
     */
    fun tapsToReach(key: FlickKey, target: String): Int {
        val cycle = tapCycleOf(key)
        var current = key.tap
        var taps = 1
        while (taps <= cycle.size + 1) {
            if (current == target) return taps
            val idx = cycle.indexOf(current)
            if (idx < 0) return -1
            current = cycle[(idx + 1) % cycle.size]
            taps++
        }
        return -1
    }

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

/**
 * Multi-tap state for the ABC (alpha) 12-key grid, where characters commit
 * IMMEDIATELY (iPhone-style: no composing buffer, no 確定 press). A repeated
 * tap on the same key within [timeoutMs] does not insert again — it asks the
 * caller to REPLACE the just-committed character with the next cycle entry
 * (the caller verifies the character before the cursor really is the pending
 * one before deleting anything). Pure and clock-injected for unit tests.
 */
class AlphaTapCycler(private val timeoutMs: Long = 800L) {

    private var keyId: String = ""
    private var cycle: List<String> = emptyList()
    private var index: Int = -1
    private var lastTapMs: Long = 0L

    /**
     * Register a tap on [keyId] whose cycle is [cycle] and whose first-tap
     * character sits at [startIndex] within it. Returns the cycle index whose
     * character should replace the pending one, or null when this tap starts
     * a fresh character (the cycler is then armed at [startIndex]).
     */
    fun tap(keyId: String, cycle: List<String>, startIndex: Int, nowMs: Long): Int? {
        val armed = index >= 0 && keyId == this.keyId && cycle == this.cycle &&
            nowMs - lastTapMs < timeoutMs
        return if (armed) {
            index = (index + 1) % cycle.size
            lastTapMs = nowMs
            index
        } else {
            this.keyId = keyId
            this.cycle = cycle
            index = startIndex
            lastTapMs = nowMs
            null
        }
    }

    /** The cycle index of the currently pending (last inserted) character, or -1. */
    val pendingIndex: Int get() = index

    fun reset() {
        keyId = ""
        cycle = emptyList()
        index = -1
        lastTapMs = 0L
    }
}
