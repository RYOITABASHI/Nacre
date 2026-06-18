package space.manus.nacre.ai.cloud

import android.content.Context
import android.util.Log
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sends a recorded utterance to an OpenAI-compatible /audio/transcriptions
 * endpoint and returns the transcript. Synchronous/blocking — call from a worker
 * thread. Returns null on any failure so the caller can fall back to the
 * on-device result.
 */
object CloudAsrClient {
    private const val TAG = "CloudAsr"
    private const val BOUNDARY = "NacreAsrBoundary7MA4YWxkTrZu0gW"

    fun transcribe(ctx: Context, samples: FloatArray, sampleRate: Int): String? {
        val key = CloudAsrConfig.apiKey(ctx)?.takeIf { it.isNotBlank() } ?: return null
        if (samples.isEmpty()) return null
        var conn: HttpURLConnection? = null
        return try {
            val wav = encodeWav(samples, sampleRate)
            val endpoint = CloudAsrConfig.baseUrl(ctx).trimEnd('/') + "/audio/transcriptions"
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 45_000
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
            }
            DataOutputStream(conn.outputStream).use { out ->
                fun field(name: String, value: String) {
                    out.writeBytes("--$BOUNDARY\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    out.writeBytes("$value\r\n")
                }
                field("model", CloudAsrConfig.model(ctx))
                field("language", "ja")
                field("response_format", "text")
                out.writeBytes("--$BOUNDARY\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n")
                out.writeBytes("Content-Type: audio/wav\r\n\r\n")
                out.write(wav)
                out.writeBytes("\r\n--$BOUNDARY--\r\n")
                out.flush()
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200)
                Log.w(TAG, "cloud ASR HTTP $code: $err")
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            // response_format=text → plain text; tolerate gateways that wrap as {"text": "..."}
            val text = if (body.startsWith("{")) {
                try { org.json.JSONObject(body).optString("text", "").trim() } catch (_: Exception) { body }
            } else {
                body
            }
            text.ifBlank { null }
        } catch (e: Exception) {
            Log.w(TAG, "cloud ASR failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Encode mono float samples (-1..1) as a 16-bit PCM WAV byte array. */
    private fun encodeWav(samples: FloatArray, sampleRate: Int): ByteArray {
        val dataSize = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)                 // PCM fmt chunk size
        buf.putShort(1)                // audio format = PCM
        buf.putShort(1)                // channels = mono
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2)     // byte rate (mono, 16-bit)
        buf.putShort(2)                // block align
        buf.putShort(16)               // bits per sample
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        for (s in samples) {
            val v = (s.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            buf.putShort(v)
        }
        return buf.array()
    }
}
