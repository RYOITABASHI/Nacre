package space.manus.nacre.ime.input

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * LLM-based candidate reranking for Japanese conversion.
 *
 * Connects to a local LLM server (Ollama or llama-server) running on the device
 * to rerank Viterbi conversion candidates based on context. This dramatically
 * improves conversion accuracy by leveraging the LLM's language understanding.
 *
 * The reranking runs asynchronously after initial candidates are shown,
 * so it never blocks typing. When the LLM responds, candidates are reordered
 * in-place and the UI updates automatically (Compose observes the list).
 *
 * Settings stored in SharedPreferences "nacre_llm":
 *   - enabled: Boolean (default false)
 *   - base_url: String (default "http://127.0.0.1:11434")
 *   - model: String (default "gemma3:1b")
 */
class LlmReranker(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs: SharedPreferences =
        context.getSharedPreferences("nacre_llm", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    var baseUrl: String
        get() = prefs.getString("base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString("base_url", value).apply()

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString("model", value).apply()

    @Volatile
    private var lastRerankJob: Job? = null

    @Volatile
    var serverAvailable: Boolean = false
        private set

    /**
     * SECURITY: Validate that a URL points to localhost only.
     *
     * IME apps have access to every keystroke the user types. To prevent
     * exfiltration of keystrokes to arbitrary external servers (whether by
     * misconfiguration or a malicious SharedPreferences write), we restrict
     * the LLM reranker to only connect to loopback addresses.
     */
    private fun isLocalhostUrl(url: String): Boolean {
        return try {
            val host = URI(url).host?.lowercase() ?: return false
            host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]"
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Check if the LLM server is reachable. Called once on IME startup.
     * Auto-probes multiple common ports if the configured URL fails.
     */
    fun checkServer() {
        scope.launch {
            // SECURITY: refuse to connect to non-localhost URLs
            if (!isLocalhostUrl(baseUrl)) {
                Log.w(TAG, "LLM server URL rejected: not localhost (url=$baseUrl)")
                serverAvailable = false
                return@launch
            }

            // Try configured URL first, then probe common ports
            val urlsToTry = listOf(
                baseUrl,
                "http://127.0.0.1:8080",
                "http://127.0.0.1:11434",
            ).distinct()

            for (url in urlsToTry) {
                if (!isLocalhostUrl(url)) continue
                val available = try {
                    val apiType = detectApiType(url)
                    val checkUrl = if (apiType == "ollama") "$url/api/tags" else "$url/health"
                    val conn = URL(checkUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 2000
                    conn.readTimeout = 2000
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    conn.disconnect()
                    code == 200
                } catch (_: Exception) {
                    false
                }
                if (available) {
                    serverAvailable = true
                    if (url != baseUrl) {
                        baseUrl = url
                        Log.i(TAG, "LLM server auto-detected at $url")
                    }
                    // Auto-detect model name from server
                    autoDetectModel(url)
                    Log.i(TAG, "LLM server check: available=true, url=$url, model=$model")
                    return@launch
                }
            }
            serverAvailable = false
            Log.i(TAG, "LLM server check: available=false (tried ${urlsToTry.size} URLs)")
        }
    }

    /**
     * Rerank candidates asynchronously. The callback is invoked on Main thread
     * with the reordered candidate list. If the server is unavailable or
     * reranking is disabled, the callback is never called.
     *
     * @param kana The reading (hiragana) being converted
     * @param candidates Current candidate list from Viterbi
     * @param precedingText Text before the cursor (context for the LLM)
     * @param onReranked Callback with reordered candidates
     */
    fun rerankAsync(
        kana: String,
        candidates: List<ConversionCandidate>,
        precedingText: String,
        onReranked: (List<ConversionCandidate>) -> Unit,
    ) {
        if (!enabled || !serverAvailable) return
        if (candidates.size <= 1) return
        // SECURITY: double-check localhost restriction before every request
        if (!isLocalhostUrl(baseUrl)) return

        // Cancel previous rerank if still running
        lastRerankJob?.cancel()

        lastRerankJob = scope.launch {
            try {
                val reranked = rerank(kana, candidates, precedingText)
                if (reranked != null && isActive) {
                    withContext(Dispatchers.Main) {
                        onReranked(reranked)
                    }
                }
            } catch (e: CancellationException) {
                // Normal cancellation
            } catch (e: Exception) {
                Log.w(TAG, "Rerank failed: ${e.message}")
            }
        }
    }

    /**
     * Perform the actual reranking via LLM.
     * Uses a minimal prompt to select the best candidate from a numbered list.
     */
    private suspend fun rerank(
        kana: String,
        candidates: List<ConversionCandidate>,
        precedingText: String,
    ): List<ConversionCandidate>? {
        // Take top candidates to rerank (limit to avoid slow inference)
        val toRerank = candidates.take(MAX_RERANK_CANDIDATES)

        val candidateList = toRerank.mapIndexed { i, c ->
            "${i + 1}. ${c.surface}"
        }.joinToString("\n")

        val contextSnippet = precedingText.takeLast(80)

        val prompt = buildString {
            append("文脈: 「${contextSnippet}」\n")
            append("読み: $kana\n")
            append("候補:\n$candidateList\n\n")
            append("最も自然で自然な日本語になる候補を1つだけ選んでください。\n")
            append("固有名詞、技術用語、コード用語は不自然に一般語へ寄せず、文脈を優先してください。\n")
            append("出力は番号だけ。")
        }

        val responseText = callLlm(prompt) ?: return null

        // Parse the response: extract the number
        val number = responseText.trim().filter { it.isDigit() }.toIntOrNull()
        if (number == null || number < 1 || number > toRerank.size) return null

        val bestIdx = number - 1
        if (bestIdx == 0) return null // Already first, no change needed

        // Move the selected candidate to the front
        val result = candidates.toMutableList()
        val selected = result.removeAt(bestIdx)
        result.add(0, selected)
        return result
    }

    private suspend fun callLlm(prompt: String): String? = withContext(Dispatchers.IO) {
        try {
            val apiType = detectApiType(baseUrl)
            val (url, body) = if (apiType == "ollama") {
                val reqBody = JSONObject().apply {
                    put("model", model)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    })
                    put("stream", false)
                    put("options", JSONObject().apply {
                        put("temperature", 0.1)
                        put("num_predict", 8)
                    })
                }
                "$baseUrl/api/chat" to reqBody.toString()
            } else {
                val reqBody = JSONObject().apply {
                    put("model", model)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    })
                    put("stream", false)
                    put("temperature", 0.1)
                    put("max_tokens", 8)
                }
                "$baseUrl/v1/chat/completions" to reqBody.toString()
            }

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = LLM_TIMEOUT_MS
            conn.readTimeout = LLM_TIMEOUT_MS
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

            if (conn.responseCode != 200) {
                conn.disconnect()
                return@withContext null
            }

            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val json = JSONObject(response)
            if (apiType == "ollama") {
                json.optJSONObject("message")?.optString("content")
            } else {
                json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
            }
        } catch (e: Exception) {
            Log.w(TAG, "LLM call failed: ${e.message}")
            null
        }
    }

    /**
     * Auto-detect model name from the server's /v1/models endpoint.
     */
    private fun autoDetectModel(url: String) {
        try {
            val apiType = detectApiType(url)
            val modelsUrl = if (apiType == "ollama") "$url/api/tags" else "$url/v1/models"
            val conn = URL(modelsUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "GET"
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val modelName = if (apiType == "ollama") {
                    json.optJSONArray("models")?.optJSONObject(0)?.optString("name")
                } else {
                    json.optJSONArray("data")?.optJSONObject(0)?.optString("id")
                        ?: json.optJSONArray("models")?.optJSONObject(0)?.optString("name")
                }
                if (!modelName.isNullOrBlank()) {
                    model = modelName
                    Log.i(TAG, "Auto-detected model: $modelName")
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Model auto-detect failed: ${e.message}")
        }
    }

    private fun detectApiType(url: String): String {
        return if (url.contains(":8080")) "openai"
        else if (url.contains(":11434")) "ollama"
        else "openai"
    }

    fun cancel() {
        lastRerankJob?.cancel()
    }

    fun destroy() {
        scope.cancel()
    }

    companion object {
        private const val TAG = "LlmReranker"
        private const val DEFAULT_BASE_URL = "http://127.0.0.1:11434"
        private const val DEFAULT_MODEL = "gemma3:1b"
        private const val LLM_TIMEOUT_MS = 3000 // 3 second max — typing can't wait longer
        private const val MAX_RERANK_CANDIDATES = 8
    }
}
