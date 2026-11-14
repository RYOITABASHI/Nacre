package space.manus.nacre.ime.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * iPhone stock Japanese kana keyboard parity for the 12-key pad:
 * key flick assignments, per-key multi-tap cycles, tap counts, the
 * ゛゜小 cycling transform, and the ABC grid's immediate-commit cycler.
 *
 * Reference (verified 2026-11-15):
 * - Flick cross order is the row order: tap, left, up, right, down
 *   (あ row: あ い う え お). Multi-tap steps through the same order.
 * - や: left=「, up=ゆ, right=」, down=よ. わ: left=を, up=ん, right=ー, down=〜.
 * - 、。？！ key: tap=、, left=。, up=？, right=！, down=….
 * - ゛゜小 key: tap cycles small → dakuten → handakuten → base
 *   (つ→っ→づ→つ, は→ば→ぱ→は); flick left=゛, right=゜ directly.
 * Sources: https://www.kitsunewa.com/guides/japanese-flick-keyboard
 *          https://wmkeyboard.app/languages/cjk/
 *          https://www.fluentu.com/help/how-do-i-type-japanese-on-my-ios-device/
 * Assumptions where sources are silent (stated in the PR): the わ tap-cycle
 * punctuation tail order 、。！？・… (feature-phone convention), brackets
 * sitting at their flick positions inside the や tap cycle, and ・ appended
 * at the end of the 、。？！ tap cycle for tap reachability (iOS: long-press).
 */
class FlickParityTest {

    private fun key(id: String): FlickEngine.FlickKey =
        FlickEngine.kanaKeys.first { it.id == id } // throws if missing: also a check

    private fun assertFlicks(
        key: FlickEngine.FlickKey,
        tap: String, left: String?, up: String?, right: String?, down: String?,
    ) {
        assertEquals("tap of ${key.id}", tap, key.tap)
        assertEquals("left of ${key.id}", left, key.left)
        assertEquals("up of ${key.id}", up, key.up)
        assertEquals("right of ${key.id}", right, key.right)
        assertEquals("down of ${key.id}", down, key.down)
    }

    // ── Flick assignments ──────────────────────────────────────────

    @Test
    fun `vowel rows use cross order tap-left-up-right-down`() {
        assertFlicks(key("あ"), "あ", "い", "う", "え", "お")
        assertFlicks(key("か"), "か", "き", "く", "け", "こ")
        assertFlicks(key("さ"), "さ", "し", "す", "せ", "そ")
        assertFlicks(key("た"), "た", "ち", "つ", "て", "と")
        assertFlicks(key("な"), "な", "に", "ぬ", "ね", "の")
        assertFlicks(key("は"), "は", "ひ", "ふ", "へ", "ほ")
        assertFlicks(key("ま"), "ま", "み", "む", "め", "も")
        assertFlicks(key("ら"), "ら", "り", "る", "れ", "ろ")
    }

    @Test
    fun `ya key brackets sit on left and right`() {
        assertFlicks(key("や"), "や", "「", "ゆ", "」", "よ")
    }

    @Test
    fun `wa key flicks are wo n choon wave`() {
        assertFlicks(key("わ"), "わ", "を", "ん", "ー", "〜")
    }

    @Test
    fun `punct key matches the iPhone cross`() {
        assertFlicks(FlickEngine.punctKey, "、", "。", "？", "！", "…")
    }

    // ── Tap cycles ─────────────────────────────────────────────────

