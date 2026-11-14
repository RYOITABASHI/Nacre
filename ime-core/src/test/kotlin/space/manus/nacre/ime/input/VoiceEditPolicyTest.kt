package space.manus.nacre.ime.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [VoiceEditPolicy]: Speak to Edit may only engage on a
 * reliably read non-empty selection, and may only apply while the
 * selection is still exactly the captured original.
 */
class VoiceEditPolicyTest {

    @Test
    fun `edit mode requires a readable non-empty selection`() {
        assertTrue(VoiceEditPolicy.shouldEnterEditMode("明日の会議は10時からです", selectionReadable = true))
        assertFalse(VoiceEditPolicy.shouldEnterEditMode("", selectionReadable = true))
        assertFalse(VoiceEditPolicy.shouldEnterEditMode(null, selectionReadable = true))
        assertFalse(VoiceEditPolicy.shouldEnterEditMode("選択テキスト", selectionReadable = false))
        assertFalse(VoiceEditPolicy.shouldEnterEditMode(null, selectionReadable = false))
    }

    @Test
    fun `selection intact only on exact match`() {
        assertTrue(VoiceEditPolicy.isSelectionIntact("元の文", "元の文"))
        assertFalse(VoiceEditPolicy.isSelectionIntact("変わった文", "元の文"))
        assertFalse(VoiceEditPolicy.isSelectionIntact(null, "元の文"))
        assertFalse(VoiceEditPolicy.isSelectionIntact("", "元の文"))
    }

    @Test
    fun `edit instruction carries the utterance and the no-invention guard`() {
        val instruction = VoiceEditPolicy.buildEditInstruction("もっと丁寧に")
        assertTrue(instruction.contains("もっと丁寧に"))
        assertTrue(instruction.contains("そのまま返す"))
        assertTrue(instruction.contains("編集後のテキストのみ"))
    }
}
