package space.manus.nacre.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Layout classification over fake model directories, mirroring the real
 * packages:
 *  - Parakeet: sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8
 *    (model.int8.onnx + SentencePiece tokens, NO `<|...|>` control tokens)
 *  - ReazonSpeech: sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01
 *    (encoder/decoder/joiner + tokens)
 *  - SenseVoice: sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17
 *    (model.int8.onnx + tokens WITH `<|ja|>` / `<|nospeech|>` control tokens)
 */
class AsrModelDetectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dirWith(name: String, files: Map<String, String>): File {
        val dir = tmp.newFolder(name)
        for ((fileName, content) in files) {
            File(dir, fileName).writeText(content)
        }
        return dir
    }

    // First lines of the real Parakeet tokens.txt (3073 lines total).
    private val parakeetTokens = "<unk> 0\n。 1\n▁ 2\nの 3\nが 4\n、 8\n"

    // SenseVoice control tokens live near the end of its ~25k-line file.
    private val senseVoiceTokens = "<unk> 0\n<s> 1\n</s> 2\n▁the 3\n<|ja|> 24892\n<|nospeech|> 24992\n"

    @Test
    fun `parakeet layout is detected as nemo ctc`() {
        val dir = dirWith(
            "parakeet",
            mapOf(
                "model.int8.onnx" to "fake-655mb-model",
                "tokens.txt" to parakeetTokens,
            ),
        )
        assertEquals(AsrModelDetector.Kind.NEMO_CTC, AsrModelDetector.detect(dir))
        assertEquals("Parakeet", AsrModelDetector.engineDisplayName(AsrModelDetector.detect(dir)))
    }

    @Test
    fun `parakeet fp32 file name is also nemo ctc`() {
        val dir = dirWith(
            "parakeet-fp32",
            mapOf(
                "model.onnx" to "fake-model",
                "tokens.txt" to parakeetTokens,
            ),
        )
        assertEquals(AsrModelDetector.Kind.NEMO_CTC, AsrModelDetector.detect(dir))
    }

    @Test
    fun `reazonspeech layout is detected as transducer`() {
        val dir = dirWith(
            "reazon",
            mapOf(
                "encoder.int8.onnx" to "enc",
                "decoder.onnx" to "dec",
                "joiner.int8.onnx" to "joiner",
                "tokens.txt" to "<blk> 0\nあ 1\n",
            ),
        )
        assertEquals(AsrModelDetector.Kind.TRANSDUCER, AsrModelDetector.detect(dir))
        assertEquals("ReazonSpeech", AsrModelDetector.engineDisplayName(AsrModelDetector.detect(dir)))
    }

    @Test
    fun `sensevoice layout is detected as sensevoice`() {
        val dir = dirWith(
            "sensevoice",
            mapOf(
                "model.int8.onnx" to "fake-model",
                "tokens.txt" to senseVoiceTokens,
            ),
        )
        assertEquals(AsrModelDetector.Kind.SENSEVOICE, AsrModelDetector.detect(dir))
        assertEquals("SenseVoice", AsrModelDetector.engineDisplayName(AsrModelDetector.detect(dir)))
    }

    @Test
    fun `sensevoice fp32 layout is detected as sensevoice`() {
        val dir = dirWith(
            "sensevoice-fp32",
            mapOf(
                "model.onnx" to "fake-model",
                "tokens.txt" to senseVoiceTokens,
            ),
        )
        assertEquals(AsrModelDetector.Kind.SENSEVOICE, AsrModelDetector.detect(dir))
    }

    @Test
    fun `transducer wins when both shapes are present`() {
        // Detection order: the transducer branch is checked first, so a
        // directory that somehow holds both layouts stays on ReazonSpeech.
        val dir = dirWith(
            "both",
            mapOf(
                "encoder.int8.onnx" to "enc",
                "decoder.onnx" to "dec",
                "joiner.int8.onnx" to "joiner",
                "model.int8.onnx" to "stray",
                "tokens.txt" to parakeetTokens,
            ),
        )
        assertEquals(AsrModelDetector.Kind.TRANSDUCER, AsrModelDetector.detect(dir))
    }

    @Test
    fun `empty and incomplete dirs are none`() {
        val empty = tmp.newFolder("empty")
        assertEquals(AsrModelDetector.Kind.NONE, AsrModelDetector.detect(empty))

        val noTokens = dirWith("no-tokens", mapOf("model.int8.onnx" to "fake"))
        assertEquals(AsrModelDetector.Kind.NONE, AsrModelDetector.detect(noTokens))

        val zeroByteModel = tmp.newFolder("zero")
        File(zeroByteModel, "model.int8.onnx").writeText("")
        File(zeroByteModel, "tokens.txt").writeText(parakeetTokens)
        assertEquals(AsrModelDetector.Kind.NONE, AsrModelDetector.detect(zeroByteModel))

        assertEquals(
            AsrModelDetector.Kind.NONE,
            AsrModelDetector.detect(File(tmp.root, "does-not-exist")),
        )
        assertNull(AsrModelDetector.engineDisplayName(AsrModelDetector.Kind.NONE))
    }
}
