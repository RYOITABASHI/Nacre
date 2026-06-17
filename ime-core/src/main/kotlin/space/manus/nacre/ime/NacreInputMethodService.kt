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

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        // Initialize all components
        configRepository = ConfigRepository(this)
        currentTheme = space.manus.nacre.config.ThemeProvider.loadSelectedTheme(this)
        inputEngine = InputEngine(this)
        feedbackManager = FeedbackManager(this)
        clipboardManager = ClipboardManager(this)
        macroEngine = MacroEngine(this)
        snippetEngine = SnippetEngine(this)
        autoConvertEngine = AutoConvertEngine(this)
        foldableDetector = FoldableDetector(this)
        layoutSelector = LayoutSelector(foldableDetector)
        physicalKeyboardDetector = PhysicalKeyboardDetector(this)
        voiceInputManager = VoiceInputManager(this)
        voiceInputManager.bindWhisperService()
        voiceInputManager.bindLlmService()
        keyLighting = KeyLighting(this)

        clipboardManager.startListening()
        foldableDetector.startHingeAngleListening()

        // Check LLM server availability (non-blocking)
        inputEngine.llmReranker.checkServer()

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

            // Load KenLM model: prefer full 5-gram (sideloaded), fall back to bundled compact model
            try {
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
        inputEngine.onStartInput(info)
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
        // SPEC: 300-500ms delay for foldable screen size stabilization
        serviceScope.launch {
            delay(350L)
            inputViewContainer?.let { container ->
                (container.parent as? ViewGroup)?.removeView(container)
                container.removeAllViews()
            }
            inputViewContainer = null
            setInputView(onCreateInputView())
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
