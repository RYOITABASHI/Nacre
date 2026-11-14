package space.manus.nacre.ime.input

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import space.manus.nacre.ai.ILlmCallback
import space.manus.nacre.ai.ILlmService
import space.manus.nacre.ai.IWhisperService
import space.manus.nacre.ai.IWhisperCallback
import space.manus.nacre.ai.KanaUtils
import space.manus.nacre.ai.LlmPostProcessor
import space.manus.nacre.ai.ZenzOutput
import space.manus.nacre.ai.ZenzStatus
import space.manus.nacre.ime.NacreInputMethodService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** zenz readiness as shown by the keyboard's candidate bar indicator. */
enum class ZenzUiState { OFF, NOT_DOWNLOADED, LOADING, READY, ERROR }

/** ASR source/health as shown in the voice panel (candidate bar / QuickInputPad). */
enum class VoiceAsrUiState { LOADING, ON_DEVICE, MODEL_MISSING, EXTERNAL, ERROR }

/** Refinement (整文) availability as shown in the voice panel. */
enum class VoiceRefineUiState { LOADING, READY_LOCAL, READY_CLOUD, UNAVAILABLE }

/** Speak to Edit session phase as shown in the voice panel. */
enum class VoiceEditUiState { IDLE, RECORDING, APPLYING, DONE, FAILED }

/**
 * Typeless-level voice input manager.
 *
 * Key differences from basic SpeechRecognizer usage:
 * - Zero-gap continuous recognition: next recognizer pre-created before current finishes
 * - Streaming partial commit: long partial results committed incrementally
 * - Intelligent utterance merging: avoids fragmented sentences
 * - All recoverable errors auto-restart with zero delay
 * - Offline-preferred with network fallback
 * - Smart punctuation with context awareness
 */
class VoiceInputManager(private val service: NacreInputMethodService) {

    var isListening by mutableStateOf(false)
        private set
    var partialText by mutableStateOf("")
        private set
    var lastError by mutableStateOf("")
        private set
    /** Normalized RMS level 0f..1f for visual feedback */
    var rmsLevel by mutableFloatStateOf(0f)
        private set

    private var speechRecognizer: SpeechRecognizer? = null
    private var nextRecognizer: SpeechRecognizer? = null // Pre-created for zero-gap restart
    private var continuousMode = false
    private var currentLanguage = "ja-JP"
    private var committedInSession = StringBuilder()
    private var utteranceCount = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    // Streaming partial commit tracking
    private var lastCommittedPartial = ""
    private var partialStableCount = 0
    private var lastPartialText = ""
    private var lastPartialTime = 0L

    // Consecutive error tracking for backoff
    private var consecutiveErrors = 0

    // Whisper continuous mode
    @Volatile private var whisperService: IWhisperService? = null
    @Volatile private var whisperBound = false
    @Volatile private var isWhisperContinuousMode = false
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    // LLM post-processing (local Qwen via isolated LlmService over AIDL)
    @Volatile private var llmService: ILlmService? = null
    @Volatile private var llmBound = false

    // zenz neural conversion (model lives in the same LlmService process, but
    // with its own handle). llmService above is only assigned when the Qwen
    // cleanup model loads, so zenz keeps its own service reference — it must
    // also work when a cloud refiner is configured and Qwen is never loaded.
    @Volatile private var zenzService: ILlmService? = null
    private val zenzLoadLock = Any()
    @Volatile private var zenzLoadState = ZENZ_LOAD_IDLE
    @Volatile private var zenzLoadFailedAt = 0L
    /** Single-flight guard: the :llm service serializes zenz generation and
     * errors any concurrent request; skipping early keeps the ZenzStatus log
     * (and the IPC) free of "already generating" noise. */
    private val zenzBusy = AtomicBoolean(false)

    /**
     * Compose-observable zenz readiness for the candidate-bar indicator, so a
     * dead/loading converter is diagnosable from the keyboard itself instead
     * of only in Settings. Load-level states only — a single generation
     * timeout does not flap this to ERROR (it is still recorded in ZenzStatus).
     */
    var zenzUiState by mutableStateOf(ZenzUiState.LOADING)
        private set

    // ── Voice engine state (no silent degradation) ─────────────────────
    // Surfaced live in the voice panel and recorded via VoiceStatus, the
    // same pattern zenz uses: a dead ASR model or a dead refiner must be
    // diagnosable from the keyboard, not discovered weeks later.

    var voiceAsrUiState by mutableStateOf(VoiceAsrUiState.LOADING)
        private set
    var voiceRefineUiState by mutableStateOf(VoiceRefineUiState.LOADING)
        private set

    fun asrStateLabel(): String = when (voiceAsrUiState) {
        VoiceAsrUiState.LOADING -> "モデル読込中"
        VoiceAsrUiState.ON_DEVICE -> "端末内認識"
        VoiceAsrUiState.MODEL_MISSING -> "ASRモデル未DL"
        VoiceAsrUiState.EXTERNAL -> "外部認識で代用中"
        VoiceAsrUiState.ERROR -> "認識エラー"
    }

    fun refineStateLabel(): String = when (voiceRefineUiState) {
        VoiceRefineUiState.LOADING -> "準備中"
        VoiceRefineUiState.READY_LOCAL -> "端末内"
        VoiceRefineUiState.READY_CLOUD -> "クラウド"
        VoiceRefineUiState.UNAVAILABLE -> "不可"
    }

    /** One-line engine summary for the voice panel (QuickInputPad). The
     *  session's resolved refinement preset is named here too, with a
     *  ・自動 suffix when the per-app mapping chose it, so an auto-chosen
     *  tone is visible rather than silently different. */
    val voiceStateSummary: String
        get() = buildString {
            append("認識: ${asrStateLabel()} / 整文: ${refineStateLabel()}")
            if (sessionPresetLabel.isNotEmpty()) {
                append("（")
                append(sessionPresetLabel)
                if (sessionPresetAuto) append("・自動")
                append("）")
            }
        }

    // ── Speak to Edit / per-app tone session state ─────────────────────

    /** True while this session edits the start-of-session selection
     *  (Speak to Edit) instead of dictating. */
    var isVoiceEditMode by mutableStateOf(false)
        private set
    var voiceEditUiState by mutableStateOf(VoiceEditUiState.IDLE)
        private set
    /** Preset label resolved for the current/last session (panel display). */
    var sessionPresetLabel by mutableStateOf("")
        private set
    /** True when the per-app category mapping chose the session preset. */
    var sessionPresetAuto by mutableStateOf(false)
        private set
    /**
     * Explicit per-session manual preset choice. When set before a
     * session starts it wins over both the standing manual preset and the
     * per-app auto mapping; it is consumed (cleared) by that session.
     */
    var sessionPresetOverrideId: String? = null

    private var editTargetText: String? = null
    private var sessionPresetInstruction: String? = null

    /** Status line for Speak to Edit, shown in the voice panel. */
    val voiceEditStatusLine: String?
        get() = when (voiceEditUiState) {
            VoiceEditUiState.RECORDING -> "✏️ 音声編集モード：選択範囲への指示を話してください"
            VoiceEditUiState.APPLYING -> "✏️ 音声編集を適用中…"
            VoiceEditUiState.DONE -> "✏️ 音声編集を適用しました"
            VoiceEditUiState.FAILED -> "✏️ 音声編集に失敗しました（元のテキストを保持）"
            VoiceEditUiState.IDLE -> null
        }

    // ── Whisper live-preview / incremental-commit session state ────────
    // Fed by VoiceSegmentFinalizer decisions on the main thread only.

    // lazy: postProcessor is declared further down in this class.
    private val whisperFinalizer by lazy { VoiceSegmentFinalizer(postProcessor) }
    @Volatile private var whisperSessionActive = false
    /** Cloud ASR (opt-in) replaces the whole transcript at stop, so those
     *  sessions preview only and never commit incrementally. */
    private var whisperCloudSession = false
    /** Editor-composing preview disabled when the user was mid-composition
     *  (kana conversion) when dictation started — the preview must not
     *  hijack an in-progress conversion. partialText still shows it. */
    private var whisperEditorPreviewEnabled = true
    private var whisperPreviewInEditor = false
    private var whisperPreviewDisplay = ""
    /** Committed chunks of the current voice session, in order. The
     *  refinement replace guard only ever touches the latest chunk. */
    private val voiceSessionChunks = mutableListOf<String>()
    /** Why the SpeechRecognizer fallback engaged (for the status record). */
    private var asrFallbackDetail = ""

    // Local LLM load retry: the ILlmService binder is kept even when the
    // model load failed, so the next voice session can retry instead of
    // refinement staying silently dead for the IME's whole lifetime.
    @Volatile private var llmServiceAny: ILlmService? = null
    private val llmLoadInFlight = AtomicBoolean(false)

    // Deterministic, instant (no LLM round-trip) post-processing: hallucination
    // filtering and self-correction ("じゃなくて" / "no wait") resolution. Runs
    // before quickClean/LLM refinement on every recognition path so disfluency
    // cleanup doesn't depend on a slow/unavailable LLM (Typeless does this
    // client-side too). Stateless — safe to share across utterances.
    private val postProcessor = space.manus.nacre.ai.PostProcessor()

