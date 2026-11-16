package space.manus.nacre.ime.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ゛゜小 transforms must never run under a live conversion.
 *
 * The defect (found by exhaustive sequence search over the flick state
 * machine, 2026-11-17): processFlickDakuten / processFlickDakutenCycle
 * rewrote the last kana of composingFlickKana and pushed it to the
 * editor with setComposingText, but during a conversion the editor is
 * showing a candidate SURFACE and the candidate list / selection /
 * fullKana still describe the pre-transform kana. The two desync, and
 * the updatePredictions() the transform launches lands ~50ms later and
 * forcibly clears isConverting, swapping the conversion candidates for
 * predictions mid-conversion. Every violating sequence in the search
 * (30,964 of them) was this one family: a ゛゜小 press — tap cycle or
 * direct flick — while converting.
 *
 * The fix (InputEngine): when a transform actually applies and a
 * conversion is live, cancelConversion() runs FIRST — the same "back
 * to kana" semantics as backspace during conversion — and the transform
 * then edits the kana composition. A press whose transform does not
 * apply (e.g. on ん) leaves the conversion untouched.
 *
 * The same search proved the surrounding machinery sound, and this
 * suite pins those negatives too: the toggle/transform logic never
 * fabricates a small っ (a strict "creations" oracle over ~3.3M action
 * sequences found zero), the PR #48 seal keeps 「って」 typable, and
 * candidate/space commits clear the flick buffer (PR #75 discipline).
 *
 * The stand-in below mirrors InputEngine's flick contract the way
 * FlickParityTest's FakeKanaPad does, extended with the conversion
 * state (identity dictionary: convert(kana) offers the kana itself).
 */
class FlickDakutenConversionTest {

    /** Editor stand-in: committed text + composing region, Android semantics. */
    private class FakeEditor {
        val text = StringBuilder()
        private var compStart = -1
        private var compEnd = -1
        val composing: String
            get() = if (compStart < 0) "" else text.substring(compStart, compEnd)

        fun setComposingText(t: String) {
            if (compStart < 0) {
                compStart = text.length; compEnd = text.length
            }
            text.replace(compStart, compEnd, t)
            compEnd = compStart + t.length
        }

        fun commitText(t: String) {
            if (compStart < 0) {
                text.append(t)
            } else {
                text.replace(compStart, compEnd, t)
                compStart = -1; compEnd = -1
            }
        }

        fun finishComposing() {
            compStart = -1; compEnd = -1
        }
    }

    /**
     * Mirrors InputEngine AFTER the fix: processFlickKana (toggle +
     * append), processFlickDakutenCycle / processFlickDakuten WITH the
     * cancel-first guard, startFlickConversion / cancelConversion /
     * commitSelectedCandidate with an identity dictionary.
     */
    private class FakeEngine {
        val editor = FakeEditor()
        var buffer: String = ""
            private set
        var converting: Boolean = false
            private set
        private var candidateSurface: String? = null
        private val toggle = FlickToggleState(timeoutMs = 800L)
        var nowMs: Long = 0L

        fun tap(key: FlickEngine.FlickKey) {
            if (converting) commitSelected()
            if (toggle.isArmedFor(key.id, nowMs) && buffer.isNotEmpty()) {
                val cycle = key.tapCycle ?: FlickEngine.tapCycleOf(key)
                val last = buffer.last().toString()
                val idx = cycle.indexOf(last)
                val next = cycle[if (idx >= 0) (idx + 1) % cycle.size else 0]
                buffer = buffer.dropLast(1) + next
                editor.setComposingText(buffer)
                toggle.touch(nowMs)
                return
            }
            buffer += key.tap
            editor.setComposingText(buffer)
            toggle.arm(key.id, nowMs, key.tapCycle)
        }

        fun flick(kana: String) {
            if (converting) commitSelected()
            buffer += kana
            editor.setComposingText(buffer)
            toggle.disarm()
        }

        fun dakutenCycle() {
            if (buffer.isEmpty()) return
            val replaced = FlickEngine.cycleKanaForm(buffer.last()) ?: return
            if (converting) cancelConversion()
            buffer = buffer.dropLast(1) + replaced
            editor.setComposingText(buffer)
            toggle.disarm()
        }

        fun dakutenDirect(type: DakutenType) {
            if (buffer.isEmpty()) return
            val last = buffer.last()
            val replaced = when (type) {
                DakutenType.Dakuten ->
                    FlickEngine.applyDakuten(last)
                        ?: FlickEngine.applyHandakuten(last)
                        ?: FlickEngine.applySmall(last)
                DakutenType.Handakuten -> FlickEngine.applyHandakuten(last)
                DakutenType.Small -> FlickEngine.applySmall(last)
            } ?: return
            if (converting) cancelConversion()
            buffer = buffer.dropLast(1) + replaced
            editor.setComposingText(buffer)
            toggle.disarm()
        }

        fun space() {
            if (buffer.isEmpty()) return
            if (!converting) {
                converting = true
                candidateSurface = buffer // identity dictionary
                editor.setComposingText(buffer)
            }
        }

        fun enter() {
            if (converting) {
                commitSelected()
            } else if (buffer.isNotEmpty()) {
                editor.commitText(buffer)
                buffer = ""
                toggle.disarm()
            }
        }

        private fun cancelConversion() {
            editor.setComposingText(buffer)
            converting = false
            candidateSurface = null
        }

        private fun commitSelected() {
            editor.commitText(candidateSurface ?: buffer)
            buffer = ""
            converting = false
            candidateSurface = null
            toggle.disarm()
        }

        /** The invariant the bug broke; must hold after EVERY action. */
        fun invariantHolds(): Boolean =
            if (converting) {
                editor.composing == candidateSurface
            } else {
                editor.composing == buffer
            }
    }

    private fun key(id: String): FlickEngine.FlickKey =
        FlickEngine.kanaKeys.first { it.id == id }

    private fun assertInvariant(e: FakeEngine, step: String) {
        assertTrue("invariant after $step: editor composing must match engine state", e.invariantHolds())
    }

    // ── The fix: transform during conversion ──────────────────────

    @Test
    fun `dakuten cycle during conversion reverts to kana then transforms`() {
        val e = FakeEngine()
        val ta = key("た")
        repeat(3) { e.tap(ta); e.nowMs += 100L }
        assertEquals("つ", e.buffer)
        e.space() // conversion starts; editor shows the candidate surface
        assertTrue(e.converting)
        assertInvariant(e, "conversion start")
        e.dakutenCycle() // つ → っ, on the reverted kana composition
        assertFalse("transform must leave the conversion state", e.converting)
        assertEquals("っ", e.buffer)
        assertEquals("っ", e.editor.composing)
        assertInvariant(e, "dakuten during conversion")
        e.enter()
        assertEquals("っ", e.editor.text.toString()) // exactly one っ, not lost, not doubled
    }

    @Test
    fun `dakuten during conversion transforms only the last kana`() {
        val e = FakeEngine()
        val ta = key("た")
        repeat(3) { e.tap(ta); e.nowMs += 100L } // つ
        e.flick("つ") // つつ (flick appends, toggle disarmed)
        e.space()
        e.dakutenCycle()
        assertEquals("つっ", e.buffer)
        assertEquals("つっ", e.editor.composing)
        assertInvariant(e, "dakuten during conversion")
    }

    @Test
    fun `direct dakuten flick during conversion also reverts first`() {
        val e = FakeEngine()
        val ta = key("た")
        repeat(3) { e.tap(ta); e.nowMs += 100L } // つ
        e.space()
        e.dakutenDirect(DakutenType.Dakuten) // つ → づ (dakuten direct)
        assertFalse(e.converting)
        assertEquals("づ", e.buffer)
        assertInvariant(e, "direct dakuten during conversion")
    }

    @Test
    fun `typing continues on the transformed kana after a mid-conversion transform`() {
        val e = FakeEngine()
        val ta = key("た")
        repeat(3) { e.tap(ta); e.nowMs += 100L } // つ
        e.space()
        e.dakutenCycle() // っ (sealed, conversion reverted)
        e.tap(ta) // next mora starts fresh: the っ must survive
        assertEquals("った", e.buffer)
        repeat(3) { e.nowMs += 100L; e.tap(ta) }
        assertEquals("って", e.buffer)
        assertInvariant(e, "typing after mid-conversion transform")
    }

    @Test
    fun `a no-op dakuten press during conversion leaves the conversion untouched`() {
        val e = FakeEngine()
        val wa = key("わ")
        e.tap(wa); e.nowMs += 100L
        e.tap(wa); e.nowMs += 100L
        e.tap(wa) // ん (3rd in the わ tap cycle)
        assertEquals("ん", e.buffer)
        e.space()
        assertTrue(e.converting)
        e.dakutenCycle() // ん has no variants: no transform, no cancel
        assertTrue("no-op press must not cancel the conversion", e.converting)
        assertEquals("ん", e.editor.composing)
        assertInvariant(e, "no-op dakuten during conversion")
    }

    // ── Pinned negatives from the diagnosis ───────────────────────

    @Test
    fun `single small tsu from the ta tap cycle, never doubled`() {
        val e = FakeEngine()
        val ta = key("た")
        repeat(6) { e.tap(ta); e.nowMs += 100L } // た→ち→つ→て→と→っ
        assertEquals("っ", e.buffer)
        assertEquals("っ", e.editor.composing)
        e.enter()
        assertEquals("っ", e.editor.text.toString())
    }

    @Test
    fun `zutto types end to end with taps and the dakuten key`() {
        val e = FakeEngine()
        val sa = key("さ"); val ta = key("た")
        var t = 0L
        fun taps(k: FlickEngine.FlickKey, n: Int) {
            repeat(n) { e.nowMs = t; e.tap(k); t += 100L }
        }
        taps(sa, 3) // す
        e.dakutenCycle() // ず
        taps(ta, 3) // つ
        e.dakutenCycle() // っ
        taps(ta, 5) // と
        assertEquals("ずっと", e.buffer)
        e.enter()
        assertEquals("ずっと", e.editor.text.toString())
    }

    @Test
    fun `yappari types end to end`() {
        val e = FakeEngine()
        val ya = key("や"); val ta = key("た"); val ha = key("は"); val ra = key("ら")
        var t = 0L
        fun taps(k: FlickEngine.FlickKey, n: Int) {
            repeat(n) { e.nowMs = t; e.tap(k); t += 100L }
        }
        taps(ya, 1) // や
        taps(ta, 3) // つ
        e.dakutenCycle() // っ
        taps(ha, 1) // は
        e.dakutenCycle() // ば
        e.dakutenCycle() // ぱ
        taps(ra, 2) // り
        assertEquals("やっぱり", e.buffer)
        e.enter()
        assertEquals("やっぱり", e.editor.text.toString())
    }

    @Test
    fun `small and dakuten cycles stay intact outside conversion`() {
        val e = FakeEngine()
        val ta = key("た"); val wa = key("わ"); val ya = key("や")
        repeat(3) { e.tap(ta); e.nowMs += 100L } // つ
        e.dakutenCycle(); assertEquals("っ", e.buffer)
        e.dakutenCycle(); assertEquals("づ", e.buffer)
        e.dakutenCycle(); assertEquals("つ", e.buffer)

        val e2 = FakeEngine()
        e2.tap(wa)
        e2.dakutenCycle(); assertEquals("ゎ", e2.buffer)

        val e3 = FakeEngine()
        e3.tap(ya)
        e3.dakutenCycle(); assertEquals("ゃ", e3.buffer)
    }

    @Test
    fun `invariant holds across mixed conversion and transform sequences`() {
        // The previously violating families from the exhaustive search:
        // transforms (cycle + direct flicks) landing right after a
        // conversion starts, in various kana contexts.
        val scenarios: List<List<FakeEngine.() -> Unit>> = listOf(
            listOf(
                { tap(key("た")) }, { tap(key("た")) }, { tap(key("た")) },
                { space() }, { dakutenCycle() },
            ),
            listOf(
                { tap(key("た")) }, { tap(key("た")) }, { tap(key("た")) },
                { dakutenCycle() }, { space() }, { dakutenCycle() },
            ),
            listOf(
                { tap(key("た")) }, { tap(key("た")) }, { tap(key("た")) },
                { flick("つ") }, { space() }, { dakutenDirect(DakutenType.Dakuten) },
            ),
            listOf(
                { tap(key("か")) }, { space() }, { dakutenCycle() }, { enter() },
            ),
            listOf(
                { tap(key("は")) }, { space() }, { dakutenCycle() },
                { dakutenCycle() }, { enter() },
            ),
        )
        for ((i, steps) in scenarios.withIndex()) {
            val e = FakeEngine()
            for ((j, step) in steps.withIndex()) {
                e.step()
                e.nowMs += 100L
                assertInvariant(e, "scenario $i step $j")
            }
        }
    }
}