    @Test
    fun `every key tap cycle equals the iPhone reference`() {
        val expected = mapOf(
            "あ" to listOf("あ", "い", "う", "え", "お", "ぁ", "ぃ", "ぅ", "ぇ", "ぉ"),
            "か" to listOf("か", "き", "く", "け", "こ"),
            "さ" to listOf("さ", "し", "す", "せ", "そ"),
            "た" to listOf("た", "ち", "つ", "て", "と", "っ"),
            "な" to listOf("な", "に", "ぬ", "ね", "の"),
            "は" to listOf("は", "ひ", "ふ", "へ", "ほ"),
            "ま" to listOf("ま", "み", "む", "め", "も"),
            "や" to listOf("や", "「", "ゆ", "」", "よ", "ゃ", "ゅ", "ょ"),
            "ら" to listOf("ら", "り", "る", "れ", "ろ"),
            "わ" to listOf("わ", "を", "ん", "ー", "〜", "、", "。", "！", "？", "・", "…"),
            "punct" to listOf("、", "。", "？", "！", "…", "・"),
        )
        for ((id, cycle) in expected) {
            val k = if (id == "punct") FlickEngine.punctKey else key(id)
            assertEquals("tap cycle of $id", cycle, FlickEngine.tapCycleOf(k))
        }
    }

    @Test
    fun `tap cycle always starts at the tap character`() {
        // The original 。 bug: the cycle started at a different character
        // than the one a first tap inserts, pushing 。 to a full wrap (10 taps).
        for (k in FlickEngine.kanaKeys + FlickEngine.punctKey) {
            assertEquals("cycle head of ${k.id}", k.tap, FlickEngine.tapCycleOf(k).first())
        }
    }

    // ── Tap counts (the C1 complaint: 。 took ~10 taps) ────────────

    @Test
    fun `maru takes 2 taps on the punct key like iPhone`() {
        assertEquals(1, FlickEngine.tapsToReach(FlickEngine.punctKey, "、"))
        assertEquals(2, FlickEngine.tapsToReach(FlickEngine.punctKey, "。"))
        assertEquals(3, FlickEngine.tapsToReach(FlickEngine.punctKey, "？"))
        assertEquals(4, FlickEngine.tapsToReach(FlickEngine.punctKey, "！"))
    }

    @Test
    fun `maru takes 7 taps via the wa key like iPhone`() {
        assertEquals(7, FlickEngine.tapsToReach(key("わ"), "。"))
        assertEquals(6, FlickEngine.tapsToReach(key("わ"), "、"))
    }

    @Test
    fun `other tap counts follow the cross order`() {
        assertEquals(5, FlickEngine.tapsToReach(key("あ"), "お"))
        assertEquals(6, FlickEngine.tapsToReach(key("あ"), "ぁ"))
        assertEquals(6, FlickEngine.tapsToReach(key("た"), "っ"))
        assertEquals(2, FlickEngine.tapsToReach(key("や"), "「"))
        assertEquals(4, FlickEngine.tapsToReach(key("や"), "」"))
        assertEquals(3, FlickEngine.tapsToReach(key("わ"), "ん"))
        assertEquals(-1, FlickEngine.tapsToReach(key("か"), "。"))
    }

    // ── ゛゜小 key transform: small → dakuten → handakuten → base ──

    @Test
    fun `dakuten key cycles small first then dakuten then handakuten`() {
        assertEquals('っ', FlickEngine.cycleKanaForm('つ'))
        assertEquals('づ', FlickEngine.cycleKanaForm('っ'))
        assertEquals('つ', FlickEngine.cycleKanaForm('づ'))
        assertEquals('ば', FlickEngine.cycleKanaForm('は'))
        assertEquals('ぱ', FlickEngine.cycleKanaForm('ば'))
        assertEquals('は', FlickEngine.cycleKanaForm('ぱ'))
        assertEquals('が', FlickEngine.cycleKanaForm('か'))
        assertEquals('か', FlickEngine.cycleKanaForm('が'))
        assertEquals('ぁ', FlickEngine.cycleKanaForm('あ'))
        assertEquals('あ', FlickEngine.cycleKanaForm('ぁ'))
        assertEquals('ゎ', FlickEngine.cycleKanaForm('わ'))
        assertNull(FlickEngine.cycleKanaForm('ん'))
    }

    // ── ABC immediate-commit cycler (the C2 seam) ──────────────────

