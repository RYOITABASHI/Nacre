package space.manus.nacre.ai

import java.io.File

/**
 * Classifies an on-device ASR model directory by its file layout.
 *
 * Pure JVM (java.io only) so the classification is unit-testable and shared
 * by [SherpaRecognizer] (which builds the sherpa-onnx config),
 * [ModelDownloader] (which resolves the preferred model directory), and the
 * Settings UI (which labels the active engine).
 *
 * Detection order matters:
 *  1. TRANSDUCER — encoder/decoder/joiner *.onnx + tokens.txt
 *     (ReazonSpeech ja Zipformer).
 *  2. Single model file (model.int8.onnx preferred, else model.onnx) +
 *     tokens.txt. Two model families share this shape, told apart by the
 *     tokens file:
 *       - SenseVoice vocabularies always carry `<|...|>` control tokens
 *         (language / emotion / event tags such as `<|ja|>`, `<|nospeech|>`).
 *       - The NeMo CTC package (Parakeet-tdt_ctc-0.6b-ja) has none — its
 *         SentencePiece vocabulary is plain text pieces and punctuation.
 *     A single-file directory WITHOUT the SenseVoice control tokens is
 *     therefore classified NEMO_CTC. Before this branch existed, a Parakeet
 *     directory was silently misdetected as SenseVoice and fed to the
 *     SenseVoice config (wrong language/ITN settings for a CTC model).
 *  3. NONE — anything else (missing/empty model file, missing tokens).
 */
object AsrModelDetector {

    enum class Kind { TRANSDUCER, NEMO_CTC, SENSEVOICE, NONE }

    /** Human-facing engine name for status displays; null for [Kind.NONE]. */
    fun engineDisplayName(kind: Kind): String? = when (kind) {
        Kind.TRANSDUCER -> "ReazonSpeech"
        Kind.NEMO_CTC -> "Parakeet"
        Kind.SENSEVOICE -> "SenseVoice"
        Kind.NONE -> null
    }

    fun detect(dir: File): Kind {
        if (!dir.isDirectory) return Kind.NONE
        if (!File(dir, "tokens.txt").exists()) return Kind.NONE

        if (pickTransducerPart(dir, "encoder") != null &&
            pickTransducerPart(dir, "decoder") != null &&
            pickTransducerPart(dir, "joiner") != null
        ) {
            return Kind.TRANSDUCER
        }

        singleModelFile(dir) ?: return Kind.NONE
        return if (tokensHaveSenseVoiceControlTokens(dir)) Kind.SENSEVOICE else Kind.NEMO_CTC
    }

    /** The single-file model of a SenseVoice / NeMo CTC dir (int8 preferred). */
    fun singleModelFile(dir: File): File? =
        listOf("model.int8.onnx", "model.onnx")
            .map { File(dir, it) }
            .firstOrNull { it.exists() && it.length() > 0 }

    private fun pickTransducerPart(dir: File, prefix: String): File? =
        dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".onnx") && f.length() > 0 }
            ?.sortedByDescending { it.name.contains("int8") }
            ?.firstOrNull()

    /**
     * SenseVoice's tokens.txt embeds `<|...|>` control tokens (they sit near
     * the end of the ~25k-line file, so the whole file is scanned; at
     * ~700KB this is still cheap). Any read failure is treated as "no
     * signature found" — the caller then takes the NeMo branch, whose load
     * either succeeds or surfaces a recorded load error (never silent).
     */
    private fun tokensHaveSenseVoiceControlTokens(dir: File): Boolean {
        val tokens = File(dir, "tokens.txt")
        if (!tokens.exists() || tokens.length() == 0L) return false
        return try {
            tokens.bufferedReader().useLines { lines ->
                lines.any { line ->
                    val token = line.substringBefore(' ')
                    token.startsWith("<|") && token.endsWith("|>")
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