    private val whisperConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
            val svc = IWhisperService.Stub.asInterface(binder)
            whisperBound = true
            writeDiagnostic("onServiceConnected: WhisperService bound")
            Thread {
                try {
                    if (!svc.isModelLoaded) {
                        voiceAsrUiState = VoiceAsrUiState.LOADING
                        val downloader = space.manus.nacre.ai.ModelDownloader(service)
                        // Prefer the ja-specialized ReazonSpeech transducer; fall back
                        // to SenseVoice until ReazonSpeech finishes downloading.
                        val modelDir = downloader.getPreferredAsrModelDir()
                        val vadPath = downloader.getVadModelPath()
                        writeDiagnostic("onServiceConnected: modelDir=$modelDir, vadPath=$vadPath")
                        if (modelDir != null && vadPath != null) {
                            svc.loadModel("$modelDir|$vadPath")
                            for (i in 0 until 120) {
                                Thread.sleep(500)
                                if (svc.isModelLoaded) break
                            }
                            if (!svc.isModelLoaded) {
                                writeDiagnostic("onServiceConnected: model load TIMEOUT after 60s")
                                voiceAsrUiState = VoiceAsrUiState.ERROR
                                space.manus.nacre.ai.VoiceStatus.recordAsr(
                                    service, space.manus.nacre.ai.VoiceStatus.AsrState.LOAD_ERROR,
                                    "ASRモデルの読み込みが60秒で完了しませんでした",
                                )
                            }
                        } else {
                            writeDiagnostic("onServiceConnected: model NOT FOUND (dir=$modelDir, vad=$vadPath)")
                            voiceAsrUiState = VoiceAsrUiState.MODEL_MISSING
                            space.manus.nacre.ai.VoiceStatus.recordAsr(
                                service, space.manus.nacre.ai.VoiceStatus.AsrState.NOT_DOWNLOADED,
                                "ASRモデル（ReazonSpeech/SenseVoice + VAD）が見つかりません",
                            )
                        }
                    } else {
                        writeDiagnostic("onServiceConnected: model already loaded")
                    }
                    if (svc.isModelLoaded) {
                        whisperService = svc
                        markAsrOnDevice()
                        writeDiagnostic("onServiceConnected: SUCCESS, whisperService set")
                    } else {
                        writeDiagnostic("onServiceConnected: FAIL, model not loaded, fallback to SpeechRecognizer")
                    }
                } catch (e: Exception) {
                    voiceAsrUiState = VoiceAsrUiState.ERROR
                    space.manus.nacre.ai.VoiceStatus.recordAsr(
                        service, space.manus.nacre.ai.VoiceStatus.AsrState.LOAD_ERROR,
                        "ASRモデル読み込み例外: ${e.message}",
                    )
                    writeDiagnostic("onServiceConnected EXCEPTION: ${e.message}")
                }
            }.start()
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            Log.w(TAG, "WhisperService disconnected unexpectedly")
            whisperService = null
            whisperBound = false
            if (isWhisperContinuousMode) {
                isWhisperContinuousMode = false
                whisperSessionActive = false
                whisperFinalizer.reset()
                whisperPreviewInEditor = false
                whisperPreviewDisplay = ""
                asrFallbackDetail = "Whisperサービスが切断されたため、端末標準の音声認識で代用"
                // Clear composing text left from the dead Whisper session
                mainHandler.post {
                    service.currentInputConnection?.finishComposingText()
                    partialText = ""
                    rmsLevel = 0f
                    releaseAudioFocus()
                    // Fallback to SpeechRecognizer if still listening
                    if (isListening) {
                        Log.i(TAG, "Falling back to SpeechRecognizer after Whisper disconnect")
                        startRecognizer()
                    }
                }
            }
        }
    }

    private val llmConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
            val svc = ILlmService.Stub.asInterface(binder)
            llmBound = true
            zenzService = svc
            writeDiagnostic("llmConnection.onServiceConnected")
            // zenz warm-up: independent of the Qwen/cloud logic below (whose
            // early returns must not skip it). Lazy-loads the 74MB converter
            // in the background so the first conversion after startup can
            // already use it; if it is not ready yet, conversions simply
            // fall back to engine candidates until it is.
            ensureZenzLoaded()
            // Keep the binder even if the model load below fails, so the
            // next voice session can retry via maybeRetryLocalLlmLoad().
            llmServiceAny = svc
            Thread { attemptLocalLlmLoad(svc) }.start()
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            Log.w(TAG, "LlmService disconnected")
            llmService = null
            llmServiceAny = null
            llmBound = false
            voiceRefineUiState = VoiceRefineUiState.LOADING
            zenzService = null
            synchronized(zenzLoadLock) { zenzLoadState = ZENZ_LOAD_IDLE }
            zenzUiState = ZenzUiState.LOADING
        }
    }

    private fun tryLoadLlmModel(svc: ILlmService, modelPath: String): Boolean {
        val modelFile = java.io.File(modelPath)
        writeDiagnostic("llmConnection: loading model ${modelFile.absolutePath} (${modelFile.length() / 1024 / 1024}MB)")
        svc.loadModel(modelFile.absolutePath)

        // Local GGUF models typically mmap in 30–90s on mid-range Android; allow
        // 3 min per candidate before falling back.
        val start = System.currentTimeMillis()
        while (!svc.isModelLoaded && System.currentTimeMillis() - start < 180_000) {
            Thread.sleep(500)
        }
        val elapsed = (System.currentTimeMillis() - start) / 1000
        if (svc.isModelLoaded) {
            writeDiagnostic("llmConnection: ${modelFile.name} ready after ${elapsed}s")
            return true
        }
        writeDiagnostic("llmConnection: ${modelFile.name} did not become ready after ${elapsed}s")
        return false
    }

    /**
     * Local LLM load sequence, extracted from the connection callback so a
     * failed attempt can be retried on the next voice session (previously a
     * single failure disabled refinement for the IME's whole lifetime, with
     * only a line in the diagnostic file saying so). Every outcome updates
     * [voiceRefineUiState] and the [space.manus.nacre.ai.VoiceStatus] record.
     * Runs on a background thread; single-flight via [llmLoadInFlight].
     */
    private fun attemptLocalLlmLoad(svc: ILlmService) {
        if (!llmLoadInFlight.compareAndSet(false, true)) return
        try {
            // Cloud-first: when a cloud refiner key is configured, do NOT load
            // the ~1GB local LLM. Kept resident it pressures memory enough that
            // the OS kills other apps (and the IME itself — observed as a
            // "vis BFGS" process death). The cloud handles refinement; the local
            // model is only an offline fallback used when no cloud key is set.
            if (space.manus.nacre.ai.cloud.CloudLlmConfig.anyConfigured(service)) {
                voiceRefineUiState = VoiceRefineUiState.READY_CLOUD
                space.manus.nacre.ai.VoiceStatus.recordRefine(
                    service, space.manus.nacre.ai.VoiceStatus.RefineState.READY_CLOUD,
                    "cloud refiner configured — local LLM not loaded",
                )
                writeDiagnostic("llmConnection: cloud refiner configured — skipping local LLM load (frees ~1GB RAM)")
                return
            }
            if (svc.isModelLoaded) {
                llmService = svc
                voiceRefineUiState = VoiceRefineUiState.READY_LOCAL
                space.manus.nacre.ai.VoiceStatus.recordRefine(
                    service, space.manus.nacre.ai.VoiceStatus.RefineState.READY_LOCAL,
                    "model already loaded",
                )
                writeDiagnostic("llmConnection: model already loaded")
                return
            }
            val downloader = space.manus.nacre.ai.ModelDownloader(service)
            // Search all standard locations (filesDir, external files, /sdcard/Download, MediaStore).
            // Qwen 2.5 1.5B is the default (Gemma 4 E2B was retired: it never
            // loads on the pinned llama.cpp b3500 — see ModelDownloader).
            val modelPaths = downloader.getPreferredLlmModelPaths()
            if (modelPaths.isEmpty()) {
                downloader.ensureDefaultModelsDownloaded(downloadCompactKenLm = false)
                voiceRefineUiState = VoiceRefineUiState.UNAVAILABLE
                space.manus.nacre.ai.VoiceStatus.recordRefine(
                    service, space.manus.nacre.ai.VoiceStatus.RefineState.UNAVAILABLE,
                    "LLMモデル未ダウンロード（ダウンロードを要求済み）。次回音声セッションで再試行します",
                )
                writeDiagnostic("llmConnection: LLM model not found — Qwen 2.5 1.5B download requested, refinement unavailable (retry next session)")
                return
            }
            var loadedPath: String? = null
            for (modelPath in modelPaths) {
                if (tryLoadLlmModel(svc, modelPath)) {
                    loadedPath = modelPath
                    break
                }
                try {
                    svc.unloadModel()
                } catch (_: Exception) {
                }
            }
            if (loadedPath == null) {
                voiceRefineUiState = VoiceRefineUiState.UNAVAILABLE
                space.manus.nacre.ai.VoiceStatus.recordRefine(
                    service, space.manus.nacre.ai.VoiceStatus.RefineState.UNAVAILABLE,
                    "LLMモデルを読み込めませんでした。次回音声セッションで再試行します",
                )
                writeDiagnostic("llmConnection: no local LLM could be loaded — refinement unavailable (retry next session)")
                return
            }
            llmService = svc
            voiceRefineUiState = VoiceRefineUiState.READY_LOCAL
            val modelFile = java.io.File(loadedPath)
            space.manus.nacre.ai.VoiceStatus.recordRefine(
                service, space.manus.nacre.ai.VoiceStatus.RefineState.READY_LOCAL,
                modelFile.name,
            )
            writeDiagnostic("llmConnection: model ready: ${modelFile.name} (${modelFile.length() / 1024 / 1024}MB)")
        } catch (e: Exception) {
            voiceRefineUiState = VoiceRefineUiState.UNAVAILABLE
            space.manus.nacre.ai.VoiceStatus.recordRefine(
                service, space.manus.nacre.ai.VoiceStatus.RefineState.UNAVAILABLE,
                "LLM読み込み例外: ${e.message}",
            )
            writeDiagnostic("llmConnection EXCEPTION: ${e.message}")
        } finally {
            llmLoadInFlight.set(false)
        }
    }

    /**
     * Resolve this session's refinement preset once, at session start:
     * an explicit session override wins, then (in auto mode) the
     * foreground app's category mapping, then the standing manual
     * preset. The resolved instruction is what [tryLlmRefinement] and
     * Speak to Edit use for the whole session, and the label/auto flag
     * are surfaced in the voice panel.
     */
    private fun resolveSessionPreset() {
        val manualId = space.manus.nacre.ai.RefinePresets.activeId(service)
        val auto = space.manus.nacre.ai.RefinePresets.autoByAppEnabled(service)
        val pkg = try {
            service.currentInputEditorInfo?.packageName
        } catch (_: Exception) {
            null
        }
        val category = VoiceToneResolver.categorize(pkg)
        val mapping = VoiceAppCategory.values().associateWith {
            space.manus.nacre.ai.RefinePresets.categoryPresetId(service, it.name)
        }
        val resolved = VoiceToneResolver.resolvePreset(
            sessionOverrideId = sessionPresetOverrideId,
            autoEnabled = auto,
            manualId = manualId,
            category = category,
            autoMapping = mapping,
        )
        // A session override is explicit and single-use.
        sessionPresetOverrideId = null
        val preset = space.manus.nacre.ai.RefinePresets.presetById(resolved.presetId)
        sessionPresetInstruction = preset.instruction
        sessionPresetLabel = preset.label
        sessionPresetAuto = resolved.autoChosen
        writeDiagnostic(
            "resolveSessionPreset: pkg=$pkg category=$category preset=${preset.id}" +
                (if (resolved.autoChosen) " (auto)" else ""),
        )
    }

    /** Retry a failed local LLM load at the start of a voice session. */
    private fun maybeRetryLocalLlmLoad() {
        if (voiceRefineUiState != VoiceRefineUiState.UNAVAILABLE) return
        val svc = llmServiceAny ?: return
        Thread { attemptLocalLlmLoad(svc) }.start()
    }

    private val whisperCallback = object : IWhisperCallback.Stub() {
        override fun onResult(text: String) {
            writeDiagnostic("whisperCallback.onResult: text='${text.take(80)}' (${text.length} chars)")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val wasSession = whisperSessionActive
                whisperSessionActive = false
                isWhisperContinuousMode = false
                isListening = false
                partialText = ""
                rmsLevel = 0f
                releaseAudioFocus()

                if (isVoiceEditMode) {
                    // Speak to Edit: the final transcript is the edit
                    // instruction. Nothing was committed during recording,
                    // so applying (or failing) cannot lose user text.
                    whisperFinalizer.reset()
                    whisperCloudSession = false
                    when {
                        text.isBlank() ->
                            failVoiceEdit("指示を聞き取れませんでした")
                        postProcessor.isHallucination(text) ->
                            failVoiceEdit("指示を聞き取れませんでした")
                        else ->
                            runSpeakToEdit(postProcessor.resolveCorrections(text))
                    }
                    return@post
                }

                if (wasSession && !whisperCloudSession) {
                    // On-device session: segments were committed incrementally
                    // during recording; only the finalizer's pending tail is
                    // left. A blank/hallucinated-looking final transcript must
                    // not erase it, so the tail flushes regardless — the
                    // service already filtered hallucinations per segment.
                    val rest = whisperFinalizer.onFinal(text)
                    if (rest.isNotBlank()) {
                        finalizeWhisperSegment(rest)
                    } else {
                        discardWhisperPreview()
                    }
                    whisperCloudSession = false
                    return@post
                }

                // Cloud-ASR sessions (opt-in) and any non-session result: the
                // final text replaces the never-committed preview wholesale,
                // as before — provisional preview text is not user content,
                // so this is not a rewrite of committed text.
                whisperFinalizer.reset()
                whisperCloudSession = false
                if (text.isBlank()) {
                    discardWhisperPreview()
                    writeDiagnostic("whisperCallback.onResult: text is BLANK, skipping")
                } else if (postProcessor.isHallucination(text)) {
                    // SenseVoice/whisper-style ASR can hallucinate a whole utterance
                    // during silence (e.g. "ご視聴ありがとうございました") — never commit it.
                    discardWhisperPreview()
                    writeDiagnostic("whisperCallback.onResult: HALLUCINATION filtered, discarding: '${text.take(80)}'")
                } else {
                    // Deterministic self-correction resolution before quickClean/LLM —
                    // instant, and doesn't depend on the LLM refiner being available.
                    val corrected = postProcessor.resolveCorrections(text)
                    val cleaned = space.manus.nacre.ai.LlmPostProcessor.quickClean(
                        applyVoiceNumberNormalization(corrected)
                    )
                    writeDiagnostic("whisperCallback.onResult: quickClean='${cleaned.take(80)}'")
                    if (cleaned.isBlank()) {
                        discardWhisperPreview()
                    } else {
                        // commitText replaces the composing preview if one is
                        // showing; otherwise it simply inserts.
                        service.currentInputConnection?.commitText(cleaned, 1)
                        whisperPreviewInEditor = false
                        whisperPreviewDisplay = ""
                        committedInSession.append(cleaned)
                        voiceSessionChunks.add(cleaned)
                        // Stage 2: LLM refinement in background, replace
                        // quickClean result if better (guarded: never touch
                        // text the user has since edited).
                        tryLlmRefinement(cleaned, guarded = true)
                    }
                }
            }
        }

        override fun onPartialResult(text: String) {
            // Live preview: WhisperService invokes this with the accumulated
            // transcript every time a VAD segment completes during recording.
            // (It used to be a deliberate no-op — "no preview during
            // recording" — which is why dictation felt dead until stop.)
            if (!whisperSessionActive || text.isBlank()) return
            mainHandler.post {
                if (!whisperSessionActive) return@post
                if (isVoiceEditMode) {
                    // The utterance is an edit instruction: preview it in
                    // the panel only, never in the editor, and commit
                    // nothing — the selection stays untouched until the
                    // LLM result is verified at final.
                    partialText = space.manus.nacre.ai.LlmPostProcessor.quickClean(
                        postProcessor.resolveCorrections(text)
                    )
                    return@post
                }
                if (whisperCloudSession) {
                    // Cloud ASR will replace the whole transcript at stop, so
                    // the on-device segments are preview-only here.
                    showWhisperPreview(
                        space.manus.nacre.ai.LlmPostProcessor.quickClean(
                            postProcessor.resolveCorrections(text)
                        )
                    )
                    return@post
                }
                val step = whisperFinalizer.onBuffer(text)
                step.finalizeText?.let { finalizeWhisperSegment(it) }
                showWhisperPreview(space.manus.nacre.ai.LlmPostProcessor.quickClean(step.previewText))
            }
        }

        override fun onError(message: String) {
            writeDiagnostic("whisperCallback.onError: $message")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Log.w(TAG, "Whisper error: $message")
                isWhisperContinuousMode = false
                whisperSessionActive = false
                whisperFinalizer.reset()
                // An edit session dies with the error; the selection was
                // never touched, so there is nothing to roll back.
                clearVoiceEditSession()
                // Keep whatever was already spoken: finish the composing
                // preview as-is rather than dropping it with the error.
                if (whisperPreviewInEditor) {
                    service.currentInputConnection?.finishComposingText()
                    whisperPreviewInEditor = false
                    whisperPreviewDisplay = ""
                }
                isListening = false
                partialText = ""
                rmsLevel = 0f
                lastError = "Whisper: $message"
                voiceAsrUiState = VoiceAsrUiState.ERROR
                space.manus.nacre.ai.VoiceStatus.recordAsr(
                    service, space.manus.nacre.ai.VoiceStatus.AsrState.ERROR,
                    "認識エラー: $message",
                )
                releaseAudioFocus()
                // Don't auto-fallback to SpeechRecognizer — let user see the error and retry
            }
        }
    }

    // ── Whisper live preview / incremental commit helpers (main thread) ──

    private fun markAsrOnDevice() {
        voiceAsrUiState = VoiceAsrUiState.ON_DEVICE
        space.manus.nacre.ai.VoiceStatus.recordAsr(
            service, space.manus.nacre.ai.VoiceStatus.AsrState.READY,
            "sherpa-onnx on-device model loaded",
        )
    }

    /**
     * The deterministic, LLM-free part of PostProcessor that is safe to run
     * on raw ASR output: kanji-numeral normalization (dates, counters,
     * standalone numbers). PostProcessor.process() as a whole is deliberately
     * NOT wired in: it extracts voice commands (an utterance that is exactly
     * 「消して」 or ends with 「送信」 would be swallowed/stripped unless the
     * caller executes the command), and its clause-comma/punctuation stages
     * duplicate this manager's existing smartPunctuation pipeline.
     */
    private fun applyVoiceNumberNormalization(text: String): String {
        var result = postProcessor.normalizeDates(text)
        result = postProcessor.normalizeCounters(result)
        result = postProcessor.normalizeStandaloneNumbers(result)
        return result
    }

    /** Show [display] as the live preview: composing text in the editor
     *  (unless disabled for this session) and always as [partialText]. */
    private fun showWhisperPreview(display: String) {
        partialText = display
        if (!whisperEditorPreviewEnabled) return
        val ic = service.currentInputConnection ?: return
        if (display.isEmpty()) {
            discardWhisperPreview()
            return
        }
        ic.setComposingText(display, 1)
        whisperPreviewInEditor = true
        whisperPreviewDisplay = display
    }

    /** Drop the editor preview without committing it (cancel / resolved away). */
    private fun discardWhisperPreview() {
        if (!whisperPreviewInEditor) return
        val ic = service.currentInputConnection
        ic?.setComposingText("", 1)
        ic?.finishComposingText()
        whisperPreviewInEditor = false
        whisperPreviewDisplay = ""
    }

    /**
     * Finalize one segment: deterministic cleanup (self-correction already
     * resolved by the finalizer, then kanji-numeral normalization, then
     * quickClean) and commit. If the segment's raw form is currently the
     * editor's composing preview, commitText replaces exactly that region —
     * the user never sees a delete+retype of text that was already final.
     * Then stage-2 LLM refinement runs guarded (see [isSessionTailIntact]).
     */
    private fun finalizeWhisperSegment(correctedText: String) {
        val cleaned = space.manus.nacre.ai.LlmPostProcessor.quickClean(
            applyVoiceNumberNormalization(correctedText)
        )
        if (cleaned.isBlank()) {
            discardWhisperPreview()
            return
        }
        service.currentInputConnection?.commitText(cleaned, 1)
        whisperPreviewInEditor = false
        whisperPreviewDisplay = ""
        committedInSession.append(cleaned)
        voiceSessionChunks.add(cleaned)
        tryLlmRefinement(cleaned, guarded = true)
    }

    /**
     * Guard for refinement replacement in the incremental-commit world: the
     * chunk must still be the session's latest committed chunk AND the text
     * immediately before the cursor must be exactly that chunk plus the
     * current preview (if any). If the user typed, deleted, or moved the
     * cursor since, the refined text is dropped instead of fighting them.
     */
    private fun isSessionTailIntact(committed: String): Boolean {
        if (voiceSessionChunks.lastOrNull() != committed) return false
        val ic = service.currentInputConnection ?: return false
        val preview = if (whisperPreviewInEditor) whisperPreviewDisplay else ""
        val expected = committed + preview
        val before = try {
            ic.getTextBeforeCursor(expected.length, 0)?.toString()
        } catch (_: Exception) {
            null
        }
        return before == expected
    }

    // Deferred commit: hold results during pauses instead of committing immediately.
    // This prevents text from being fragmented when the user pauses to think.
    // Text is committed after DEFERRED_COMMIT_DELAY_MS of no new speech,
    // or immediately when the user taps stop.
    private var deferredText = StringBuilder()
    private var deferredCommitRunnable: Runnable? = null

    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(service)
    }

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            service, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start continuous voice input. Keeps listening until explicitly stopped.
     */
    fun startListening(language: String = "ja-JP") {
        if (isListening) return
        if (service.inputEngine.isPasswordField) {
            Log.w(TAG, "Voice input disabled in password field")
            return
        }
        if (!isBatteryOk()) {
            Log.w(TAG, "Battery too low for voice input")
            lastError = "バッテリー残量不足"
            return
        }
        if (!hasPermission()) {
            Log.w(TAG, "RECORD_AUDIO permission not granted, launching permission request")
            lastError = "マイク権限を許可してください"
            try {
                val intent = Intent().apply {
                    setClassName(service.packageName, "${service.packageName}.PermissionActivity")
                    putExtra("permission", Manifest.permission.RECORD_AUDIO)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch permission activity", e)
            }
            return
        }
        // First voice use: start the :whisper / :llm processes now (they used
        // to be bound at IME onCreate). The Whisper model loads asynchronously
        // in its connection callback; until whisperService becomes non-null
        // this session follows the same SpeechRecognizer fallback path that a
        // mic tap before model-load completion has always taken. The LLM is
        // bound here too because dictation refinement runs right after a
        // voice session.
        ensureWhisperBound()
        ensureLlmBound()
        if (whisperService == null && !isAvailable()) {
            Log.w(TAG, "Speech recognition not available (no Whisper, no SpeechRecognizer)")
            lastError = "音声認識が利用できません"
            return
        }

        isListening = true
        partialText = ""
        lastError = ""
        rmsLevel = 0f
        continuousMode = true
        currentLanguage = language
        committedInSession.clear()
        utteranceCount = 0
        consecutiveErrors = 0
        lastCommittedPartial = ""
        partialStableCount = 0
        lastPartialText = ""
        lastPartialTime = 0L
        deferredText.clear()
        cancelDeferredCommit()

        // Voice session state for live preview + degradation visibility.
        whisperFinalizer.reset()
        voiceSessionChunks.clear()
        whisperSessionActive = false
        whisperPreviewInEditor = false
        whisperPreviewDisplay = ""
        // Cloud ASR (opt-in, key configured) replaces the transcript at
        // stop; those sessions preview only, never commit incrementally.
        whisperCloudSession =
            space.manus.nacre.ai.cloud.CloudAsrConfig.isEnabled(service)
        // Don't hijack an in-progress kana conversion with the preview.
        whisperEditorPreviewEnabled =
            service.inputEngine.composingKana.isEmpty() && !service.inputEngine.isConverting
        asrFallbackDetail = ""

        // ── Speak to Edit + per-app tone (session setup) ──
        // A reliably read, non-empty selection at voice start makes this
        // an edit session: the utterance is an instruction for the
        // selected text, never dictation (see VoiceEditPolicy). When the
        // selection cannot be read reliably, the session falls back to
        // normal dictation — the panel simply shows no edit mode.
        val startIc = service.currentInputConnection
        var selectionAtStart: String? = null
        var selectionReadable = false
        if (startIc != null) {
            try {
                selectionAtStart = startIc.getSelectedText(0)?.toString()
                selectionReadable = true
            } catch (e: Exception) {
                writeDiagnostic("startListening: selection unreadable (${e.message}) — dictation mode")
            }
        }
        isVoiceEditMode = VoiceEditPolicy.shouldEnterEditMode(selectionAtStart, selectionReadable)
        editTargetText = if (isVoiceEditMode) selectionAtStart else null
        voiceEditUiState = if (isVoiceEditMode) VoiceEditUiState.RECORDING else VoiceEditUiState.IDLE
        if (isVoiceEditMode) {
            // The live editor preview must never touch the selection;
            // partials are shown in the panel only.
            whisperEditorPreviewEnabled = false
            writeDiagnostic("startListening: Speak to Edit session (selection ${selectionAtStart?.length} chars)")
        }
        resolveSessionPreset()
        // A refinement load failure must not persist silently: retry the
        // load now (no-op unless the state is UNAVAILABLE).
        maybeRetryLocalLlmLoad()
        // Refresh the ASR state for this session.
        val whisperReady = try { whisperService?.isModelLoaded == true } catch (_: Exception) { false }
        if (whisperReady) {
            markAsrOnDevice()
        } else if (voiceAsrUiState != VoiceAsrUiState.MODEL_MISSING) {
            voiceAsrUiState = VoiceAsrUiState.LOADING
        }

        // Whisper priority — use continuous mode if model loaded
        Log.i(TAG, "startListening: whisperService=${whisperService != null}, whisperBound=$whisperBound, isWhisperContinuousMode=$isWhisperContinuousMode")
        writeDiagnostic("startListening: whisperService=${whisperService != null}, whisperBound=$whisperBound")
        if (whisperService == null && whisperBound) {
            Log.w(TAG, "startListening: WhisperService is bound but whisperService proxy is null — model likely not loaded")
            writeDiagnostic("WARNING: bound but proxy null — model not loaded")
        }
        if (whisperService != null) {
            try {
                val modelLoaded = whisperService!!.isModelLoaded
                Log.i(TAG, "startListening: Whisper modelLoaded=$modelLoaded")
                if (modelLoaded) {
                    requestAudioFocus()
                    // Language is fixed to ja at SenseVoice init (see SherpaRecognizer);
                    // pass the session locale rather than a misleading "auto".
                    whisperService!!.startContinuousRecognition(currentLanguage, whisperCallback)
                    isWhisperContinuousMode = true  // Set AFTER successful IPC
                    whisperSessionActive = true
                    markAsrOnDevice()
                    Log.i(TAG, "startListening: Whisper continuous mode STARTED")
                    writeDiagnostic("WHISPER STARTED: continuous mode active")
                    return
                }
            } catch (e: android.os.RemoteException) {
                Log.w(TAG, "Whisper IPC failed, falling back to SpeechRecognizer", e)
                isWhisperContinuousMode = false
                releaseAudioFocus()
            }
        }

        // Falling back to Android SpeechRecognizer. This may be NETWORK
        // recognition depending on the device — the substitution is always
        // labeled in the voice panel and recorded, never silent.
        asrFallbackDetail = when (voiceAsrUiState) {
            VoiceAsrUiState.MODEL_MISSING ->
                "ASRモデル未ダウンロードのため、端末標準の音声認識（外部認識の場合あり）で代用"
            VoiceAsrUiState.ERROR ->
                "オンデバイス認識でエラーのため、端末標準の音声認識（外部認識の場合あり）で代用"
            else ->
                "オンデバイスモデル読込中のため、端末標準の音声認識（外部認識の場合あり）で代用"
        }
        startRecognizer()
    }

    private fun createRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage)
            // Accept both ja and en for bilingual users
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLanguage)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, true)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                putStringArrayListExtra(
                    RecognizerIntent.EXTRA_BIASING_STRINGS,
                    ArrayList(STT_BIASING_TERMS),
                )
            }
            // Offline preference disabled — causes ERROR_NO_MATCH on devices
            // without offline model downloaded. Let the engine decide.
            // putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Silence tolerance — maximized for always-on dictation
            putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 10000L)
            putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 5000L)
            putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 500L)
            // Web search bias off (better for natural language)
            putExtra(RecognizerIntent.EXTRA_WEB_SEARCH_ONLY, false)
        }
    }

    private fun startRecognizer() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null

                // Use pre-created recognizer if available (zero-gap restart)
                val recognizer = nextRecognizer ?: SpeechRecognizer.createSpeechRecognizer(service.applicationContext)
                nextRecognizer = null

                if (recognizer == null) {
                    Log.e(TAG, "createSpeechRecognizer returned null")
                    lastError = "音声認識を初期化できません"
                    isListening = false
                    continuousMode = false
                    return@post
                }

                recognizer.setRecognitionListener(listener)
                speechRecognizer = recognizer
                recognizer.startListening(createRecognizerIntent())
                if (isListening) {
                    // The external recognizer is now the active engine for
                    // this session — surface it (see startListening).
                    voiceAsrUiState = VoiceAsrUiState.EXTERNAL
                    space.manus.nacre.ai.VoiceStatus.recordAsr(
                        service, space.manus.nacre.ai.VoiceStatus.AsrState.EXTERNAL_FALLBACK,
                        asrFallbackDetail.ifBlank { "端末標準の音声認識（外部認識の場合あり）で代用" },
                    )
                }
                Log.i(TAG, "SpeechRecognizer started (lang=$currentLanguage, utterance=$utteranceCount)")
                writeDiagnostic("FALLBACK: SpeechRecognizer started (NOT Whisper)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening", e)
                lastError = "音声認識の開始に失敗: ${e.message}"
                isListening = false
                continuousMode = false
            }
        }
    }

    /**
     * Pre-create the next recognizer while current one is still running.
     * This eliminates the gap between utterances.
     */
    private fun prepareNextRecognizer() {
        if (!continuousMode) return
        mainHandler.post {
            try {
                if (nextRecognizer == null) {
                    nextRecognizer = SpeechRecognizer.createSpeechRecognizer(service.applicationContext)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pre-create next recognizer", e)
            }
        }
    }

    fun stopListening() {
        writeDiagnostic("stopListening: whisperMode=$isWhisperContinuousMode, whisperService=${whisperService != null}")
        if (isWhisperContinuousMode) {
            isWhisperContinuousMode = false
            isListening = false
            partialText = ""
            rmsLevel = 0f
            try {
                whisperService?.stopRecognition()
            } catch (e: android.os.RemoteException) {
                writeDiagnostic("stopListening: IPC FAILED: ${e.message}")
                service.currentInputConnection?.finishComposingText()
            }
            // Release audio focus AFTER stopRecognition IPC — recording may still be
            // finishing in the remote process; releasing focus early could inject noise.
            releaseAudioFocus()
            return
        }
        continuousMode = false
        // Commit any remaining partial text — never in Speak to Edit
        // mode: the partial there is an instruction, not dictation.
        if (!isVoiceEditMode) commitRemainingPartial()
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
        try {
            nextRecognizer?.destroy()
            nextRecognizer = null
        } catch (_: Exception) {}
        isListening = false
        partialText = ""
        rmsLevel = 0f
    }

    fun cancel() {
        if (isWhisperContinuousMode) {
            try {
                whisperService?.cancelContinuousRecognition()
            } catch (e: android.os.RemoteException) {
                Log.w(TAG, "Whisper cancelContinuousRecognition IPC failed", e)
            }
            isWhisperContinuousMode = false
            whisperSessionActive = false
            whisperFinalizer.reset()
            clearVoiceEditSession()
            isListening = false
            partialText = ""
            rmsLevel = 0f
            releaseAudioFocus()
            // Cancel discards the unfinalized preview (already-committed
            // segments stay — they were final when committed).
            if (whisperPreviewInEditor) {
                discardWhisperPreview()
            } else {
                service.currentInputConnection?.finishComposingText()
            }
            return
        }
        continuousMode = false
        clearVoiceEditSession()
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        try {
            nextRecognizer?.destroy()
            nextRecognizer = null
        } catch (_: Exception) {}
        isListening = false
        partialText = ""
        rmsLevel = 0f
    }

    fun release() {
        cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    // ── Streaming partial commit ────────────────────────────────────────

    /**
     * Typeless-style: commit stable portions of partial results in real-time.
     * If the same partial text appears 3+ times in a row (stable for ~750ms),
     * commit the stable prefix and keep only the changing suffix as partial.
     */
    private fun tryStreamingCommit(partial: String) {
        if (partial.isEmpty()) return
        val now = System.currentTimeMillis()

        if (partial == lastPartialText) {
            partialStableCount++
        } else {
            partialStableCount = 0
            lastPartialText = partial
            lastPartialTime = now
        }

        // Commit stable prefix after 3 consecutive identical partials (~750ms)
        // or if partial is very long (>30 chars) and hasn't changed for 500ms
        val shouldCommit = (partialStableCount >= 3) ||
            (partial.length > 30 && now - lastPartialTime > 500 && partialStableCount >= 1)

        if (shouldCommit && partial.length > lastCommittedPartial.length) {
            // Find stable prefix: everything except the last "word" being edited
            val commitUpTo = findStablePrefix(partial)
            if (commitUpTo > lastCommittedPartial.length) {
                val toCommit = partial.substring(lastCommittedPartial.length, commitUpTo)
                if (toCommit.isNotBlank()) {
                    val converted = convertVoiceCommands(toCommit)
                    val spaced = addUtteranceSpacing(converted)
                    service.currentInputConnection?.commitText(spaced, 1)
                    committedInSession.append(spaced)
                    lastCommittedPartial = partial.substring(0, commitUpTo)
                    // Log.d removed: never log user voice input text
                }
            }
        }
    }

    /**
     * Find the boundary of the stable prefix in partial text.
     * For Japanese: commit up to the last particle/punctuation boundary.
     * For English: commit up to the last space (complete words only).
     *
     * When KenLM is available, verify the chosen boundary by scoring:
     * if the prefix forms a natural sentence fragment, commit it.
     * If not, try earlier boundaries.
     */
    private fun findStablePrefix(text: String): Int {
        if (text.length < 3) return 0
        val hasJapanese = text.any { it.code in 0x3000..0x9FFF || it.code in 0xFF00..0xFFEF }

        if (hasJapanese) {
            val punctuationIdx = text.indexOfLast { it in "。、！？!?,、" }
            if (punctuationIdx >= 0 && punctuationIdx >= (text.length * 0.4f).toInt()) {
                return punctuationIdx + 1
            }

            // Collect candidate break points (particle/punctuation boundaries)
            val target = (text.length * 0.8).toInt()
            val candidates = mutableListOf<Int>()
            for (i in target downTo (text.length / 2)) {
                val c = text[i]
                if (c in "。、！？「」（）") {
                    candidates.add(i + 1)
                } else if (i > 0 && c in "はがをにでとのもへやかなけど") {
                    candidates.add(i + 1)
                }
            }

            // If no natural break points, fall back to target position
            if (candidates.isEmpty()) return target

            // With KenLM: pick the boundary that creates the highest-scoring prefix
            val scorer = (service.inputEngine.dictionary as? NacreDictionary)?.kenLmScorer
            if (scorer != null && scorer.isReady() && candidates.size > 1) {
                val context = committedInSession.toString().takeLast(20)
                val prefixes = candidates.take(4).map { listOf(text.substring(0, it)) }
                val scores = scorer.scoreBatch(prefixes, context)
                val bestIdx = scores.indices.maxByOrNull { scores[it] } ?: 0
                return candidates[bestIdx]
            }

            return candidates.first()
        } else {
            // English: commit complete words only
            val lastSpace = text.lastIndexOf(' ', text.length - 2)
            return if (lastSpace > lastCommittedPartial.length) lastSpace + 1 else 0
        }
    }

    /**
     * When stopping, commit any remaining uncommitted partial text.
     */
    // ── Deferred commit ─────────────────────────────────────────────────
    // Instead of committing each utterance immediately, buffer results and
    // commit after a pause. This lets the user think mid-sentence without
    // the text being fragmented. The deferred text is shown as partialText
    // (preview) until committed.

    private fun cancelDeferredCommit() {
        deferredCommitRunnable?.let { mainHandler.removeCallbacks(it) }
        deferredCommitRunnable = null
    }

    /**
     * Flush all buffered text to the input field.
     * Only called on explicit stop — never on a timer.
     * This is the Typeless model: record forever, commit on user action.
     */
    private fun flushDeferredText() {
        cancelDeferredCommit()
        val text = deferredText.toString()
        deferredText.clear()
        if (text.isBlank()) return

        val withCommas = insertMidSentenceCommas(text)
        val processed = smartPunctuation(withCommas)
        service.currentInputConnection?.commitText(processed, 1)
        committedInSession.append(processed)
        utteranceCount++

        partialText = ""
    }

    private fun appendDeferredText(text: String) {
        if (text.isEmpty()) return
        val converted = convertVoiceCommands(text)
        if (deferredText.isNotEmpty()) {
            // Japanese: no space needed between utterances
            val lastChar = deferredText.last()
            val hasJapanese = lastChar.code in 0x3000..0x9FFF || lastChar.code in 0xFF00..0xFFEF
            if (!hasJapanese && converted.first().code in 0x20..0x7E) {
                deferredText.append(" ")
            }
        }
        deferredText.append(converted)
        // Show buffered text as live preview in candidate bar
        partialText = deferredText.toString()
        // No timer — text stays in buffer until user taps stop
    }

    private fun commitRemainingPartial() {
        val remaining = if (lastCommittedPartial.isNotEmpty() && lastPartialText.length > lastCommittedPartial.length) {
            lastPartialText.substring(lastCommittedPartial.length)
        } else if (lastCommittedPartial.isEmpty() && partialText.isNotEmpty()) {
            partialText
        } else {
            null
        }
        if (remaining != null && remaining.isNotBlank()) {
            val converted = convertVoiceCommands(remaining)
            val spaced = addUtteranceSpacing(converted)
            val processed = smartPunctuation(spaced)
            service.currentInputConnection?.commitText(processed, 1)
            committedInSession.append(processed)
        }
        lastCommittedPartial = ""
        partialStableCount = 0
        lastPartialText = ""
    }

    // ── Voice commands → symbol conversion ──────────────────────────

    /**
     * 音声コマンドを記号に変換する。Typeless同等。
     * 「てん」→、 「まる」→。 「はてな」→？ 等。
     * テキスト中のどこに出現しても変換する（文末以外も）。
     */
    private fun convertVoiceCommands(text: String): String {
        var result = text

        // 完全一致のコマンド（単独で発話された場合）
        val exactCommands = mapOf(
            "改行" to "\n",
            "かいぎょう" to "\n",
            "エンター" to "\n",
            "スペース" to " ",
            "タブ" to "\t",
        )
        val trimmed = result.trim()
        exactCommands[trimmed]?.let { return it }

        // インライン変換（文中に出現するコマンド）
        // 順番重要: 長いパターンを先にマッチ
        val inlineCommands = listOf(
            // 句読点
            "まる" to "。",
            "マル" to "。",
            "てん" to "、",
            "テン" to "、",
            "こめてん" to "※",
            "コメテン" to "※",
            // 疑問符・感嘆符
            "はてな" to "？",
            "ハテナ" to "？",
            "クエスチョン" to "？",
            "くえすちょん" to "？",
            "クエスチョンマーク" to "？",
            "びっくり" to "！",
            "ビックリ" to "！",
            "びっくりマーク" to "！",
            "ビックリマーク" to "！",
            "エクスクラメーション" to "！",
            "かんたんふ" to "！",
            "感嘆符" to "！",
            "ぎもんふ" to "？",
            "疑問符" to "？",
            // 括弧
            "かっこ" to "「",
            "カッコ" to "「",
            "かっことじ" to "」",
            "カッコトジ" to "」",
            "とじかっこ" to "」",
            "まるかっこ" to "（",
            "まるかっことじ" to "）",
            "かぎかっこ" to "「",
            "かぎかっことじ" to "」",
            "にじゅうかっこ" to "『",
            "にじゅうかっことじ" to "』",
            // その他記号
            "なかぐろ" to "・",
            "ナカグロ" to "・",
            "中黒" to "・",
            "コロン" to "：",
            "ころん" to "：",
            "セミコロン" to "；",
            "せみころん" to "；",
            "スラッシュ" to "／",
            "すらっしゅ" to "／",
            "さんてん" to "…",
            "三点" to "…",
            "てんてんてん" to "…",
            "テンテンテン" to "…",
            "ハイフン" to "ー",
            "はいふん" to "ー",
            "マイナス" to "−",
            "まいなす" to "−",
            "プラス" to "＋",
            "ぷらす" to "＋",
            "イコール" to "＝",
            "いこーる" to "＝",
            "アンダーバー" to "＿",
            "あんだーばー" to "＿",
            "アットマーク" to "＠",
            "あっとまーく" to "＠",
            "シャープ" to "＃",
            "しゃーぷ" to "＃",
            "アスタリスク" to "＊",
            "あすたりすく" to "＊",
            "パーセント" to "％",
            "ぱーせんと" to "％",
            "アンパサンド" to "＆",
            "あんぱさんど" to "＆",
            "ドル" to "＄",
            "どる" to "＄",
            "えん" to "￥",
            "円マーク" to "￥",
            "チルダ" to "～",
            "ちるだ" to "～",
            "バックスラッシュ" to "＼",
            "ばっくすらっしゅ" to "＼",
            "パイプ" to "｜",
            "ぱいぷ" to "｜",
            // スペース・改行（文中）
            "かいぎょう" to "\n",
            "改行" to "\n",
        )

        // Words that contain command substrings but should NOT be converted
        // e.g. "かっこいい" contains "かっこ" but should not become "「いい"
        val safeWords = setOf(
            "かっこいい", "カッコイイ", "かっこ悪い", "カッコ悪い",
            "かっこう", "カッコウ", // 郭公
            "てんき", "テンキ", "てんきん", "テンキン", // 天気, 転勤
            "てんけん", "テンケン", // 点検
            "てんさい", "テンサイ", // 天才
            "まるで", "マルデ", // まるで〜
            "まるい", "マルイ", // 丸い / マルイ
            "まるごと", "マルゴト",
        )

        // Check if any safe word is present, temporarily replace them
        var protected = result
        val placeholders = mutableMapOf<String, String>()
        for (word in safeWords) {
            if (protected.contains(word)) {
                val placeholder = "\u0000${placeholders.size}\u0000"
                placeholders[placeholder] = word
                protected = protected.replace(word, placeholder)
            }
        }

        // Apply inline command replacements
        for ((cmd, sym) in inlineCommands) {
            protected = protected.replace(cmd, sym)
        }

        // Restore protected words
        var final = protected
        for ((placeholder, word) in placeholders) {
            final = final.replace(placeholder, word)
        }

        return final
    }

    // ── Smart punctuation ─────────────────────────────────────────────

    /**
     * 文末に自然な句読点を自動追加する。
     * convertVoiceCommands() で明示的に入力された句読点がある場合はスキップ。
     * KenLMが利用可能な場合、パターンマッチに該当しない境界ケースで
     * 「？」「！」「。」のスコアを比較して最適な句読点を選択する。
     */
    private fun smartPunctuation(text: String): String {
        if (text.isEmpty()) return text
        val lastChar = text.last()
        // 既に句読点・記号で終わっている場合はそのまま
        if (lastChar in "。、！？.!?,;:「」『』（）・…\n") return text

        val hasJapanese = text.any { it.code in 0x3000..0x9FFF || it.code in 0xFF00..0xFFEF }

        if (hasJapanese) {
            // 疑問パターン（80+ patterns for comprehensive coverage）
            val questionEndings = arrayOf(
                // 丁寧語
                "ですか", "ますか", "ませんか", "でしょうか", "でしたか",
                "ございますか", "いただけますか", "くださいますか",
                // 確認系
                "よね", "だよね", "ですよね", "じゃない", "じゃないですか",
                "でしたっけ", "だっけ", "ではないですか", "ではありませんか",
                // カジュアル
                "かな", "かね", "かしら", "だろうか", "だろう", "でしょう",
                "じゃん", "なの", "のか",
                // 疑問詞で始まるフレーズの末尾
                "って何", "ってなに", "ってなんですか", "とは", "ってどういうこと",
                "ってこと", "ということ", "の意味", "ってどうなった",
                // 動詞疑問
                "できる", "できますか", "してくれる", "してもらえる",
                "知ってる", "知っていますか", "分かる", "わかりますか",
                "ある", "ありますか", "いる", "いますか",
                // 比較・選択
                "どっち", "どちら", "どれ", "どこ", "どう", "いつ", "なぜ",
                "どうして", "なんで", "いくら", "いくつ", "どのくらい",
                "何人", "何日", "何時",
            )
            // Check longest matches first to avoid partial matches
            if (questionEndings.sortedByDescending { it.length }.any { text.endsWith(it) } ||
                (text.endsWith("か") && text.length > 2) ||
                (text.endsWith("の") && text.length > 4)
            ) {
                return "$text？"
            }
            // 感嘆パターン
            val exclamationEndings = arrayOf(
                // 命令・依頼
                "ください", "くれ", "しろ", "しなさい", "やれ", "やめろ", "やめて",
                "ないで", "するな", "するぞ", "してくれ", "やってくれ",
                "頑張れ", "気をつけて", "お願い",
                // 感情
                "すごい", "すげー", "やばい", "やべー", "最高", "最悪",
                "うれしい", "嬉しい", "楽しい", "悲しい", "つらい", "辛い",
                "びっくり", "信じられない", "ありえない",
                // 完了・成功
                "やった", "できた", "終わった", "勝った", "成功",
                // 肯定強調
                "だろ", "だぜ", "だぞ", "だよ", "ぞ", "よ", "ね",
                "まじ", "マジ", "本当",
                // 挨拶系
                "ありがとう", "おめでとう", "お疲れ", "いいね",
            )
            if (exclamationEndings.sortedByDescending { it.length }.any { text.endsWith(it) }) {
                return "$text！"
            }

            // KenLM-assisted punctuation for ambiguous cases
            val scorer = (service.inputEngine.dictionary as? NacreDictionary)?.kenLmScorer
            if (scorer != null && scorer.isReady()) {
                val context = committedInSession.toString().takeLast(20)
                val scores = scorer.scoreBatch(
                    listOf(listOf("$text。"), listOf("$text？"), listOf("$text！")),
                    context,
                )
                val bestIdx = scores.indices.maxByOrNull { scores[it] } ?: 0
                return when (bestIdx) {
                    1 -> "$text？"
                    2 -> "$text！"
                    else -> "$text。"
                }
            }

            return "$text。"
        } else {
            val lower = text.lowercase()
            if (lower.startsWith("what ") || lower.startsWith("how ") ||
                lower.startsWith("why ") || lower.startsWith("where ") ||
                lower.startsWith("when ") || lower.startsWith("who ") ||
                lower.startsWith("which ") || lower.startsWith("is ") ||
                lower.startsWith("are ") || lower.startsWith("do ") ||
                lower.startsWith("does ") || lower.startsWith("can ") ||
                lower.startsWith("could ") || lower.startsWith("would ") ||
                lower.startsWith("should ") || lower.startsWith("have ") ||
                lower.startsWith("has ") || lower.startsWith("will ") ||
                lower.startsWith("did ") || lower.endsWith("?") ||
                lower.endsWith(" right") || lower.endsWith(" huh")
            ) {
                return "$text?"
            }
            return "$text."
        }
    }

    /**
     * Insert commas (、) at natural clause boundaries within Japanese text.
     * Targets conjunctive particles and connectives that typically precede a pause.
     */
    private fun insertMidSentenceCommas(text: String): String {
        if (text.length < 8) return text // Too short for mid-sentence commas
        val hasJapanese = text.any { it.code in 0x3000..0x9FFF || it.code in 0xFF00..0xFFEF }
        if (!hasJapanese) return text

        var result = text
        // Clause-boundary patterns: insert 、 after these conjunctive forms
        // Only insert if not already followed by punctuation
        val clauseBreaks = listOf(
            "ので" to "ので、", "から" to "から、", "けど" to "けど、",
            "けれど" to "けれど、", "けれども" to "けれども、",
            "ですが" to "ですが、", "ますが" to "ますが、",
            "たら" to "たら、", "ても" to "ても、", "のに" to "のに、",
            "ながら" to "ながら、", "つつ" to "つつ、",
            "そして" to "そして、", "しかし" to "しかし、",
            "でも" to "でも、", "ただ" to "ただ、", "また" to "また、",
            "さらに" to "さらに、", "それから" to "それから、",
            "ところが" to "ところが、", "一方" to "一方、",
            "なので" to "なので、", "だから" to "だから、",
        )
        for ((pattern, replacement) in clauseBreaks) {
            // Only replace if pattern is followed by non-punctuation content
            val idx = result.indexOf(pattern)
            if (idx >= 0) {
                val afterIdx = idx + pattern.length
                if (afterIdx < result.length && result[afterIdx] !in "。、！？「」『』（）・…\n、") {
                    result = result.substring(0, afterIdx) + "、" + result.substring(afterIdx)
                }
            }
        }
        return result
    }

    private fun addUtteranceSpacing(text: String): String {
        if (committedInSession.isEmpty()) return text
        val lastCommitted = committedInSession.last()
        // Japanese: no space needed
        if (lastCommitted.code in 0x3000..0x9FFF || lastCommitted.code in 0xFF00..0xFFEF) {
            return text
        }
        return if (lastCommitted != ' ') " $text" else text
    }

    /**
     * Select the best recognition result using confidence scores + KenLM language model.
     * If KenLM is available, re-rank candidates by combining engine confidence with
     * language model probability. This catches common mis-recognitions like
     * "こんにちわ" vs "こんにちは" where the LM strongly prefers the correct form.
     */
    private fun selectBestResult(results: Bundle?): String {
        val alternatives = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?: return ""
        if (alternatives.isEmpty()) return ""
        if (alternatives.size == 1) return alternatives[0]

        val confidences = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        val normalizedAlternatives = alternatives.map { space.manus.nacre.ai.LlmPostProcessor.quickClean(it).trim() }

        // Try KenLM re-ranking if available
        val scorer = (service.inputEngine.dictionary as? NacreDictionary)?.kenLmScorer
        if (scorer != null && scorer.isReady()) {
            val precedingContext = committedInSession.toString().takeLast(80)
            val sentences = normalizedAlternatives.mapIndexed { index, cleaned ->
                listOf(cleaned.ifEmpty { alternatives[index] })
            }
            val lmScores = scorer.scoreBatch(sentences, precedingContext)

            // Combined score: confidence × weight + LM score × weight
            // LM scores are log10 probabilities (negative), higher = better
            var bestIdx = 0
            var bestScore = Float.MIN_VALUE
            for (i in alternatives.indices) {
                val conf = if (confidences != null && i < confidences.size) confidences[i] else 0.5f
                val candidateText = normalizedAlternatives[i].ifEmpty { alternatives[i] }
                // Normalize confidence (0-1) to similar scale as LM scores
                // LM scores are typically -10 to -2 range
                val combined = conf * 5f + lmScores[i] * 0.3f + if (candidateText.length < alternatives[i].length) 0.15f else 0f
                if (combined > bestScore) {
                    bestScore = combined
                    bestIdx = i
                }
            }
            return normalizedAlternatives[bestIdx].ifEmpty { alternatives[bestIdx] }
        }

        // Fallback: use confidence scores only
        if (confidences != null && confidences.size == alternatives.size) {
            var bestIdx = 0
            var bestConf = confidences[0]
            for (i in 1 until confidences.size) {
                if (confidences[i] > bestConf) {
                    bestConf = confidences[i]
                    bestIdx = i
                }
            }
            return normalizedAlternatives[bestIdx].ifEmpty { alternatives[bestIdx] }
        }
        return normalizedAlternatives[0].ifEmpty { alternatives[0] }
    }

    // ── RecognitionListener ───────────────────────────────────────────

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            lastError = ""
            rmsLevel = 0f
            consecutiveErrors = 0
            // Pre-create next recognizer for zero-gap restart
            prepareNextRecognizer()
        }

        override fun onBeginningOfSpeech() {
            service.feedbackManager.onTrackballStep()
        }

        override fun onRmsChanged(rmsdB: Float) {
            rmsLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            rmsLevel = 0f
        }

        override fun onError(error: Int) {
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "聞き取れませんでした"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "音声入力タイムアウト"
                SpeechRecognizer.ERROR_AUDIO -> "オーディオエラー"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "マイク権限がありません"
                SpeechRecognizer.ERROR_NETWORK -> "ネットワークエラー"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ネットワークタイムアウト"
                SpeechRecognizer.ERROR_SERVER -> "サーバーエラー"
                SpeechRecognizer.ERROR_CLIENT -> "クライアントエラー"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "認識エンジンビジー"
                else -> "エラー ($error)"
            }

            // Silence errors (NO_MATCH, SPEECH_TIMEOUT) are normal in always-on mode
            // — don't count them toward the consecutive error limit
            val isSilenceError = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (!isSilenceError) {
                consecutiveErrors++
            }
            Log.w(TAG, "Recognition error: $msg (code=$error, consecutive=$consecutiveErrors, silence=$isSilenceError)")

            // In continuous mode, auto-restart on recoverable errors
            if (continuousMode && error in RECOVERABLE_ERRORS) {
                partialText = ""
                rmsLevel = 0f
                // Only show error for non-silence errors (don't spam the user)
                if (!isSilenceError) {
                    lastError = msg
                }

                // Give up after too many consecutive real errors
                if (consecutiveErrors > MAX_CONSECUTIVE_ERRORS) {
                    Log.w(TAG, "Too many consecutive errors ($consecutiveErrors), stopping")
                    lastError = msg
                    isListening = false
                    continuousMode = false
                    return
                }

                // Silence errors: restart immediately (always-on behavior)
                // Real errors: exponential backoff
                val delay = if (isSilenceError) {
                    50L // Near-instant restart for silence — Typeless-style always-on
                } else {
                    (100L * (1 shl (consecutiveErrors - 1).coerceAtMost(4)))
                }

                mainHandler.postDelayed({
                    if (continuousMode && isBatteryOk()) {
                        startRecognizer()
                    } else {
                        isListening = false
                        continuousMode = false
                    }
                }, delay)
            } else {
                lastError = msg
                partialText = ""
                rmsLevel = 0f
                isListening = false
                continuousMode = false
            }
        }

        override fun onResults(results: Bundle?) {
            val rawText = selectBestResult(results)
            writeDiagnostic("SpeechRecognizer.onResults: text='${rawText.take(80)}' (${rawText.length} chars)")

            if (isVoiceEditMode) {
                // Speak to Edit: this one utterance concludes the session.
                // The transcript is an instruction, not dictation, so
                // nothing is committed here, and the continuous restart
                // below is suppressed — the edit (or its failure) ends it.
                isListening = false
                continuousMode = false
                rmsLevel = 0f
                partialText = ""
                lastCommittedPartial = ""
                partialStableCount = 0
                lastPartialText = ""
                when {
                    rawText.isEmpty() ->
                        failVoiceEdit("指示を聞き取れませんでした")
                    postProcessor.isHallucination(rawText) ->
                        failVoiceEdit("指示を聞き取れませんでした")
                    else ->
                        runSpeakToEdit(postProcessor.resolveCorrections(rawText))
                }
                return
            }

            if (rawText.isEmpty()) {
                writeDiagnostic("SpeechRecognizer.onResults: EMPTY text, skipping")
            } else if (postProcessor.isHallucination(rawText)) {
                // Known ASR hallucination (e.g. "ご視聴ありがとうございました" during
                // silence, or a stuck repetition loop) — discard instead of committing.
                writeDiagnostic("SpeechRecognizer.onResults: HALLUCINATION filtered, discarding: '${rawText.take(80)}'")
            } else {
                // Deterministic self-correction resolution ("火曜日じゃなくて水曜日"
                // → "水曜日") before anything else — instant, no LLM round-trip needed.
                val text = postProcessor.resolveCorrections(rawText)
                val asciiRatio = if (text.isEmpty()) 0f else {
                    text.count { it.code in 0x20..0x7E }.toFloat() / text.length
                }
                currentLanguage = if (asciiRatio > 0.7f) "en-US" else "ja-JP"

                // Streaming partial-commit may have already flushed a stable prefix
                // of this same utterance to the input field (tryStreamingCommit).
                // Only commit what's left, or this final result would duplicate it.
                val remainder = if (lastCommittedPartial.isNotEmpty() && text.startsWith(lastCommittedPartial)) {
                    text.substring(lastCommittedPartial.length)
                } else {
                    text
                }

                if (remainder.isNotBlank()) {
                    val converted = convertVoiceCommands(remainder)
                    val spaced = addUtteranceSpacing(converted)
                    val withCommas = insertMidSentenceCommas(spaced)
                    val processed = smartPunctuation(withCommas)

                    val cleaned = space.manus.nacre.ai.LlmPostProcessor.quickClean(processed)
                    service.currentInputConnection?.commitText(cleaned, 1)
                    committedInSession.append(cleaned)
                    // Stage 2: LLM refinement in background
                    tryLlmRefinement(cleaned)
                }
                utteranceCount++
            }

            rmsLevel = 0f
            lastCommittedPartial = ""
            partialStableCount = 0
            lastPartialText = ""
            consecutiveErrors = 0

            // Zero-gap continuous restart — recognizer restarts instantly
            // so the user never has to tap again. Silence is fine.
            if (continuousMode && isBatteryOk()) {
                startRecognizer()
            } else {
                isListening = false
                continuousMode = false
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val rawText = selectBestResult(partialResults)
            if (rawText.isBlank()) return

            if (isVoiceEditMode) {
                // Instruction preview only — no streaming commit (a
                // committed prefix would insert the instruction as text).
                partialText = LlmPostProcessor.quickClean(postProcessor.resolveCorrections(rawText))
                return
            }

            // Typeless-style retroactive correction: resolve any self-correction
            // cue in this partial ("火曜日じゃなくて水曜日" → "水曜日"). If the
            // corrected text no longer starts with what streaming partial-commit
            // already flushed to the input field, that already-committed prefix
            // is now stale — retract it before continuing.
            val check = postProcessor.checkRetroactiveCorrection(rawText, lastCommittedPartial)
            if (check.mustRetract) {
                service.currentInputConnection?.deleteSurroundingText(lastCommittedPartial.length, 0)
                if (committedInSession.length >= lastCommittedPartial.length) {
                    committedInSession.setLength(committedInSession.length - lastCommittedPartial.length)
                }
                writeDiagnostic("onPartialResults: retracted stale committed prefix (${lastCommittedPartial.length} chars) after self-correction")
                lastCommittedPartial = ""
                partialStableCount = 0
                lastPartialText = ""
            }
            val text = check.correctedText
            if (text.isBlank()) return

            // Keep the latest partial visible in the UI and try to commit only
            // the stable prefix. This gives us Typeless-style segmentation
            // without waiting for final results on every pause.
            val converted = convertVoiceCommands(text)
            partialText = LlmPostProcessor.quickClean(converted)
            tryStreamingCommit(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun requestAudioFocus() {
        val am = service.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        audioFocusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        am.requestAudioFocus(audioFocusRequest!!)
    }

    private fun releaseAudioFocus() {
        audioFocusRequest?.let {
            val am = service.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            am.abandonAudioFocusRequest(it)
            audioFocusRequest = null
        }
    }

    /**
     * Lazy-bind entry point: start the :whisper process (and its model load)
     * on first voice use instead of at IME onCreate. Idempotent — the bind
     * functions early-return once bound, and the binding stays warm for the
     * IME's lifetime afterwards.
     */
    fun ensureWhisperBound() = bindWhisperService()

    /** See [ensureWhisperBound]. */
    fun ensureLlmBound() = bindLlmService()

    fun bindWhisperService() {
        if (whisperBound) return
        try {
            val intent = android.content.Intent().apply {
                setClassName(service.packageName, "space.manus.nacre.ai.WhisperService")
            }
            val bound = service.bindService(intent, whisperConnection, android.content.Context.BIND_AUTO_CREATE)
            whisperBound = bound
            Log.i(TAG, "bindWhisperService: bound=$bound, package=${service.packageName}")
            writeDiagnostic("bindWhisperService: bound=$bound")
        } catch (e: Exception) {
            Log.e(TAG, "bindWhisperService failed", e)
            writeDiagnostic("bindWhisperService FAILED: ${e.message}")
        }
    }

    fun unbindWhisperService() {
        if (whisperBound) {
            service.unbindService(whisperConnection)
            whisperBound = false
            whisperService = null
        }
    }

    fun bindLlmService() {
        if (llmBound) return
        try {
            val intent = android.content.Intent().apply {
                setClassName(service.packageName, "space.manus.nacre.ai.LlmService")
            }
            val bound = service.bindService(intent, llmConnection, android.content.Context.BIND_AUTO_CREATE)
            llmBound = bound
            writeDiagnostic("bindLlmService: bound=$bound")
        } catch (e: Exception) {
            Log.e(TAG, "bindLlmService failed", e)
            writeDiagnostic("bindLlmService FAILED: ${e.message}")
        }
    }

    fun unbindLlmService() {
        if (llmBound) {
            service.unbindService(llmConnection)
            llmBound = false
            llmService = null
            llmServiceAny = null
            zenzService = null
            synchronized(zenzLoadLock) { zenzLoadState = ZENZ_LOAD_IDLE }
        }
    }

    private fun isBatteryOk(): Boolean {
        val bm = service.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return true
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level > BATTERY_THRESHOLD
    }

    /**
     * Stage 2: Try LLM refinement in background. Priority:
     *   1. Cloud chain (Qwen Max → Gemini Pro → DeepSeek V3) if any API key configured
     *   2. Local in-process LlmService (Qwen 1.5B) as offline fallback
     * First success wins; otherwise the quickClean output stays committed.
     */
    private fun tryLlmRefinement(quickCleanText: String, guarded: Boolean = false) {
        Thread {
            try {
                // Refinement is an LLM use — make sure the :llm process is on
                // its way even if this session reached refinement without a
                // prior predictNextViaLlm (no-op once bound).
                ensureLlmBound()
                // Active rewrite preset (整文/英訳/要約/敬語/箇条書き) — resolved
                // once per session by resolveSessionPreset() (a session
                // override, else the per-app auto mapping in auto mode,
                // else the standing manual choice).
                val instruction = sessionPresetInstruction
                    ?: space.manus.nacre.ai.RefinePresets.activeInstruction(service)

                // Step 1: cloud chain
                val cloudChain = space.manus.nacre.ai.cloud.RefinerFactory.build(service)
                for (refiner in cloudChain) {
                    val start = System.currentTimeMillis()
                    val result = refiner.refine(quickCleanText, instruction, CLOUD_REFINE_TIMEOUT_MS)
                    val elapsed = System.currentTimeMillis() - start
                    if (result.isSuccess) {
                        val refined = result.getOrNull()?.trim().orEmpty()
                        writeDiagnostic("tryLlmRefinement[cloud:${refiner.name}]: ${elapsed}ms, refined='${refined.take(80)}'")
                        applyRefinedIfDifferent(quickCleanText, refined, refiner.name, guarded)
                        return@Thread
                    } else {
                        writeDiagnostic("tryLlmRefinement[cloud:${refiner.name}]: FAIL (${elapsed}ms) ${result.exceptionOrNull()?.message}")
                    }
                }

                // Step 2: local AIDL Qwen fallback
                val svc = llmService
                if (svc == null || !svc.isModelLoaded) {
                    writeDiagnostic("tryLlmRefinement: no cloud key + local LLM not loaded, keeping quickClean")
                    return@Thread
                }
                if (svc.isGenerating) {
                    writeDiagnostic("tryLlmRefinement[local]: busy, skipping")
                    return@Thread
                }
                val start = System.currentTimeMillis()
                val refined = refineViaAidl(svc, quickCleanText, LLM_REFINE_TIMEOUT_MS, instruction)
                val elapsed = System.currentTimeMillis() - start
                writeDiagnostic("tryLlmRefinement[local]: ${elapsed}ms, refined='${refined?.take(80)}'")
                applyRefinedIfDifferent(quickCleanText, refined.orEmpty(), "local-qwen", guarded)
            } catch (e: Exception) {
                writeDiagnostic("tryLlmRefinement: error ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    /**
     * If [refined] differs from the committed quickClean result, delete-and-replace
     * on the UI thread.
     *
     * With [guarded] (incremental voice commits), the replacement happens only
     * when [isSessionTailIntact] proves the chunk is still the untouched tail
     * of the session text; otherwise the quickClean text stays. This is what
     * keeps refinement from visibly rewriting text the user already saw as
     * final — or worse, text they have since edited — while dictating.
     */
    private fun applyRefinedIfDifferent(
        committed: String,
        refined: String,
        source: String,
        guarded: Boolean = false,
    ) {
        if (refined.isBlank() || refined == committed) {
            writeDiagnostic("tryLlmRefinement[$source]: no improvement, keeping quickClean")
            return
        }
        Handler(Looper.getMainLooper()).post {
            val ic = service.currentInputConnection ?: return@post
            if (guarded) {
                if (!isSessionTailIntact(committed)) {
                    writeDiagnostic("tryLlmRefinement[$source]: replace SKIPPED (session tail edited or superseded)")
                    return@post
                }
                val preview = if (whisperPreviewInEditor) whisperPreviewDisplay else ""
                ic.deleteSurroundingText(committed.length + preview.length, 0)
                ic.commitText(refined, 1)
                if (preview.isNotEmpty()) {
                    ic.setComposingText(preview, 1)
                }
                val idx = voiceSessionChunks.lastIndexOf(committed)
                if (idx >= 0) voiceSessionChunks[idx] = refined
                if (committedInSession.toString().endsWith(committed)) {
                    committedInSession.setLength(committedInSession.length - committed.length)
                    committedInSession.append(refined)
                }
                writeDiagnostic("tryLlmRefinement[$source]: replaced quickClean with LLM result (guarded)")
                return@post
            }
            ic.deleteSurroundingText(committed.length, 0)
            ic.commitText(refined, 1)
            writeDiagnostic("tryLlmRefinement[$source]: replaced quickClean with LLM result")
        }
    }

    // ── Speak to Edit execution ────────────────────────────────────────

    /** End an edit session without applying anything (cancel). An edit
     *  already applying is dropped by [applyVoiceEditResult]'s guard. */
    private fun clearVoiceEditSession() {
        isVoiceEditMode = false
        editTargetText = null
        if (voiceEditUiState == VoiceEditUiState.RECORDING ||
            voiceEditUiState == VoiceEditUiState.APPLYING
        ) {
            voiceEditUiState = VoiceEditUiState.IDLE
        }
    }

    /**
     * Run one Speak to Edit request: [utterance] says how to rewrite the
     * captured selection, via the same cloud→local refiner chain as
     * dictation refinement but with the edit instruction. The selection
     * is replaced only in [applyVoiceEditResult] after verification;
     * every failure path keeps the original text and surfaces via
     * [voiceEditUiState] / [lastError] — the selection is never deleted
     * speculatively.
     */
    private fun runSpeakToEdit(utterance: String) {
        val original = editTargetText
        if (original.isNullOrEmpty() || utterance.isBlank()) {
            failVoiceEdit("指示を聞き取れませんでした")
            return
        }
        voiceEditUiState = VoiceEditUiState.APPLYING
        val instruction = VoiceEditPolicy.buildEditInstruction(utterance)
        writeDiagnostic(
            "runSpeakToEdit: utterance='${utterance.take(60)}', selection=${original.length} chars",
        )
        Thread {
            try {
                ensureLlmBound()
                var edited: String? = null
                var source = ""
                val cloudChain = space.manus.nacre.ai.cloud.RefinerFactory.build(service)
                for (refiner in cloudChain) {
                    val result = refiner.refine(original, instruction, CLOUD_REFINE_TIMEOUT_MS)
                    if (result.isSuccess) {
                        edited = result.getOrNull()?.trim()
                        source = refiner.name
                        break
                    }
                    writeDiagnostic(
                        "runSpeakToEdit[cloud:${refiner.name}]: FAIL ${result.exceptionOrNull()?.message}",
                    )
                }
                if (edited == null) {
                    val svc = llmService
                    if (svc != null && svc.isModelLoaded && !svc.isGenerating) {
                        edited = refineViaAidl(svc, original, LLM_REFINE_TIMEOUT_MS, instruction)
                        source = "local-qwen"
                    }
                }
                val resultText = edited
                val resultSource = source
                mainHandler.post { applyVoiceEditResult(original, resultText, resultSource) }
            } catch (e: Exception) {
                writeDiagnostic("runSpeakToEdit: error ${e.javaClass.simpleName}: ${e.message}")
                mainHandler.post { failVoiceEdit("編集処理でエラーが発生しました") }
            }
        }.start()
    }

    private fun applyVoiceEditResult(original: String, edited: String?, source: String) {
        // The session may have been cancelled or superseded while the
        // LLM worked — then the edit is dropped, the selection untouched.
        if (!isVoiceEditMode || editTargetText != original) {
            writeDiagnostic("applyVoiceEditResult[$source]: dropped (edit session no longer active)")
            return
        }
        if (edited.isNullOrBlank()) {
            failVoiceEdit("整文エンジンが利用できませんでした")
            return
        }
        val ic = service.currentInputConnection
        val currentSelection = try {
            ic?.getSelectedText(0)?.toString()
        } catch (_: Exception) {
            null
        }
        if (ic == null || !VoiceEditPolicy.isSelectionIntact(currentSelection, original)) {
            failVoiceEdit("選択範囲が変わったため、編集を中止しました")
            return
        }
        // commitText replaces the current selection — just verified to
        // still be exactly the captured original.
        ic.commitText(edited, 1)
        isVoiceEditMode = false
        editTargetText = null
        voiceEditUiState = VoiceEditUiState.DONE
        writeDiagnostic(
            "applyVoiceEditResult[$source]: selection replaced (${original.length} -> ${edited.length} chars)",
        )
    }

    private fun failVoiceEdit(reason: String) {
        writeDiagnostic("failVoiceEdit: $reason (original selection kept)")
        isVoiceEditMode = false
        editTargetText = null
        voiceEditUiState = VoiceEditUiState.FAILED
        lastError = "音声編集に失敗: $reason"
    }

    /**
     * Blocking wrapper around ILlmService.transform() for the dictation-cleanup
     * instruction. Returns the refined string, or null on timeout/error.
     */
    /**
     * Local-LLM next-word prediction. Given recent context text, returns up to 3
     * short continuation candidates, or null if the LLM is unavailable/busy/slow.
     * Used by InputEngine to augment the n-gram prediction bar. Yields to voice
     * refinement (skips when the model is generating).
     */
    fun predictNextViaLlm(context: String, timeoutMs: Long = 600): List<String>? {
        // First LLM use starts the :llm process in the background; this call
        // still returns null until the model is loaded, exactly as an
        // unbound call did before lazy binding.
        if (llmService == null) ensureLlmBound()
        val svc = llmService ?: return null
        if (context.isBlank()) return null
        try {
            if (svc.isGenerating) return null
        } catch (_: Exception) { return null }
        val raw = try {
            refineViaAidl(svc, context, timeoutMs, LlmPostProcessor.NEXT_WORD_PREDICTION_INSTRUCTION)
        } catch (_: Exception) { null } ?: return null
        return raw.split('、', '，', ',', '\n', '・', ' ', '　')
            .map { it.trim().trim('「', '」', '『', '』', '"', '・', '-', '。') }
            .filter { it.isNotBlank() && it.length <= 8 }
            .distinct()
            .take(3)
            .ifEmpty { null }
    }

    private fun refineViaAidl(
        svc: ILlmService,
        rawText: String,
        timeoutMs: Long,
        instruction: String = LlmPostProcessor.DICTATION_CLEANUP_INSTRUCTION,
    ): String? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)
        svc.transform(
            rawText,
            instruction,
            object : ILlmCallback.Stub() {
                override fun onResult(text: String) {
                    result.set(text.trim())
                    latch.countDown()
                }
                override fun onPartialResult(text: String) { /* streaming not used here */ }
                override fun onError(message: String) {
                    writeDiagnostic("refineViaAidl.onError: $message")
                    latch.countDown()
                }
            },
        )
        val finished = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            try { svc.cancelGeneration() } catch (_: Exception) {}
            return null
        }
        return result.get()
    }

    // ── zenz neural conversion ──────────────────────────────────────────

    /**
     * Lazy zenz load state machine: IDLE → LOADING → READY / FAILED.
     * Triggered on LlmService connect (warm-up) and on each conversion
     * request. A conversion that arrives while LOADING gets no zenz
     * candidate (engine candidates still show); later conversions use the
     * model. Every failure is recorded via [ZenzStatus] so the Settings
     * screen can show it — a dead zenz model must never fail silently.
     */
    private fun ensureZenzLoaded() {
        val svc = zenzService ?: return
        if (!ZenzStatus.isEnabled(service)) {
            ZenzStatus.record(service, ZenzStatus.State.DISABLED)
            zenzUiState = ZenzUiState.OFF
            return
        }
        // Desync guard: LlmService unloads the zenz model under memory
        // pressure (onTrimMemory >= RUNNING_LOW) without notifying us, so a
        // cached READY can be a lie — and previously every later conversion
        // then returned null (engine-only) for the rest of the session,
        // silently. Verify READY against the service; if the model is gone,
        // fall through and reload.
        if (zenzLoadState == ZENZ_LOAD_READY) {
            val loaded = try { svc.isZenzModelLoaded } catch (_: Exception) { false }
            if (loaded) return
            synchronized(zenzLoadLock) {
                if (zenzLoadState == ZENZ_LOAD_READY) zenzLoadState = ZENZ_LOAD_IDLE
            }
        }
        synchronized(zenzLoadLock) {
            if (zenzLoadState == ZENZ_LOAD_LOADING || zenzLoadState == ZENZ_LOAD_READY) return
            if (zenzLoadState == ZENZ_LOAD_FAILED &&
                System.currentTimeMillis() - zenzLoadFailedAt < ZENZ_LOAD_RETRY_MS
            ) return
            zenzLoadState = ZENZ_LOAD_LOADING
        }
        zenzUiState = ZenzUiState.LOADING
        Thread {
            try {
                if (svc.isZenzModelLoaded) {
                    synchronized(zenzLoadLock) { zenzLoadState = ZENZ_LOAD_READY }
                    ZenzStatus.record(service, ZenzStatus.State.READY)
                    zenzUiState = ZenzUiState.READY
                    return@Thread
                }
                val downloader = space.manus.nacre.ai.ModelDownloader(service)
                val path = downloader.getZenzModelPath()
                if (path == null) {
                    synchronized(zenzLoadLock) {
                        zenzLoadState = ZENZ_LOAD_FAILED
                        zenzLoadFailedAt = System.currentTimeMillis()
                    }
                    ZenzStatus.record(
                        service, ZenzStatus.State.NOT_DOWNLOADED,
                        "zenz-v3.2-small is not downloaded yet",
                    )
                    zenzUiState = ZenzUiState.NOT_DOWNLOADED
                    writeDiagnostic("zenz: model file not found — conversion stays engine-only")
                    return@Thread
                }
                ZenzStatus.record(service, ZenzStatus.State.LOADING, java.io.File(path).name)
                svc.loadZenzModel(path)
                val start = System.currentTimeMillis()
                while (!svc.isZenzModelLoaded && System.currentTimeMillis() - start < 30_000) {
                    Thread.sleep(300)
                }
                if (svc.isZenzModelLoaded) {
                    synchronized(zenzLoadLock) { zenzLoadState = ZENZ_LOAD_READY }
                    ZenzStatus.record(service, ZenzStatus.State.READY, java.io.File(path).name)
                    zenzUiState = ZenzUiState.READY
                    writeDiagnostic("zenz: model ready")
                } else {
                    val err = try { svc.zenzLoadError } catch (_: Exception) { "" }
                    synchronized(zenzLoadLock) {
                        zenzLoadState = ZENZ_LOAD_FAILED
                        zenzLoadFailedAt = System.currentTimeMillis()
                    }
                    ZenzStatus.record(
                        service, ZenzStatus.State.LOAD_ERROR,
                        err.ifBlank { "model did not become ready after 30s" },
                    )
                    zenzUiState = ZenzUiState.ERROR
                    writeDiagnostic("zenz: LOAD FAILED: $err")
                }
            } catch (e: Exception) {
                synchronized(zenzLoadLock) {
                    zenzLoadState = ZENZ_LOAD_FAILED
                    zenzLoadFailedAt = System.currentTimeMillis()
                }
                ZenzStatus.record(service, ZenzStatus.State.LOAD_ERROR, e.message ?: "load exception")
                zenzUiState = ZenzUiState.ERROR
                writeDiagnostic("zenz: load exception ${e.message}")
            }
        }.start()
    }

    /**
     * zenz neural conversion of a composing hiragana string. Blocking — call
     * from a background dispatcher. Returns the converted surface, or null
     * when zenz is disabled / not ready / busy / timed out / produced
     * nothing usable; null always means "engine candidates only", i.e.
     * exactly today's behavior. Failures are recorded in [ZenzStatus].
     * Never logs the input text (user content).
     */
    fun convertViaZenz(hiragana: String, timeoutMs: Long = ZENZ_TIMEOUT_MS): String? {
        if (hiragana.length < 2) return null
        // zenz converts Japanese kana; anything else (e.g. ASCII) is out of scope.
        if (hiragana.none { it in 'ぁ'..'ゖ' || it in 'ァ'..'ヶ' }) return null
        if (!ZenzStatus.isEnabled(service)) return null
        val svc = zenzService ?: run {
            // :llm binding is lazy (binds on first voice/LLM use). Kick it off
            // so later conversions can use zenz; this one stays engine-only.
            mainHandler.post { ensureLlmBound() }
            return null
        }
        ensureZenzLoaded()
        val ready = try { svc.isZenzModelLoaded } catch (_: Exception) { false }
        if (!ready) return null

        // Single-flight: typing-time and conversion-time requests can race;
        // the service would error the second one ("zenz already generating").
        // Skipping is free — the in-flight result serves the same composing.
        if (!zenzBusy.compareAndSet(false, true)) return null
        try {
            val katakana = KanaUtils.hiraganaToKatakana(hiragana)
            val latch = CountDownLatch(1)
            val result = AtomicReference<String?>(null)
            val error = AtomicReference<String?>(null)
            try {
                svc.zenzConvert(
                    katakana,
                    object : ILlmCallback.Stub() {
                        override fun onResult(text: String) {
                            result.set(text)
                            latch.countDown()
                        }
                        override fun onPartialResult(text: String) { /* not used */ }
                        override fun onError(message: String) {
                            error.set(message)
                            latch.countDown()
                        }
                    },
                )
            } catch (e: Exception) {
                ZenzStatus.record(service, ZenzStatus.State.GENERATION_ERROR, e.message ?: "IPC failed")
                return null
            }
            val finished = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                try { svc.cancelZenzGeneration() } catch (_: Exception) {}
                ZenzStatus.record(service, ZenzStatus.State.GENERATION_ERROR, "timeout after ${timeoutMs}ms")
                return null
            }
            error.get()?.let {
                ZenzStatus.record(service, ZenzStatus.State.GENERATION_ERROR, it)
                return null
            }
            val out = ZenzOutput.sanitize(result.get(), katakana, hiragana)
            if (out != null) zenzUiState = ZenzUiState.READY
            return out
        } finally {
            zenzBusy.set(false)
        }
    }

    /**
     * Write diagnostic info to a file readable from Termux.
     * File: /sdcard/Download/nacre-voice-debug.txt
     */
    private fun writeDiagnostic(message: String) {
        try {
            val file = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ),
                "nacre-voice-debug.txt"
            )
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            file.appendText("[$timestamp] $message\n")
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "VoiceInput"
        private const val BATTERY_THRESHOLD = 10
        private const val MAX_CONSECUTIVE_ERRORS = 15
        // LLM refinement timeout. Qwen 2.5 1.5B Q4_K_M on modern Android typically
        // finishes a ~100-char dictation in 1-3s; allow headroom before giving up.
        private const val LLM_REFINE_TIMEOUT_MS = 8_000L

        // Cloud refiner per-attempt timeout. Gemini Pro / Qwen Max usually finish
        // in 500-1500ms; allow a few seconds of tail before falling through to
        // the next provider in the chain.
        private const val CLOUD_REFINE_TIMEOUT_MS = 6_000
        // zenz conversion timeout: the 95M-param model converts a sentence in
        // well under a second on-device; 2.5s of tail keeps a slow device from
        // stalling the candidate bar (engine candidates are already shown).
        private const val ZENZ_TIMEOUT_MS = 2_500L
        private const val ZENZ_LOAD_RETRY_MS = 30_000L
        private const val ZENZ_LOAD_IDLE = 0
        private const val ZENZ_LOAD_LOADING = 1
        private const val ZENZ_LOAD_READY = 2
        private const val ZENZ_LOAD_FAILED = 3
        // No deferred commit timer — Typeless model: commit only on user stop action
        private val STT_BIASING_TERMS = listOf(
            "git", "git rebase", "git merge", "git pull", "git push", "GitHub",
            "npm", "npm install", "npm install React", "pnpm", "yarn",
            "React", "TypeScript", "JavaScript", "Kotlin", "Android",
            "Gradle", "Compose", "Jetpack Compose", "API", "JSON", "SDK",
            "Codex", "Nacre", "Shelly", "Gemma", "Whisper", "SenseVoice",
            "MCP", "LLM", "ADB", "logcat",
        )
        private val RECOVERABLE_ERRORS = setOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        )
    }
}