    /** Minimal editor stand-in: applies the engine's insert/replace contract. */
    private class FakeEditor {
        val text = StringBuilder()
        fun insert(s: String) { text.append(s) }
        fun replaceLast(s: String) { text.deleteCharAt(text.length - 1); text.append(s) }
        override fun toString() = text.toString()
    }

    private fun tapAlpha(
        editor: FakeEditor,
        cycler: AlphaTapCycler,
        keyId: String,
        cycle: List<String>,
        nowMs: Long,
    ) {
        val startIndex = 0 // first-tap character is cycle[0] on every ABC key
        val replace = cycler.tap(keyId, cycle, startIndex, nowMs)
        if (replace == null) {
            editor.insert(cycle[startIndex])
        } else {
            // Engine-side guard: the char before the cursor must be the
            // pending cycle entry for the replace to happen.
            val expectedPrev = cycle[(replace + cycle.size - 1) % cycle.size]
            assertEquals(expectedPrev, editor.text.last().toString())
            editor.replaceLast(cycle[replace])
        }
    }

    @Test
    fun `alpha taps commit immediately and cycle by replacement`() {
        val editor = FakeEditor()
        val cycler = AlphaTapCycler(timeoutMs = 800L)
        val abc = listOf("a", "b", "c")
        tapAlpha(editor, cycler, "abc", abc, nowMs = 0L)
        assertEquals("a", editor.toString()) // visible after ONE tap, no 確定
        tapAlpha(editor, cycler, "abc", abc, nowMs = 100L)
        assertEquals("b", editor.toString())
        tapAlpha(editor, cycler, "abc", abc, nowMs = 200L)
        assertEquals("c", editor.toString())
        tapAlpha(editor, cycler, "abc", abc, nowMs = 300L)
        assertEquals("a", editor.toString()) // wraps
    }

    @Test
    fun `alpha cycle breaks on a different key and on timeout`() {
        val editor = FakeEditor()
        val cycler = AlphaTapCycler(timeoutMs = 800L)
        val abc = listOf("a", "b", "c")
        val def = listOf("d", "e", "f")
        tapAlpha(editor, cycler, "abc", abc, nowMs = 0L)
        tapAlpha(editor, cycler, "def", def, nowMs = 100L) // different key: fresh insert
        assertEquals("ad", editor.toString())
        tapAlpha(editor, cycler, "def", def, nowMs = 200L)
        assertEquals("ae", editor.toString())
        tapAlpha(editor, cycler, "def", def, nowMs = 2000L) // window expired: fresh insert
        assertEquals("aed", editor.toString())
    }

    @Test
    fun `alpha cycler reset disarms the pending replace`() {
        val cycler = AlphaTapCycler(timeoutMs = 800L)
        val abc = listOf("a", "b", "c")
        assertNull(cycler.tap("abc", abc, startIndex = 0, nowMs = 0L))
        assertEquals(1, cycler.tap("abc", abc, startIndex = 0, nowMs = 100L))
        cycler.reset()
        assertEquals(-1, cycler.pendingIndex)
        assertNull(cycler.tap("abc", abc, startIndex = 0, nowMs = 200L))
    }

    // ── ゛゜小 transform seals the pending toggle ────────────────────
    //
    // The bug: a ゛゜小 transform rewrote the last composing character but
    // left the multi-tap toggle armed from the row taps that produced it,
    // so the next same-row tap within the window REPLACED the transformed
    // character (っ→た, が→か) and 「って」 lost its っ. The fix seals the
    // character on transform (InputEngine calls confirmFlickToggle(),
    // i.e. FlickToggleState.disarm(), after every successful transform).

    /** Minimal stand-in mirroring InputEngine.processFlickKana's tap contract. */
    private class FakeKanaPad {
        val toggle = FlickToggleState(timeoutMs = 800L)
        var composing: String = ""
            private set

