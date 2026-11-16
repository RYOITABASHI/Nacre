package space.manus.nacre.ime.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * QWERTY romaji triple-consonant sequences (ttt / nnn) — the owner's
 * on-device report (2026-11-17, build-462/466): typing っ on QWERTY
 * tends to produce っっ, and the same class of doubling shows around
 * n-runs. The 12-key flick logic was already proven clean (PR #83);
 * this suite isolates the QWERTY path layer by layer.
 *
 * The stand-in below mirrors InputEngine's romaji contract exactly:
 * processJapaneseInput appends the delivered character to the romaji
 * buffer and re-converts the WHOLE buffer with the real
 * [JapaneseEngine] (finalize=false while typing, finalize=true at
 * conversion/commit — startConversion / finishComposing). KeyView
 * delivers one KeyAction.Text per tap gesture, so one type() call here
 * is one physical tap.
 *
 * Reference semantics are Gboard/Mozc's (the owner's own keystroke
 * model): ん is typed "nn" and a following な-row mora adds its own
 * "n…" — so こんにちは is keystroked "konnnichiha", かんにん is
 * "kannnin", しんねん is "shinnnen". Mozc's table resolves "nn" as
 * ん consuming both n's (hence "konnichiha" → こんいちは and
 * "kanni" → かんい in Mozc too), and sokuon as a partial conversion
 * ("tt" → っ + keep "t"), so "ttt" holds the third consonant pending.
 *
 * Findings pinned by this suite:
 *  - With each keystroke delivered exactly once, the kana layer
 *    CANNOT double: every standard word and every literal run below
 *    matches Mozc. The sokuon rule (a), the ん rules (b) and the
 *    finalize flush (d) do not mis-fire.
 *  - The owner's symptom is reproduced EXACTLY when one keystroke in
 *    the run is delivered twice (ずっと → ずっっと, the n-run of
 *    こんにちは gaining a ん) — i.e. the defect lives in key
 *    DELIVERY (c), and the fix is the same-key bounce guard in
 *    KeyView (see SameKeyBounceTest).
 */
class RomajiTripleConsonantTest {

    /** Mirrors InputEngine's romaji path (see class doc). */
    private class FakeRomajiInput {
        private val engine = JapaneseEngine()
        var romaji: String = ""
            private set

        /** One delivered keystroke. Returns the composing display. */
        fun type(ch: Char): String {
            romaji += ch
            return engine.romajiToHiragana(romaji)
        }

        fun typeAll(s: String): String {
            var display = ""
            for (c in s) display = type(c)
            return display
        }

        /** Conversion/commit: finalize=true over the same buffer. */
        fun finalize(): String = engine.romajiToHiragana(romaji, finalize = true)
    }

    // ── Standard words: sokuon (tt) ──

    @Test
    fun `zutto converts to a single sokuon word`() {
        val input = FakeRomajiInput()
        assertEquals("ずっと", input.typeAll("zutto"))
        assertEquals("ずっと", input.finalize())
    }

    @Test
    fun `motto converts to a single sokuon word`() {
        val input = FakeRomajiInput()
        assertEquals("もっと", input.typeAll("motto"))
        assertEquals("もっと", input.finalize())
    }

    @Test
    fun `datta converts to a single sokuon word`() {
        val input = FakeRomajiInput()
        assertEquals("だった", input.typeAll("datta"))
        assertEquals("だった", input.finalize())
    }

    // ── Standard words: ん + な-row (the nnn keystrokes) ──

    @Test
    fun `konnnichiha converts to konnichiwa`() {
        val input = FakeRomajiInput()
        assertEquals("こんにちは", input.typeAll("konnnichiha"))
        assertEquals("こんにちは", input.finalize())
    }

    @Test
    fun `kannnin converts to kannin`() {
        val input = FakeRomajiInput()
        // Display keeps the trailing n pending; finalize flushes it to ん.
        assertEquals("かんにn", input.typeAll("kannnin"))
        assertEquals("かんにん", input.finalize())
    }

    @Test
    fun `shinnnen converts to shinnen`() {
        val input = FakeRomajiInput()
        assertEquals("しんねn", input.typeAll("shinnnen"))
        assertEquals("しんねん", input.finalize())
    }

    // ── Mozc reference points around the n rules ──

    @Test
    fun `konnichiha with two ns is the Mozc spelling of konichiha`() {
        // Mozc/Gboard resolve "nn" as ん consuming both n's, so the
        // two-n spelling yields こんいちは there too; こんにちは needs
        // the な-row's own n (konnnichiha, pinned above).
        val input = FakeRomajiInput()
        assertEquals("こんいちは", input.typeAll("konnichiha"))
    }

    @Test
    fun `kanni is kan'i and onnna is onna`() {
        assertEquals("かんい", FakeRomajiInput().typeAll("kanni"))
        assertEquals("おんな", FakeRomajiInput().typeAll("onnna"))
    }

    @Test
    fun `nn at word end is a single n`() {
        val input = FakeRomajiInput()
        assertEquals("ほん", input.typeAll("honn"))
        assertEquals("ほん", input.finalize())
    }

    // ── Literal runs ──

    @Test
    fun `ttta yields two sokuon plus ta`() {
        val input = FakeRomajiInput()
        assertEquals("っった", input.typeAll("ttta"))
        assertEquals("っった", input.finalize())
    }

    @Test
    fun `nnna yields n plus na`() {
        val input = FakeRomajiInput()
        assertEquals("んな", input.typeAll("nnna"))
        assertEquals("んな", input.finalize())
    }

    @Test
    fun `ttt alone holds the third consonant pending`() {
        val input = FakeRomajiInput()
        // Mozc partial conversion: "tt" -> っ + keep "t"; the third t
        // starts a new pending consonant — it is NOT flushed as a
        // second committed mora, and finalize does not invent one.
        assertEquals("っっt", input.typeAll("ttt"))
        assertEquals("っっt", input.finalize())
    }

    @Test
    fun `emphatic zuttto keeps both sokuon`() {
        // ずっっと (emphatic) is legitimate and must not be "fixed" away.
        val input = FakeRomajiInput()
        assertEquals("ずっっと", input.typeAll("zuttto"))
        assertEquals("ずっっと", input.finalize())
    }

    // ── Symptom signature: one duplicated delivery in the run ──
    //
    // If the kana layer mis-parsed, the single-delivery cases above
    // would fail. They do not. Delivering ONE keystroke of the run
    // twice reproduces the owner's report exactly — the extra letter
    // is in the romaji buffer itself, so the defect is in key
    // delivery, not in conversion.

    @Test
    fun `one duplicated t delivery doubles the sokuon`() {
        val input = FakeRomajiInput()
        // "zutto" with the second t delivered twice.
        assertEquals("ずっっと", input.typeAll("zuttto"))
    }

    @Test
    fun `one duplicated n delivery doubles the n`() {
        val input = FakeRomajiInput()
        // "konnnichiha" with one extra n in the run.
        assertEquals("こんんいちは", input.typeAll("konnnnichiha"))
    }
}
