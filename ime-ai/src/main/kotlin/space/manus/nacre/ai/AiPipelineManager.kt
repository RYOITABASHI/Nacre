package space.manus.nacre.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.BatteryManager
import android.os.IBinder
import android.os.RemoteException
import android.util.Log

/**
 * AI Pipeline Manager.
 *
 * Orchestrates the voice input → LLM transformation pipeline.
 * Uses AIDL IPC to communicate with WhisperService and LlmService
 * running in separate processes (:whisper / :llm) for JNI crash isolation.
 *
 * Workflow:
 * 1. User triggers voice input (trackball up-swipe)
 * 2. WhisperService transcribes speech to text
 * 3. Text inserted immediately (or optionally sent to LLM)
 * 4. User can request transformation: "敬語にして", "カタカナにして"
 * 5. LlmService transforms text
 * 6. Result replaces the transcribed text
 */
class AiPipelineManager(private val context: Context) {

    private var whisperService: IWhisperService? = null
    private var llmService: ILlmService? = null
    private var whisperBound = false
    private var llmBound = false

    var isAvailable = false
        private set

    var onTranscriptionResult: ((String) -> Unit)? = null
    var onTransformResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStatusChanged: ((AiStatus) -> Unit)? = null

    enum class AiStatus {
        Idle,
        Listening,
        Transcribing,
        Transforming,
        Error,
    }

    var status: AiStatus = AiStatus.Idle
        private set(value) {
            field = value
            onStatusChanged?.invoke(value)
        }

    private val whisperConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            whisperService = IWhisperService.Stub.asInterface(service)
            whisperBound = true
            checkAvailability()
            Log.d(TAG, "WhisperService connected (AIDL)")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            whisperService = null
            whisperBound = false
            checkAvailability()
        }
    }

    private val llmConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            llmService = ILlmService.Stub.asInterface(service)
            llmBound = true
            checkAvailability()
            Log.d(TAG, "LlmService connected (AIDL)")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            llmService = null
            llmBound = false
            checkAvailability()
        }
    }

    fun bindServices() {
        try {
            context.bindService(
                Intent(context, WhisperService::class.java),
                whisperConnection,
                Context.BIND_AUTO_CREATE,
            )
            context.bindService(
                Intent(context, LlmService::class.java),
                llmConnection,
                Context.BIND_AUTO_CREATE,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind AI services", e)
        }
    }

    fun unbindServices() {
        try {
            if (whisperBound) {
                context.unbindService(whisperConnection)
                whisperBound = false
            }
            if (llmBound) {
                context.unbindService(llmConnection)
                llmBound = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding AI services", e)
        }
        whisperService = null
        llmService = null
        isAvailable = false
    }

    /**
     * Load Whisper model for speech recognition.
     */
    fun loadWhisperModel(modelPath: String) {
        try {
            whisperService?.loadModel(modelPath)
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to load Whisper model", e)
        }
    }

    /**
     * Load LLM model for text transformation.
     */
    fun loadLlmModel(modelPath: String) {
        try {
            llmService?.loadModel(modelPath)
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to load LLM model", e)
        }
    }

    /**
     * Unload models to free memory.
     */
    fun unloadModels() {
        try {
            whisperService?.unloadModel()
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to unload Whisper model", e)
        }
        try {
            llmService?.unloadModel()
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to unload LLM model", e)
        }
    }

    /**
     * Start voice input → transcription.
     */
    fun startVoiceInput(language: String = "ja-JP") {
        val whisper = whisperService
        if (whisper == null) {
            onError?.invoke("Speech recognition not available")
            return
        }

        try {
            if (whisper.isRecognizing) {
                onError?.invoke("Already recognizing")
                return
            }
        } catch (e: RemoteException) {
            onError?.invoke("Service communication error")
            return
        }

        if (!isBatteryOk()) {
            onError?.invoke("Battery too low")
            return
        }

        status = AiStatus.Listening

        try {
            whisper.startRecognition(language, object : IWhisperCallback.Stub() {
                override fun onResult(text: String) {
                    status = AiStatus.Idle
                    onTranscriptionResult?.invoke(text)
                }

                override fun onPartialResult(text: String, isStable: Boolean) {
                    status = AiStatus.Transcribing
                }

                override fun onError(message: String) {
                    status = AiStatus.Error
                    this@AiPipelineManager.onError?.invoke(message)
                }
            })
        } catch (e: RemoteException) {
            status = AiStatus.Error
            onError?.invoke("Failed to start recognition")
        }
    }

    fun stopVoiceInput() {
        try {
            whisperService?.stopRecognition()
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to stop recognition", e)
        }
        status = AiStatus.Idle
    }

    /**
     * Transform text using LlmService (LLM or rule-based fallback).
     *
     * @param text Text to transform
     * @param instruction e.g., "敬語にして", "カタカナにして", "英語にして"
     */
    fun transformText(text: String, instruction: String) {
        val llm = llmService
        if (llm == null) {
            onError?.invoke("LLM service not connected")
            return
        }

        try {
            if (llm.isGenerating) {
                onError?.invoke("Already generating")
                return
            }
        } catch (e: RemoteException) {
            onError?.invoke("Service communication error")
            return
        }

        if (!isBatteryOk()) {
            onError?.invoke("Battery too low")
            return
        }

        status = AiStatus.Transforming

        try {
            llm.transform(text, instruction, object : ILlmCallback.Stub() {
                override fun onResult(text: String) {
                    status = AiStatus.Idle
                    onTransformResult?.invoke(text)
                }

                override fun onPartialResult(text: String) {
                    // Streaming partial results (future use)
                }

                override fun onError(message: String) {
                    status = AiStatus.Error
                    this@AiPipelineManager.onError?.invoke(message)
                }
            })
        } catch (e: RemoteException) {
            status = AiStatus.Error
            onError?.invoke("Failed to start transformation")
        }
    }

    /**
     * Cancel an in-progress LLM generation.
     */
    fun cancelTransformation() {
        try {
            llmService?.cancelGeneration()
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to cancel generation", e)
        }
        status = AiStatus.Idle
    }

    /**
     * Check if Whisper model is loaded.
     */
    fun isWhisperModelLoaded(): Boolean {
        return try {
            whisperService?.isModelLoaded ?: false
        } catch (e: RemoteException) {
            false
        }
    }

    /**
     * Check if LLM model is loaded.
     */
    fun isLlmModelLoaded(): Boolean {
        return try {
            llmService?.isModelLoaded ?: false
        } catch (e: RemoteException) {
            false
        }
    }

    private fun checkAvailability() {
        isAvailable = whisperBound || llmBound
    }

    private fun isBatteryOk(): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return true
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level > BATTERY_THRESHOLD
    }

    companion object {
        private const val TAG = "AiPipelineManager"
        private const val BATTERY_THRESHOLD = 20
    }
}
