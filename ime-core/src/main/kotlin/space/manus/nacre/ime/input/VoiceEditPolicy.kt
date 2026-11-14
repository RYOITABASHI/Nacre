package space.manus.nacre.ime.input

/**
 * Speak to Edit decisions (Typeless parity): when a voice session starts
 * with a text selection in the editor, the utterance is an edit
 * instruction for that selection instead of dictation.
 *
 * Pure Kotlin so the entry/apply guards are JVM-testable. The safety
 * contract enforced by these helpers at the call sites:
 *  - edit mode requires a reliably read, non-empty selection at start;
 *  - the selection is replaced ONLY with a verified LLM result, and ONLY
 *    while the editor's current selection still equals the original —
 *    on any failure the original text stays untouched.
 */
object VoiceEditPolicy {

    /**
     * Enter edit mode only when the selection was actually readable and
     * non-empty. [selectionReadable] is false when there is no input
     * connection or reading the selection threw — those sessions fall
     * back to normal dictation.
     */
    fun shouldEnterEditMode(selectedText: String?, selectionReadable: Boolean): Boolean =
        selectionReadable && !selectedText.isNullOrEmpty()

    /**
     * The refinement instruction for an edit request. The original text is
     * passed as the refiner's input text; this instruction carries the
     * spoken command. If the instruction is unintelligible, the model is
     * told to return the original unchanged rather than invent an edit.
     */
    fun buildEditInstruction(utterance: String): String =
        "あなたは音声指示によるテキスト編集アシスタントです。入力されたテキストを、" +
            "次の音声指示に従って編集し、編集後のテキストのみを出力してください。" +
            "説明・前置き・引用符・原文の繰り返しは付けない。" +
            "音声指示が聞き取り違いなどで意味をなさない場合は、元のテキストをそのまま返す。" +
            "音声指示:「${utterance.trim()}」"

    /**
     * Apply-time guard: the editor's current selection must still be
     * exactly the text captured at session start. If the user (or the
     * app) changed the selection while recording, the edit is aborted
     * and the original is kept.
     */
    fun isSelectionIntact(currentSelectedText: String?, originalText: String): Boolean =
        currentSelectedText != null && currentSelectedText == originalText
}