        fun tap(key: FlickEngine.FlickKey, nowMs: Long) {
            if (toggle.isArmedFor(key.id, nowMs) && composing.isNotEmpty()) {
                // Engine cycle resolution: pad override → stored → key cycle.
                val cycle = key.tapCycle ?: toggle.cycle ?: FlickEngine.tapCycleOf(key)
                val last = composing.last().toString()
                val idx = cycle.indexOf(last)
                val next = cycle[if (idx >= 0) (idx + 1) % cycle.size else 0]
                composing = composing.dropLast(1) + next
                toggle.touch(nowMs)
                return
            }
            composing += key.tap
            toggle.arm(key.id, nowMs, key.tapCycle)
        }

        /** Mirrors processFlickDakutenCycle AFTER the fix: transform, then seal. */
        fun dakutenKey() {
            if (composing.isEmpty()) return
            val replaced = FlickEngine.cycleKanaForm(composing.last()) ?: return
            composing = composing.dropLast(1) + replaced
            toggle.disarm()
        }
    }

    @Test
    fun `tte types end to end with taps only`() {
        val pad = FakeKanaPad()
        val ta = key("た")
        pad.tap(ta, 0L); pad.tap(ta, 100L); pad.tap(ta, 200L)
        assertEquals("つ", pad.composing)
        pad.dakutenKey() // つ → っ, sealed
        assertEquals("っ", pad.composing)
        // て is on the SAME row: without the seal, the next tap (still
        // inside the window from t=200) would replace っ with た.
        pad.tap(ta, 300L)
        assertEquals("った", pad.composing)
        pad.tap(ta, 400L); pad.tap(ta, 500L); pad.tap(ta, 600L)
        assertEquals("って", pad.composing)
    }

    @Test
    fun `dakuten form is sealed the same way as small kana`() {
        val pad = FakeKanaPad()
        val ka = key("か")
        pad.tap(ka, 0L)
        pad.dakutenKey() // か → が, sealed
        assertEquals("が", pad.composing)
        // Without the seal this tap would replace が with か
        // (が is not in the か tap cycle, so the toggle wrapped to its head).
        pad.tap(ka, 100L)
        assertEquals("がか", pad.composing)
        pad.tap(ka, 200L)
        assertEquals("がき", pad.composing)
    }

    @Test
    fun `handakuten form is sealed the same way`() {
        val pad = FakeKanaPad()
        val ha = key("は")
        pad.tap(ha, 0L)
        pad.dakutenKey(); pad.dakutenKey() // は → ば → ぱ
        assertEquals("ぱ", pad.composing)
        pad.tap(ha, 100L) // would replace ぱ with は without the seal
        assertEquals("ぱは", pad.composing)
    }

    @Test
    fun `dakuten key keeps cycling after sealing`() {
        // Sealing must not break the transform cycle itself: repeated
        // ゛゜小 presses edit the composing string directly.
        val pad = FakeKanaPad()
        val ta = key("た")
        pad.tap(ta, 0L); pad.tap(ta, 100L); pad.tap(ta, 200L)
        pad.dakutenKey()
        assertEquals("っ", pad.composing)
        pad.dakutenKey()
        assertEquals("づ", pad.composing)
        pad.dakutenKey()
        assertEquals("つ", pad.composing)
    }

    @Test
    fun `toggle state arms expires and disarms`() {
        val state = FlickToggleState(timeoutMs = 800L)
        assertEquals(false, state.isArmed)
        state.arm("た", nowMs = 1000L, cycle = null)
        assertEquals(true, state.isArmed)
        assertEquals(true, state.isArmedFor("た", nowMs = 1799L))
        assertEquals(false, state.isArmedFor("た", nowMs = 1800L)) // window is [arm, arm+800)
        assertEquals(false, state.isArmedFor("か", nowMs = 1100L)) // different key
        state.touch(nowMs = 1500L)
        assertEquals(true, state.isArmedFor("た", nowMs = 2000L))
        state.disarm()
        assertEquals(false, state.isArmed)
        assertEquals(false, state.isArmedFor("た", nowMs = 1600L))
    }
}
