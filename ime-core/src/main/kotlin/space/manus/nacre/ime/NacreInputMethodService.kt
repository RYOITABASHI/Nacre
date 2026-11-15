package space.manus.nacre.ime

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import space.manus.nacre.config.ConfigRepository
import space.manus.nacre.ime.feedback.FeedbackManager
import space.manus.nacre.ime.foldable.FoldableDetector
import space.manus.nacre.ime.foldable.LayoutSelector
import space.manus.nacre.ime.input.AutoConvertEngine
import space.manus.nacre.ime.input.ClipboardManager
import space.manus.nacre.ime.input.InputEngine
import space.manus.nacre.ime.input.LayerManager
import space.manus.nacre.ime.input.MacroEngine
import space.manus.nacre.ime.input.NacreDictionary
import space.manus.nacre.ai.KenLmScorer
import space.manus.nacre.ime.input.PhysicalKeyboardDetector
import space.manus.nacre.ime.input.SnippetEngine
import space.manus.nacre.ime.input.VoiceInputManager
import space.manus.nacre.ime.keyboard.KeyLighting
import space.manus.nacre.ime.keyboard.KeyboardScreen
import space.manus.nacre.ime.pointer.FlexPointerBridge
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NacreInputMethodService :
    InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private var inputViewContainer: FrameLayout? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // --- Core components ---
    lateinit var inputEngine: InputEngine
        private set
    val layerManager = LayerManager()

    // --- Phase 2+ components ---
    lateinit var feedbackManager: FeedbackManager
        private set
    lateinit var clipboardManager: ClipboardManager
        private set
    lateinit var macroEngine: MacroEngine
        private set
    lateinit var snippetEngine: SnippetEngine
        private set
    lateinit var autoConvertEngine: AutoConvertEngine
        private set
    lateinit var configRepository: ConfigRepository
        private set
    lateinit var foldableDetector: FoldableDetector
        private set
    lateinit var layoutSelector: LayoutSelector
        private set
    lateinit var physicalKeyboardDetector: PhysicalKeyboardDetector
        private set
    lateinit var voiceInputManager: VoiceInputManager
        private set
    lateinit var keyLighting: KeyLighting
        private set
    lateinit var currentTheme: space.manus.nacre.config.NacreTheme
        private set

    /**
     * Bumped to make [KeyboardScreen] re-pick the layout. The input view is cached
     * (onCreateInputView returns the existing view) and never recomposes on its own,
     * so a layout/settings change — e.g. toggling "12キー入力" — stays invisible until
     * this epoch advances. Bumped on every keyboard show (onStartInputView) and on
     * every configuration change (fold/unfold, rotation — see onConfigurationChanged).
     */
    val layoutEpoch = androidx.compose.runtime.mutableStateOf(0)

    /** Pending post-fold layout re-pick; see [onConfigurationChanged]. */
    private var foldSettleJob: Job? = null

    companion object {
        /**
         * Window-metrics settle window after a configuration change before the
         * layout is re-picked a second time. Same 350ms the old code waited
         * before recreating the view — now spent with the keyboard on screen.
         */
        private const val FOLD_SETTLE_MS = 350L
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        // Initialize all components
        configRepository = ConfigRepository(this)
        currentTheme = space.manus.nacre.config.ThemeProvider.loadSelectedTheme(this)
        inputEngine = InputEngine(this)
        feedbackManager = FeedbackManager(this)
        // The password-field provider keeps clips copied while a password
        // field is focused out of the clipboard history (privacy).
        clipboardManager = ClipboardManager(this) { inputEngine.isPasswordField }
        macroEngine = MacroEngine(this)
        snippetEngine = SnippetEngine(this)
        autoConvertEngine = AutoConvertEngine(this)
        foldableDetector = FoldableDetector(this)
        layoutSelector = LayoutSelector(foldableDetector)
        physicalKeyboardDetector = PhysicalKeyboardDetector(this)
        voiceInputManager = VoiceInputManager(this)
        // NOTE: the :whisper / :llm services are intentionally NOT bound here.
        // Binding with BIND_AUTO_CREATE would keep two extra processes (and a
        // ~1GB local LLM) resident for the IME's whole lifetime even when the
        // user never uses voice or LLM features. VoiceInputManager now binds
        // lazily on first use (startListening / predictNextViaLlm /
        // tryLlmRefinement) and stays bound afterwards — see ensure*Bound().
        keyLighting = KeyLighting(this)

        clipboardManager.startListening()
        // NOTE: the hinge-angle sensor is intentionally NOT registered.
        // FoldableDetector.hingeAngle has no consumer in the app (LayoutSelector
        // decides purely from window width; isTableTopMode() has no callers),
        // so the old onCreate registration kept the sensor powered for the
        // IME process's entire lifetime — including while the keyboard was
        // hidden — in exchange for a value nothing ever read. If a future
        // feature consumes hinge angle, register then (and unregister on hide).

        // When native Mozc is the active conversion engine it owns ranking outright, so the
        // Kotlin-engine helpers are dead weight: the local-LLM reranker (skip its server probe)
        // and the KenLM 5-gram rescorer (skip the ~560MB load below). Frees ~1GB+ of resident
        // memory on Mozc-enabled devices; the Kotlin path (Mozc OFF) is unchanged.
        val mozcEnabled = getSharedPreferences("nacre_mozc", MODE_PRIVATE).getBoolean("enabled", false)

        // Check LLM server availability (non-blocking)
        if (!mozcEnabled) inputEngine.llmReranker.checkServer()

        // Personal default: keep compact KenLM and the default local LLM
        // provisioning in the background. Actual model loading stays below and
        // never blocks IME startup.
        space.manus.nacre.ai.ModelDownloader(this).ensureDefaultModelsDownloaded()

        // Load dictionary in background at low priority, publish on Main
        serviceScope.launch(Dispatchers.IO) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val dict = NacreDictionary(this@NacreInputMethodService)
            try {
                dict.load()
            } catch (e: Exception) {
                android.util.Log.e("NacreIME", "Dictionary load FAILED", e)
                withContext(Dispatchers.Main) {
                    inputEngine.debugInfo = "DICT FAIL: ${e.message}"
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                inputEngine.dictionary = dict
                inputEngine.dictionaryLoaded = true
                inputEngine.refreshPredictionsIfNeeded()
            }

            // Load KenLM model: prefer full 5-gram (sideloaded), fall back to bundled compact model.
            // Skipped when native Mozc is active — Mozc does its own scoring, so loading the
            // 560MB 5-gram into the IME process would just sit there wasting RAM.
            if (mozcEnabled) {
                android.util.Log.i("NacreIME", "Mozc native active — skipping KenLM load (saves ~560MB)")
            } else try {
                val modelsDir = java.io.File(filesDir, "models")
                modelsDir.mkdirs()
                val fullModel = java.io.File(modelsDir, "japanese-5gram.klm")
                val compactModel = java.io.File(modelsDir, "japanese-compact.klm")

                // Search for either model anywhere on device (Download, Documents, etc.)
                // and copy into filesDir so subsequent loads are fast and deterministic.
                val downloader = space.manus.nacre.ai.ModelDownloader(this@NacreInputMethodService)
                if (!fullModel.exists()) {
                    val foundPath = downloader.getKenLmModelPath()
                    if (foundPath != null && foundPath != fullModel.absolutePath) {
                        val extSource = java.io.File(foundPath)
                        android.util.Log.i("NacreIME", "Copying KenLM 5-gram from ${extSource.absolutePath}...")
                        extSource.copyTo(fullModel, overwrite = true)
                        android.util.Log.i("NacreIME", "KenLM 5-gram copied (${fullModel.length() / 1024 / 1024}MB)")
                    }
                }
                if (!fullModel.exists() && !compactModel.exists()) {
                    val foundPath = downloader.getCompactKenLmModelPath()
                    if (foundPath != null && foundPath != compactModel.absolutePath) {
                        val extSource = java.io.File(foundPath)
                        android.util.Log.i("NacreIME", "Copying compact KenLM from ${extSource.absolutePath}...")
                        extSource.copyTo(compactModel, overwrite = true)
                        android.util.Log.i("NacreIME", "Compact KenLM copied (${compactModel.length() / 1024 / 1024}MB)")
                    }
                }

                // Load the best available model: full > compact.
                // Neither is bundled — both come from sideload (full 5-gram) or
                // ModelDownloader.downloadCompactKenLm() (compact 3-gram).
                val modelToLoad = when {
                    fullModel.exists() -> fullModel
                    compactModel.exists() -> compactModel
                    else -> null
                }
                if (modelToLoad != null) {
                    val scorer = KenLmScorer()
                    if (scorer.load(modelToLoad.absolutePath)) {
                        dict.kenLmScorer = scorer
                        android.util.Log.i("NacreIME", "KenLM loaded: ${modelToLoad.name} (${modelToLoad.length() / 1024 / 1024}MB)")
                    }
                } else {
                    downloader.ensureDefaultModelsDownloaded(downloadLlm = false)
                    android.util.Log.i("NacreIME", "No KenLM model available yet; compact KenLM download requested")
                }
            } catch (e: Exception) {
                android.util.Log.w("NacreIME", "KenLM load failed", e)
            }
        }
    }

    override fun onCreateInputView(): View {
        // Cache — don't recreate every time
        inputViewContainer?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }

        // Advance lifecycle so ComposeView can start composition.
        if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        return try {
            val service = this

            // FrameLayout wrapper that stamps ViewTree owners on the ENTIRE
            // parent chain (including the IME framework's parentPanel) before
            // the ComposeView gets attached. ComposeView is final so we can't
            // override its onAttachedToWindow — instead we use
            // addOnAttachStateChangeListener to stamp owners right before
            // ComposeView looks them up.
            val container = object : FrameLayout(service) {
                override fun onAttachedToWindow() {
                    // Stamp owners on ourselves
                    setViewTreeLifecycleOwner(service)
                    setViewTreeViewModelStoreOwner(service)
                    setViewTreeSavedStateRegistryOwner(service)
                    // Stamp on every ancestor in the parent chain
                    var p = parent
                    while (p is View) {
                        p.setViewTreeLifecycleOwner(service)
                        p.setViewTreeViewModelStoreOwner(service)
                        p.setViewTreeSavedStateRegistryOwner(service)
                        p = (p as View).parent
                    }
                    super.onAttachedToWindow()
                }
            }
            container.setViewTreeLifecycleOwner(this)
            container.setViewTreeViewModelStoreOwner(this)
            container.setViewTreeSavedStateRegistryOwner(this)

            val composeView = ComposeView(service)
            composeView.setViewTreeLifecycleOwner(this)
            composeView.setViewTreeViewModelStoreOwner(this)
            composeView.setViewTreeSavedStateRegistryOwner(this)

            // Stamp owners on the parent chain again when ComposeView attaches,
            // in case the view hierarchy changed between container attach and
            // ComposeView attach.
            composeView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.setViewTreeLifecycleOwner(service)
                    v.setViewTreeViewModelStoreOwner(service)
                    v.setViewTreeSavedStateRegistryOwner(service)
                    var p = v.parent
                    while (p is View) {
                        p.setViewTreeLifecycleOwner(service)
                        p.setViewTreeViewModelStoreOwner(service)
                        p.setViewTreeSavedStateRegistryOwner(service)
                        p = (p as View).parent
                    }
                }
                override fun onViewDetachedFromWindow(v: View) {}
            })

            composeView.setContent {
                KeyboardScreen(service = service)
            }

            container.addView(composeView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            ))

            inputViewContainer = container
            container
        } catch (e: Exception) {
            Log.e("NacreIME", "Failed to create input view", e)
            android.widget.TextView(this).apply {
                text = "Nacre: keyboard load error"
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Cancel voice input when switching to a new text field — the InputConnection
        // changes, so composing text from the previous field would be lost.
        if (voiceInputManager.isListening && !restarting) {
            voiceInputManager.cancel()
        }
        // Reload theme & config each time keyboard appears (picks up settings changes)
        currentTheme = space.manus.nacre.config.ThemeProvider.loadSelectedTheme(this)
        // The input view is cached, so force KeyboardScreen to re-pick its layout — this
        // is what makes a "12キー入力" / sub-display toggle apply on the next keyboard open.
        layoutEpoch.value++
        // Pick up 単語登録 edits made in the Settings screen (same prefs, separate object).
        val udPrefs = getSharedPreferences("nacre_user_dict", android.content.Context.MODE_PRIVATE)
        if (udPrefs.getBoolean("dirty", false)) {
            inputEngine.reloadUserDictionary()
            udPrefs.edit().putBoolean("dirty", false).apply()
        }
        // zenz warm-up: the :llm binding is lazy (first voice/LLM use), which
        // left the neural converter unloaded until well into a typing session
        // — the first conversions were always engine-only and the load only
        // started when something else happened to bind the service. Bind at
        // keyboard show instead (idempotent; stays bound for the IME's
        // lifetime, and rebinds after the :llm process dies). The connection
        // callback checks the zenz toggle + model presence before loading;
        // the :whisper/ASR binding stays lazy as before.
        voiceInputManager.ensureLlmBound()
        inputEngine.onStartInput(info)
    }

    /**
     * Forward editor-reported selection changes to the engine. This is
     * the only channel that observes cursor moves the IME did not make
     * itself (the user tapping into older text, the app repositioning
     * the cursor); the post-hoc ⇧ tracker depends on it to drop its
     * target instead of rewriting foreign text that merely reads the
     * same. Our own commits/rewrites report back as a collapsed cursor
     * at the tracked position and are recognized as such by the tracker.
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd,
            candidatesStart, candidatesEnd,
        )
        if (::inputEngine.isInitialized) {
            inputEngine.onSelectionUpdate(newSelStart, newSelEnd)
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        // Stop voice input when keyboard is hidden (e.g., user switches apps).
        // Without this, Whisper continues recording in the background with no UI,
        // and the InputConnection becomes stale so composing text cannot be updated.
        if (voiceInputManager.isListening) {
            voiceInputManager.cancel()
        }
        FlexPointerBridge.setImePointerVisible(false)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Fold/unfold and rotation land here. The input view is NOT recreated:
        // the old behaviour waited 350ms ("foldable screen size stabilization"),
        // then detached the view, emptied it and built a brand-new composition.
        // That blanked the keyboard on every fold and destroyed every piece of
        // Compose-held state — the flick pad's mode (かな/ABC/123), the candidate
        // bar's scroll position and expand-all state, and any open panel
        // (clipboard / emoji / symbols / command palette / flex pointer).
        // None of it has to die: InputEngine (composing text, candidates,
        // conversion state) is service-scoped and untouched by view lifecycle,
        // and the layout re-pick only needs the epoch bump KeyboardScreen
        // already honours on every keyboard show. The cached view stays
        // attached and relayouts against the new window size in place, so the
        // layout still switches (cover 12-key <-> main QWERTY) — just without
        // the blank gap and the state loss.
        currentTheme = space.manus.nacre.config.ThemeProvider.loadSelectedTheme(this)
        layoutEpoch.value++
        // Window metrics can lag the fold animation, so re-pick once more
        // after the old stabilization window. This is a pure recomposition
        // trigger — the view is never detached, and if the layout is already
        // correct the second pick changes nothing on screen. A further
        // configuration change supersedes the pending re-pick.
        foldSettleJob?.cancel()
        foldSettleJob = serviceScope.launch {
            delay(FOLD_SETTLE_MS)
            layoutEpoch.value++
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        inputEngine.destroy()
        clipboardManager.stopListening()
        foldableDetector.stopHingeAngleListening()
        (inputEngine.dictionary as? NacreDictionary)?.flushPendingSave()
        macroEngine.saveMacros(this)
        snippetEngine.saveSnippets(this)
        autoConvertEngine.saveRules(this)
        feedbackManager.release()
        voiceInputManager.unbindWhisperService()
        voiceInputManager.unbindLlmService()
        voiceInputManager.release()
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        inputViewContainer = null
        serviceScope.cancel()
        super.onDestroy()
    }
}
