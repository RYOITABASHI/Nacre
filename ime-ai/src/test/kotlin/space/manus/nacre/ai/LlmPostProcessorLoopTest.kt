package space.manus.nacre.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repetition-loop detection used as the Qwen3 safety net in
 * LlmService.llmTransform (greedy-only JNI, no presence penalty).
 */
class LlmPostProcessorLoopTest {

    @Test
    fun `detects the observed Qwen3 loop`() {
        // Shape of the actual desktop-smoke failure (qwen-14): one meta
        // sentence repeated until MAX_TOKENS.
        val looped = "明日中に届くように送りたいという旨を伝えると、以下のように整形されます。\n\n" +
            "明日中に届くように送りたいという旨を伝えると、以下のように整形されます。\n\n" +
            "明日中に届くように送りたいという旨を伝えると、以下のように整形されます。\n\n" +
            "明日中に届くように送りたいという旨を伝えると、以下のように整形されま"
        assertTrue(LlmPostProcessor.hasRepetitionLoop(looped))
    }

    @Test
    fun `normal refinements are not loops`() {
        assertFalse(
            LlmPostProcessor.hasRepetitionLoop("明後日の会議の資料を準備しておいて。"),
        )
        assertFalse(
            LlmPostProcessor.hasRepetitionLoop(
                "今日の午後四時半から打ち合わせが入っているので、それまでに見積もりをメールで送ってください。",
            ),
        )
        assertFalse(
            LlmPostProcessor.hasRepetitionLoop(
                "会議の議事録なんですけど、決定事項は二つあって、一つ目が予算の承認、二つ目がスケジュールの見直しです。",
            ),
        )
    }

    @Test
    fun `two repeats are not a loop yet`() {
        val twice = "これはテストです、間違いありません。これはテストです、間違いありません。"
        assertFalse(LlmPostProcessor.hasRepetitionLoop(twice))
    }

    @Test
    fun `short repeated fragments are ignored`() {
        // Short sentences (< 8 chars) repeat legitimately in lists etc.
        assertFalse(LlmPostProcessor.hasRepetitionLoop("はい。はい。はい。はい。"))
        assertFalse(LlmPostProcessor.hasRepetitionLoop(""))
        assertFalse(LlmPostProcessor.hasRepetitionLoop("短い文。"))
    }
}
