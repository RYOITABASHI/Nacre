package space.manus.nacre.ai

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.*

/**
 * LLM text transformation service.
 *
 * Runs in a separate process (android:process=":llm") for JNI crash isolation.
 * Uses llama.cpp via JNI for local Gemma/Qwen inference.
 *
 * Falls back to rule-based transformation if native library/model is unavailable.
 *
 * Supported transformations:
 * - "英語にして" / "english" → English translation
 * - "敬語にして" / "keigo" → polite Japanese
 * - "カタカナにして" / "katakana" → to katakana
 * - "ひらがなにして" / "hiragana" → to hiragana
 * - "要約して" / "summarize" → summarize text
 * - "コードにして" / "code" → convert to code
 * - "大文字にして" / "uppercase" → UPPERCASE
 * - "小文字にして" / "lowercase" → lowercase
 * - "全角にして" / "fullwidth" → full-width
 * - "半角にして" / "halfwidth" → half-width
 * - "逆にして" / "reverse" → reverse text
 * - "タイトル" / "titlecase" → Title Case
 */
class LlmService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var isGenerating = false

    @Volatile
    private var promptFormat: PromptFormat = PromptFormat.GEMMA

    // NOTE: The former 30-second idle-auto-unload was removed because voice
    // post-processing binds the service, loads the model, then waits for the
    // user to speak. The idle window between load and the first transform()
    // call is frequently >30s, so the timer was unloading the model before
    // we could use it. Memory pressure still triggers unload via
    // onTrimMemory; that's the right signal to free the ~1GB mmap.

    private val binder = object : ILlmService.Stub() {

        override fun isModelLoaded(): Boolean {
            return LlamaJni.isAvailable() && LlamaJni.isModelLoaded()
        }

        override fun isGenerating(): Boolean {
            return this@LlmService.isGenerating
        }

        override fun loadModel(modelPath: String) {
            scope.launch {
                Log.i(TAG, "Loading LLM model: $modelPath")
                val ok = LlamaJni.loadModel(modelPath)
                if (ok) {
                    promptFormat = PromptFormat.fromPath(modelPath)
                }
                Log.i(TAG, "LLM model loaded: $ok")
            }
        }

        override fun unloadModel() {
            LlamaJni.unloadModel()
            Log.i(TAG, "LLM model unloaded")
        }

        override fun transform(text: String, instruction: String, callback: ILlmCallback?) {
            if (this@LlmService.isGenerating) {
                try { callback?.onError("Already generating") } catch (_: RemoteException) {}
                return
            }

            this@LlmService.isGenerating = true

            scope.launch {
                try {
                    val result = if (LlamaJni.isModelLoaded()) {
                        // Use llama.cpp for full LLM inference
                        llmTransform(text, instruction)
                    } else {
                        // Fallback to rule-based transformation
                        ruleBasedTransform(text, instruction)
                    }
                    withContext(Dispatchers.Main) {
                        try { callback?.onResult(result) } catch (_: RemoteException) {}
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Transform error", e)
                    withContext(Dispatchers.Main) {
                        try { callback?.onError(e.message ?: "Transform failed") } catch (_: RemoteException) {}
                    }
                } finally {
                    this@LlmService.isGenerating = false
                }
            }
        }

        override fun cancelGeneration() {
            LlamaJni.cancelGeneration()
            this@LlmService.isGenerating = false
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "LlmService created (process=${android.os.Process.myPid()})")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW && !isGenerating) {
            Log.w(TAG, "onTrimMemory($level) — force unloading model")
            LlamaJni.unloadModel()
        }
    }

    /**
     * Full LLM-based transformation using llama.cpp.
     */
    private fun llmTransform(text: String, instruction: String): String {
        val prompt = buildPrompt(text, instruction)
        // Log.d removed: never log LLM prompts (contain user text)
        val result = LlamaJni.generate(prompt, MAX_TOKENS)
        return result.trim()
    }

    private fun buildPrompt(text: String, instruction: String): String {
        // Keep prompt templates model-specific. Gemma chat models do not use
        // ChatML; using Qwen's markers there materially hurts generation.
        return when (promptFormat) {
            PromptFormat.GEMMA ->
                "<start_of_turn>user\n$instruction\n\n$text<end_of_turn>\n" +
                    "<start_of_turn>model\n"
            PromptFormat.QWEN ->
                "<|im_start|>system\n$instruction<|im_end|>\n" +
                    "<|im_start|>user\n$text<|im_end|>\n" +
                    "<|im_start|>assistant\n"
        }
    }

    // --- Rule-based fallback (works without model) ---

    private fun ruleBasedTransform(text: String, instruction: String): String {
        val cmd = instruction.lowercase().trim()
        return when {
            cmd.contains("敬語") || cmd == "keigo" -> toKeigo(text)
            cmd.contains("カタカナ") || cmd.contains("かたかな") || cmd == "katakana" ->
                hiraganaToKatakana(text)
            cmd.contains("ひらがな") || cmd == "hiragana" ->
                katakanaToHiragana(text)
            cmd.contains("大文字") || cmd == "uppercase" -> text.uppercase()
            cmd.contains("小文字") || cmd == "lowercase" -> text.lowercase()
            cmd.contains("全角") || cmd == "fullwidth" -> toFullWidth(text)
            cmd.contains("半角") || cmd == "halfwidth" -> toHalfWidth(text)
            cmd.contains("逆") || cmd == "reverse" -> text.reversed()
            cmd.contains("タイトル") || cmd == "titlecase" ->
                text.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            // LLM-only features — return original with note when model unavailable
            cmd.contains("英語") || cmd == "english" ||
            cmd.contains("韓国語") || cmd == "korean" ||
            cmd.contains("要約") || cmd == "summarize" ||
            cmd.contains("コード") || cmd == "code" ->
                text // Cannot do without LLM
            else -> text
        }
    }

    private fun toKeigo(text: String): String {
        var result = text
        val conversions = listOf(
            "だ。" to "です。", "だよ" to "ですよ", "だね" to "ですね",
            "だった" to "でした", "じゃない" to "ではありません",
            "じゃなかった" to "ではありませんでした",
            "する" to "いたします", "した" to "いたしました",
            "できる" to "できます", "できない" to "できません",
            "ある" to "あります", "ない" to "ありません",
            "いる" to "おります", "いた" to "おりました",
            "言う" to "申します", "言った" to "申しました",
            "見る" to "拝見します", "見た" to "拝見しました",
            "行く" to "参ります", "行った" to "参りました",
            "来る" to "いらっしゃいます", "来た" to "いらっしゃいました",
            "食べる" to "いただきます", "食べた" to "いただきました",
            "もらう" to "いただきます", "もらった" to "いただきました",
            "知る" to "存じます", "知った" to "存じました", "知らない" to "存じません",
            "わかる" to "わかります", "わかった" to "わかりました",
            "思う" to "存じます", "思った" to "存じました",
            "聞く" to "伺います", "聞いた" to "伺いました",
            "会う" to "お会いします", "会った" to "お会いしました",
            "待つ" to "お待ちします", "待った" to "お待ちしました",
            "読む" to "拝読します", "読んだ" to "拝読しました",
            "書く" to "お書きします", "書いた" to "お書きしました",
            "送る" to "お送りします", "送った" to "お送りしました",
            "よ。" to "。", "ね。" to "。", "よね。" to "。",
            "んだ" to "のです", "けど" to "ですが",
        )
        for ((from, to) in conversions.sortedByDescending { it.first.length }) {
            result = result.replace(from, to)
        }
        return result
    }

    private fun hiraganaToKatakana(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (ch in '\u3041'..'\u3096') sb.append((ch.code + 0x60).toChar())
            else sb.append(ch)
        }
        return sb.toString()
    }

    private fun katakanaToHiragana(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (ch in '\u30A1'..'\u30F6') sb.append((ch.code - 0x60).toChar())
            else sb.append(ch)
        }
        return sb.toString()
    }

    private fun toFullWidth(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch == ' ' -> sb.append('\u3000')
                ch.code in 0x21..0x7E -> sb.append((ch.code + 0xFEE0).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun toHalfWidth(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch == '\u3000' -> sb.append(' ')
                ch.code in 0xFF01..0xFF5E -> sb.append((ch.code - 0xFEE0).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    override fun onDestroy() {
        scope.cancel()
        if (LlamaJni.isModelLoaded()) {
            LlamaJni.unloadModel()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LlmService"
        private const val MAX_TOKENS = 512

        /** List of supported transformation commands for UI display */
        val SUPPORTED_COMMANDS = listOf(
            "敬語にして" to "Polite Japanese (keigo)",
            "カタカナにして" to "To katakana",
            "ひらがなにして" to "To hiragana",
            "大文字にして" to "UPPERCASE",
            "小文字にして" to "lowercase",
            "全角にして" to "Full-width",
            "半角にして" to "Half-width",
            "逆にして" to "Reverse text",
            "英語にして" to "Translate to English (LLM)",
            "韓国語にして" to "Translate to Korean (LLM)",
            "要約して" to "Summarize (LLM)",
            "コードにして" to "Convert to code (LLM)",
        )
    }

    private enum class PromptFormat {
        GEMMA,
        QWEN;

        companion object {
            fun fromPath(path: String): PromptFormat {
                val lower = path.lowercase()
                return if (lower.contains("qwen")) QWEN else GEMMA
            }
        }
    }
}
