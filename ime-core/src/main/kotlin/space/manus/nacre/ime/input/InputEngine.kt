package space.manus.nacre.ime.input

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.text.InputType
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.manus.nacre.config.KeyAction
import space.manus.nacre.config.KeyDef
import space.manus.nacre.ime.NacreInputMethodService

private val CTRL_PATTERN = Regex("^C-([a-zA-Z])$")
private val DIRECT_TOKEN_CONTEXT = Regex("(^|[\\s\\n\\t])[@#/][A-Za-z0-9._-]*$")

class InputEngine(private val service: NacreInputMethodService) {

    private val scope = MainScope()
    private var editorInfo: EditorInfo? = null
    private val japaneseEngine = JapaneseEngine()
    private var composingText: String = ""
    private var composingFlickKana: String = ""
    private var predictionJob: Job? = null

    // LLM-based candidate reranking (optional, async)
    val llmReranker = LlmReranker(service)

    // Observable composing kana for CandidateBar display
    var composingKana by mutableStateOf("")
        private set

    // English composition state
    private var englishComposing: String = ""
    private var englishPredictionJob: Job? = null

    // Conversion state (observable by Compose)
    var candidates = mutableStateListOf<ConversionCandidate>()
        private set
    var selectedCandidateIndex by mutableStateOf(-1)
        private set
    var isConverting by mutableStateOf(false)
        private set

    // Segment boundary adjustment (文節区切り修正)
    // During conversion, segmentBoundary = number of kana chars in current first segment
    private var segmentBoundary: Int = 0
    private var fullKana: String = "" // Full kana being converted

    // Password field state (observable by Compose for CandidateBar hiding)
    var isPasswordField by mutableStateOf(false)
        private set

    // Dictionary reference (set from IO thread after loading)
    @Volatile
    var dictionary: DictionaryProvider? = null

    // Debug: observable dictionary load state for UI
    var dictionaryLoaded by mutableStateOf(false)

    // Debug: last prediction info
    var debugInfo by mutableStateOf("")

    /**
     * Called when dictionary finishes loading. If there's active composing text,
     * regenerate predictions so candidates appear retroactively.
     */
    fun refreshPredictionsIfNeeded() {
        if (composingText.isNotEmpty() && !isConverting) {
            val kana = japaneseEngine.romajiToHiragana(composingText)
            // Log.d removed: never log user input text (kana) even in debug
            updatePredictions(kana)
        }
    }

    fun onStartInput(info: EditorInfo?) {
        editorInfo = info
        composingText = ""
        composingFlickKana = ""
        composingKana = ""
        japaneseEngine.reset()
        clearCandidates()

        // EditorInfo handling: disable Japanese for password/number fields
        if (info != null) {
            val rawInputType = info.inputType
            val inputType = rawInputType and InputType.TYPE_MASK_CLASS
            val variation = rawInputType and InputType.TYPE_MASK_VARIATION

            // Only check password variation when the class is actually TYPE_CLASS_TEXT
            // Termux uses TYPE_CLASS_TEXT with TYPE_TEXT_VARIATION_NORMAL (0x0) or TYPE_NULL
            val isTextClass = inputType == InputType.TYPE_CLASS_TEXT
            val isPassword = isTextClass && (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )
            val isNumber = inputType == InputType.TYPE_CLASS_NUMBER ||
                inputType == InputType.TYPE_CLASS_PHONE
            val isEmail = isTextClass && (
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
            )
            val isUri = isTextClass && variation == InputType.TYPE_TEXT_VARIATION_URI

            val noSuggestions = rawInputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0

            // debugInfo removed — no longer shown in candidate bar

            // SPEC: Password field — disable prediction, history, AI, hide candidate bar
            isPasswordField = isPassword

            if (isPassword || isNumber || isEmail || isUri) {
                if (service.layerManager.isJapanese) {
                    service.layerManager.toggleJapanese()
                }
            }
            // Note: noSuggestions flag alone does NOT disable Japanese
            // (e.g. Termux uses TYPE_TEXT_FLAG_NO_SUGGESTIONS but user still wants kana)
        } else {
            isPasswordField = false
        }
    }

    fun processKey(keyDef: KeyDef) {
        processAction(keyDef.action)
    }

    fun processSwipe(keyDef: KeyDef, direction: SwipeDirection) {
        val text = when (direction) {
            SwipeDirection.Up -> keyDef.swipeUp
            SwipeDirection.Down -> keyDef.swipeDown
            SwipeDirection.Left -> keyDef.swipeLeft
            SwipeDirection.Right -> keyDef.swipeRight
        } ?: return

        // Parse Emacs-style "C-x" notation as Ctrl+key
        val ctrlMatch = CTRL_PATTERN.matchEntire(text)
        if (ctrlMatch != null) {
            val letter = ctrlMatch.groupValues[1].uppercase()
            val keyCode = KeyEvent.keyCodeFromString("KEYCODE_$letter")
            if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                processAction(KeyAction.KeyCode(keyCode, ctrl = true))
                return
            }
        }

        // Space swipe-down = Toggle Japanese (あ indicator)
        if (text == "\u3042" && keyDef.action is KeyAction.Space) {
            processAction(KeyAction.ToggleJapanese)
            return
        }

        // "ToggleJa" swipe = Toggle Japanese input
        if (text == "ToggleJa") {
            processAction(KeyAction.ToggleJapanese)
            return
        }

        // "GL" swipe = Switch IME
        if (text == "GL") {
            processAction(KeyAction.SwitchIme)
            return
        }

        // "Tab" swipe = send Tab key
        if (text == "Tab") {
            processAction(KeyAction.Tab)
            return
        }

        // BS swipe-left = word delete
        if (text == "\u232Bw" && keyDef.action is KeyAction.Backspace) {
            val ic = service.currentInputConnection ?: return
            // Delete word to the left: find last word boundary
            val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
            val fullText = extracted?.text?.toString() ?: ""
            val pos = extracted?.selectionStart ?: 0
            if (pos > 0) {
                val before = fullText.substring(0, pos)
                val trimmed = before.trimEnd()
                val lastSpace = trimmed.lastIndexOf(' ')
                val deleteCount = pos - if (lastSpace >= 0) lastSpace + 1 else 0
                if (deleteCount > 0) {
                    ic.deleteSurroundingText(deleteCount, 0)
                }
            }
            return
        }

        // Period key swipe-right = Shift toggle (⇧)
        if (text == "\u21E7") {
            processAction(KeyAction.Shift)
            return
        }

        // In Japanese mode, route "-" and "ー" through Japanese input
        // so they join the composing text as long vowel mark
        if (service.layerManager.isJapanese && (text == "-" || text == "ー")) {
            val ic = service.currentInputConnection ?: return
            if (text == "ー") {
                // Direct kana: append to composing text as-is
                composingText += "-"  // store as romaji "-" so engine converts to ー
                val kana = japaneseEngine.romajiToHiragana(composingText)
                composingKana = kana
                ic.setComposingText(kana, 1)
                updatePredictions(kana)
            } else {
                processJapaneseInput(text, ic)
            }
            return
        }

        // Commit any active composition before inserting swipe text
        val ic = service.currentInputConnection
        if (ic != null && composingText.isNotEmpty()) {
            if (isConverting) {
                commitSelectedCandidate(ic)
            } else {
                finishComposing(ic)
            }
        }

        // Japanese mode: auto-convert punctuation to fullwidth
        if (service.layerManager.isJapanese) {
            val jaSwipe = when (text) {
                "?" -> { commitTextWithSymbol(ic ?: service.currentInputConnection ?: return, "？", listOf("？", "?", "⁉", "❓")); return }
                "!" -> { commitTextWithSymbol(ic ?: service.currentInputConnection ?: return, "！", listOf("！", "!", "‼", "❗")); return }
                else -> text
            }
            commitText(jaSwipe)
        } else {
            commitText(text)
        }
    }

    fun processAction(action: KeyAction) {
        val ic = service.currentInputConnection ?: return

        when (action) {
            is KeyAction.Text -> {
                // Dismiss symbol candidates (、。？！) when typing next character
                if (symbolReplace != null) {
                    symbolReplace = null
                    candidates.clear()
                    selectedCandidateIndex = -1
                }
                if (isConverting) {
                    // If we're showing candidates, commit selected and start fresh
                    commitSelectedCandidate(ic)
                }
                if (shouldCommitDirectTokenText(action.text, ic)) {
                    commitDirectTokenText(action.text, ic)
                    return
                }
                if (service.layerManager.isJapanese) {
                    // Japanese punctuation/symbols: commit composing first, then insert directly
                    val jaText = when (action.text) {
                        "," -> { if (composingText.isNotEmpty()) finishComposing(ic); ic.commitText("、", 1); showSymbolCandidates("、", listOf("、", "，", "‥")); return }
                        "." -> { if (composingText.isNotEmpty()) finishComposing(ic); ic.commitText("。", 1); showSymbolCandidates("。", listOf("。", "…", "‥", "・", ".")); return }
                        "?", "？" -> { if (composingText.isNotEmpty()) finishComposing(ic); ic.commitText("？", 1); showSymbolCandidates("？", listOf("？", "?", "⁉", "❓")); return }
                        "!", "！" -> { if (composingText.isNotEmpty()) finishComposing(ic); ic.commitText("！", 1); showSymbolCandidates("！", listOf("！", "!", "‼", "❗")); return }
                        "(" -> { if (composingText.isNotEmpty()) finishComposing(ic); ic.commitText("（", 1); showSymbolCandidates("（", listOf("（", "「", "『", "【", "〈", "《", "[", "(")); return }
                        ")" -> { if (composingText.isNotEmpty()) finishComposing(ic); ic.commitText("）", 1); showSymbolCandidates("）", listOf("）", "」", "』", "】", "〉", "》", "]", ")")); return }
                        "[" -> { if (composingText.isNotEmpty()) finishComposing(ic); ic.commitText("「", 1); showSymbolCandidates("「", listOf("「", "『", "【", "〈", "《", "（", "[")); return }
                        "]" -> { if (composingText.isNotEmpty()) finishComposing(ic); ic.commitText("」", 1); showSymbolCandidates("」", listOf("」", "』", "】", "〉", "》", "）", "]")); return }
                        "ー" -> {
                            // Long vowel mark: append to composing text as "-"
                            composingText += "-"
                            val kana = japaneseEngine.romajiToHiragana(composingText)
                            composingKana = kana
                            ic.setComposingText(kana, 1)
                            updatePredictions(kana)
                            return
                        }
                        else -> action.text.lowercase()
                    }
                    processJapaneseInput(jaText, ic)
                } else {
                    val ch = if (service.layerManager.isShifted) {
                        action.text.uppercase()
                    } else {
                        action.text
                    }
                    // English composition with prediction
                    if (ch.first().isLetter()) {
                        englishComposing += ch
                        ic.setComposingText(englishComposing, 1)
                        updateEnglishPredictions(englishComposing)
                    } else {
                        // Non-letter (space, punctuation, digit): commit composing + the char
                        if (englishComposing.isNotEmpty()) {
                            // If a candidate is selected, commit that; otherwise commit raw text
                            if (selectedCandidateIndex >= 0 && candidates.isNotEmpty()) {
                                val selected = candidates[selectedCandidateIndex]
                                ic.commitText(selected.surface, 1)
                                dictionary?.recordEnglishSelection(selected.surface)
                            } else {
                                ic.commitText(englishComposing, 1)
                                dictionary?.recordEnglishSelection(englishComposing)
                            }
                            englishComposing = ""
                            candidates.clear()
                            selectedCandidateIndex = -1
                        }
                        ic.commitText(ch, 1)
                    }
                    if (service.layerManager.isShifted) {
                        service.layerManager.toggleShift()
                    }
                }
            }

            is KeyAction.Backspace -> {
                if (isConverting) {
                    // Cancel conversion, go back to kana
                    cancelConversion(ic)
                } else if (composingFlickKana.isNotEmpty()) {
                    // Flick mode backspace
                    processFlickBackspace()
                } else if (composingText.isNotEmpty()) {
                    // Remove one kana unit worth of romaji
                    composingText = removeLastKanaUnit(composingText)
                    if (composingText.isEmpty()) {
                        composingKana = ""
                        ic.finishComposingText()
                        clearCandidates()
                    } else {
                        val kana = japaneseEngine.romajiToHiragana(composingText)
                        composingKana = kana
                        ic.setComposingText(kana, 1)
                        updatePredictions(kana)
                    }
                } else if (englishComposing.isNotEmpty()) {
                    // English backspace: remove last char
                    englishComposing = englishComposing.dropLast(1)
                    if (englishComposing.isEmpty()) {
                        ic.finishComposingText()
                        clearCandidates()
                    } else {
                        ic.setComposingText(englishComposing, 1)
                        updateEnglishPredictions(englishComposing)
                    }
                } else {
                    ic.deleteSurroundingText(1, 0)
                }
            }

            is KeyAction.Enter -> {
                if (symbolReplace != null) {
                    clearCandidates()
                } else if (isConverting) {
                    commitSelectedCandidate(ic)
                } else if (composingFlickKana.isNotEmpty()) {
                    // Flick mode: commit kana as-is
                    ic.commitText(composingFlickKana, 1)
                    recordJapanesePhrase(composingFlickKana, composingFlickKana)
                    composingFlickKana = ""
                    composingKana = ""
                    clearCandidates()
                } else if (composingText.isNotEmpty()) {
                    finishComposing(ic)
                } else if (englishComposing.isNotEmpty()) {
                    // Commit English composing text as-is
                    ic.commitText(englishComposing, 1)
                    dictionary?.recordEnglishSelection(englishComposing)
                    englishComposing = ""
                    clearCandidates()
                } else {
                    if (service.layerManager.currentLayer != Layer.Base) {
                        performEditorActionOrEnter(ic)
                        service.layerManager.resetToBase()
                    } else {
                        // Chat/note first behavior: avoid accidental send in Slack,
                        // Notion, web CLIs, and similar multiline text boxes.
                        commitNewline(ic)
                    }
                }
            }

            is KeyAction.Space -> {
                // Fn + Space → command palette
                if (service.layerManager.currentLayer != space.manus.nacre.ime.input.Layer.Base) {
                    service.layerManager.requestCommandPalette()
                    service.layerManager.resetToBase()
                    return
                }
                // Flick mode: space triggers conversion or commits + space
                if (composingFlickKana.isNotEmpty()) {
                    if (isConverting) {
                        nextCandidate(ic)
                    } else {
                        startFlickConversion(ic)
                    }
                } else if (composingText.isNotEmpty()) {
                    if (isConverting) {
                        // Cycle to next candidate
                        nextCandidate(ic)
                    } else {
                        // Start conversion
                        startConversion(ic)
                    }
                } else if (englishComposing.isNotEmpty()) {
                    // English: space commits the top candidate (or raw text) + space
                    if (candidates.isNotEmpty()) {
                        val best = if (selectedCandidateIndex >= 0) candidates[selectedCandidateIndex]
                                   else candidates[0]
                        ic.commitText(best.surface + " ", 1)
                        dictionary?.recordEnglishSelection(best.surface)
                    } else {
                        ic.commitText(englishComposing + " ", 1)
                        dictionary?.recordEnglishSelection(englishComposing)
                    }
                    englishComposing = ""
                    clearCandidates()
                } else {
                    commitText(" ")
                }
            }

            is KeyAction.Tab -> {
                // If a snippet session is active, advance to next tab stop
                if (service.snippetEngine.hasActiveSession) {
                    service.snippetEngine.nextTabStop(ic)
                } else {
                    sendKeyEvent(KeyEvent.KEYCODE_TAB)
                }
            }
            is KeyAction.Escape -> {
                if (isConverting) {
                    cancelConversion(ic)
                } else if (composingFlickKana.isNotEmpty()) {
                    composingFlickKana = ""
                    composingKana = ""
                    ic.finishComposingText()
                    clearCandidates()
                } else if (composingText.isNotEmpty()) {
                    composingText = ""
                    composingKana = ""
                    ic.finishComposingText()
                    clearCandidates()
                } else {
                    sendKeyEvent(KeyEvent.KEYCODE_ESCAPE)
                }
            }

            is KeyAction.Shift -> service.layerManager.toggleShift()
            is KeyAction.Fn -> service.layerManager.toggleFn()
            is KeyAction.FnPage2 -> service.layerManager.toggleFn2()

            is KeyAction.SwitchIme -> {
                @Suppress("DEPRECATION")
                service.switchToNextInputMethod(false)
            }

            is KeyAction.ToggleJapanese -> {
                if (isConverting) commitSelectedCandidate(ic)
                if (composingFlickKana.isNotEmpty()) {
                    ic.commitText(composingFlickKana, 1)
                    recordJapanesePhrase(composingFlickKana, composingFlickKana)
                    composingFlickKana = ""
                    composingKana = ""
                }
                if (composingText.isNotEmpty()) finishComposing(ic)
                if (englishComposing.isNotEmpty()) {
                    ic.commitText(englishComposing, 1)
                    englishComposing = ""
                    clearCandidates()
                }
                japaneseEngine.reset()
                service.layerManager.toggleJapanese()
            }

            is KeyAction.Emoji -> {
                if (isConverting) commitSelectedCandidate(ic)
                if (composingFlickKana.isNotEmpty()) commitFlickIfNeeded()
                if (composingText.isNotEmpty()) finishComposing(ic)
                service.layerManager.isEmojiRequested = true
            }

            is KeyAction.Symbols -> {
                if (isConverting) commitSelectedCandidate(ic)
                if (composingFlickKana.isNotEmpty()) commitFlickIfNeeded()
                if (composingText.isNotEmpty()) finishComposing(ic)
                service.layerManager.isSymbolsRequested = true
            }

            is KeyAction.Alt -> service.layerManager.toggleAlt()

            is KeyAction.Henkan -> {
                if (composingFlickKana.isNotEmpty()) {
                    if (isConverting) nextCandidate(ic) else startFlickConversion(ic)
                } else if (composingText.isNotEmpty()) {
                    if (isConverting) nextCandidate(ic) else startConversion(ic)
                }
            }

            is KeyAction.KeyCode -> {
                // In flick mode, ▶ (DPAD_RIGHT) confirms current toggle input
                if (action.code == KeyEvent.KEYCODE_DPAD_RIGHT && lastFlickKeyId.isNotEmpty()) {
                    confirmFlickToggle()
                    return
                }
                val now = System.currentTimeMillis()
                var meta = 0
                if (action.ctrl) meta = meta or KeyEvent.META_CTRL_ON
                if (service.layerManager.isAltActive) meta = meta or KeyEvent.META_ALT_ON
                ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, action.code, 0, meta))
                ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, action.code, 0, meta))
                if (service.layerManager.isAltActive) service.layerManager.consumeAlt()
            }
        }
    }

    // --- Candidate selection from UI ---

    fun selectCandidate(index: Int) {
        val ic = service.currentInputConnection ?: return
        if (index in candidates.indices) {
            selectedCandidateIndex = index
            ic.setComposingText(candidates[index].surface, 1)
        }
    }

    fun commitCandidate(index: Int) {
        val ic = service.currentInputConnection ?: return
        if (index in candidates.indices) {
            val candidate = candidates[index]

            // Symbol replacement: delete the just-committed symbol and insert the selected one
            val replacing = symbolReplace
            if (replacing != null) {
                ic.deleteSurroundingText(replacing.length, 0)
                ic.commitText(candidate.surface, 1)
                clearCandidates()
                return
            }

            // Clear composing text first to avoid double input
            // (commitText auto-finishes composing, causing composing + candidate duplication)
            if (composingText.isNotEmpty() || englishComposing.isNotEmpty() || composingFlickKana.isNotEmpty()) {
                ic.setComposingText("", 0)
            }
            ic.finishComposingText()
            ic.commitText(candidate.surface, 1)
            if (englishComposing.isNotEmpty()) {
                dictionary?.recordEnglishSelection(candidate.surface)
                englishComposing = ""
            } else {
                dictionary?.recordSelection(candidate)
                recordJapanesePhrase(candidate.reading, candidate.surface)
            }
            composingText = ""
            composingKana = ""
            japaneseEngine.reset()
            clearCandidates()
            // Show next-word predictions after committing
            showNextWordPredictions()
        }
    }

    // --- Japanese conversion ---

    private fun processJapaneseInput(text: String, ic: InputConnection) {
        composingText += text
        val kana = japaneseEngine.romajiToHiragana(composingText)
        composingKana = kana
        ic.setComposingText(kana, 1)
        updatePredictions(kana)
    }

    private fun startConversion(ic: InputConnection) {
        val kana = japaneseEngine.romajiToHiragana(composingText, finalize = true)
        fullKana = kana
        segmentBoundary = kana.length // Default: entire kana is one segment
        val dict = dictionary
        if (dict != null) {
            val results = dict.convert(kana)
            if (results.isNotEmpty()) {
                candidates.clear()
                candidates.addAll(results)
                selectedCandidateIndex = 0
                isConverting = true
                ic.setComposingText(results[0].surface, 1)
                return
            }
        }
        // No dictionary or no results: commit as kana
        finishComposing(ic)
    }

    /**
     * Shrink or extend the first segment boundary during conversion.
     * Left = shrink (fewer kana in first segment), Right = extend.
     * Re-converts with new boundary and shows candidates for that segment.
     */
    fun adjustSegmentBoundary(direction: SwipeDirection) {
        if (!isConverting || fullKana.isEmpty()) return
        val ic = service.currentInputConnection ?: return
        val dict = dictionary ?: return

        when (direction) {
            SwipeDirection.Left -> {
                if (segmentBoundary > 1) segmentBoundary--
            }
            SwipeDirection.Right -> {
                if (segmentBoundary < fullKana.length) segmentBoundary++
            }
            else -> return
        }

        // Convert only the first segment
        val firstSegKana = fullKana.substring(0, segmentBoundary)
        val restKana = fullKana.substring(segmentBoundary)

        val firstResults = dict.convert(firstSegKana)
        val firstSurface = firstResults.firstOrNull()?.surface ?: firstSegKana

        // Build display: converted first segment + remaining kana
        val displayText = firstSurface + restKana

        // Update candidates for the first segment only
        candidates.clear()
        for (r in firstResults) {
            // Append remaining kana to each candidate surface for full display
            candidates.add(ConversionCandidate(
                surface = r.surface + restKana,
                reading = fullKana,
                cost = r.cost,
            ))
        }
        // Also add the first segment as hiragana + rest
        val allHiragana = fullKana
        if (candidates.none { it.surface == allHiragana }) {
            candidates.add(ConversionCandidate(surface = allHiragana, reading = fullKana, cost = 9999))
        }

        selectedCandidateIndex = 0
        ic.setComposingText(displayText, 1)
        service.feedbackManager.onSwipe()
    }

    private fun nextCandidate(ic: InputConnection) {
        if (candidates.isEmpty()) return
        selectedCandidateIndex = (selectedCandidateIndex + 1) % candidates.size
        ic.setComposingText(candidates[selectedCandidateIndex].surface, 1)
    }

    private fun commitSelectedCandidate(ic: InputConnection) {
        if (candidates.isNotEmpty() && selectedCandidateIndex in candidates.indices) {
            val candidate = candidates[selectedCandidateIndex]
            ic.commitText(candidate.surface, 1)
            dictionary?.recordSelection(candidate)
            recordJapanesePhrase(candidate.reading, candidate.surface)
        } else {
            val kana = japaneseEngine.romajiToHiragana(composingText, finalize = true)
            ic.commitText(kana, 1)
            recordJapanesePhrase(kana, kana)
        }
        composingText = ""
        composingFlickKana = ""
        composingKana = ""
        japaneseEngine.reset()
        clearCandidates()
        // Show next-word predictions based on context
        showNextWordPredictions()
    }

    private fun cancelConversion(ic: InputConnection) {
        val kana = japaneseEngine.romajiToHiragana(composingText)
        ic.setComposingText(kana, 1)
        isConverting = false
        selectedCandidateIndex = -1
        // Keep prediction candidates but not conversion candidates
        updatePredictions(kana)
    }

    private fun updatePredictions(kana: String) {
        // SPEC: disable prediction in password fields
        if (isPasswordField) return
        val dict = dictionary ?: return
        if (kana.isEmpty()) {
            clearCandidates()
            return
        }
        val romaji = composingText // Pass raw romaji for English candidate matching
        // Cancel previous prediction job — debounce for fast typing
        predictionJob?.cancel()
        predictionJob = scope.launch {
            // Debounce: wait 50ms before starting prediction to batch rapid keystrokes
            kotlinx.coroutines.delay(50L)
            // Run dictionary lookup off the main thread
            val predictions = withContext(Dispatchers.Default) {
                dict.predict(kana, romaji)
            }
            // Update in one snapshot to avoid Compose seeing empty list
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                candidates.clear()
                candidates.addAll(predictions)
                isConverting = false
                selectedCandidateIndex = -1
            }

            // Async LLM reranking (non-blocking — updates candidates in-place when done)
            if (predictions.size > 1) {
                val precedingText = service.currentInputConnection
                    ?.getTextBeforeCursor(50, 0)?.toString() ?: ""
                llmReranker.rerankAsync(kana, predictions, precedingText) { reranked ->
                    // Only apply if candidates haven't changed since we started
                    if (candidates.size == reranked.size &&
                        candidates.firstOrNull()?.reading == reranked.firstOrNull()?.reading
                    ) {
                        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                            candidates.clear()
                            candidates.addAll(reranked)
                            selectedCandidateIndex = -1
                        }
                    }
                }
            }
        }
    }

    /**
     * Update English autocomplete/spellcheck predictions.
     */
    private fun updateEnglishPredictions(prefix: String) {
        if (isPasswordField) return
        val dict = dictionary ?: return
        if (prefix.isEmpty()) {
            clearCandidates()
            return
        }
        englishPredictionJob?.cancel()
        englishPredictionJob = scope.launch {
            kotlinx.coroutines.delay(30L) // Faster debounce for English
            val predictions = withContext(Dispatchers.Default) {
                dict.predictEnglish(prefix, limit = 15)
            }
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                candidates.clear()
                candidates.addAll(predictions)
                isConverting = false
                selectedCandidateIndex = if (predictions.isNotEmpty()) 0 else -1
            }
        }
    }

    /**
     * Show next-word predictions based on recently committed context.
     * Uses bigram/trigram history to suggest likely follow-up words.
     */
    private fun showNextWordPredictions() {
        if (isPasswordField) return
        if (!service.layerManager.isJapanese) return
        val nacrDict = dictionary as? NacreDictionary ?: return
        predictionJob?.cancel()
        predictionJob = scope.launch {
            val predictions = withContext(Dispatchers.Default) {
                nacrDict.predictNextWord(limit = 8)
            }
            if (predictions.isNotEmpty() && composingText.isEmpty()) {
                androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                    candidates.clear()
                    candidates.addAll(predictions)
                    isConverting = false
                    selectedCandidateIndex = -1
                }
            }

            // Local-LLM augmentation: ask the on-device model for context-aware
            // next-word predictions and prepend them. Additive and best-effort —
            // any failure/timeout/busy leaves the n-gram predictions in place.
            // On-device only (no network); guarded against stale context.
            try {
                // Debounce: a new commit cancels predictionJob before this fires,
                // so rapid typing doesn't pile up local-LLM calls.
                kotlinx.coroutines.delay(120)
                val ctx = service.currentInputConnection
                    ?.getTextBeforeCursor(60, 0)?.toString()?.trim().orEmpty()
                if (ctx.length >= 4 && composingText.isEmpty()) {
                    val llmPreds = withContext(Dispatchers.Default) {
                        service.voiceInputManager.predictNextViaLlm(ctx, timeoutMs = 600)
                    }
                    val ctxNow = service.currentInputConnection
                        ?.getTextBeforeCursor(60, 0)?.toString()?.trim().orEmpty()
                    if (!llmPreds.isNullOrEmpty() && composingText.isEmpty() &&
                        !isConverting && ctxNow == ctx
                    ) {
                        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                            val seen = candidates.map { it.surface }.toMutableSet()
                            val add = llmPreds
                                .filter { it.isNotBlank() && seen.add(it) }
                                .map { ConversionCandidate(surface = it, reading = it, cost = 100) }
                            if (add.isNotEmpty()) candidates.addAll(0, add)
                        }
                    }
                }
            } catch (_: Exception) {
                // best-effort; keep the n-gram predictions
            }
        }
    }

    private fun finishComposing(ic: InputConnection) {
        val kana = japaneseEngine.romajiToHiragana(composingText, finalize = true)
        ic.commitText(kana, 1)
        // Update bigram context
        (dictionary as? NacreDictionary)?.updateContext(kana)
        recordJapanesePhrase(kana, kana)
        composingText = ""
        composingFlickKana = ""
        composingKana = ""
        japaneseEngine.reset()
        clearCandidates()
    }

    private fun recordJapanesePhrase(reading: String, surface: String) {
        (dictionary as? NacreDictionary)?.recordPhrase(reading, surface)
    }

    private fun clearCandidates() {
        predictionJob?.cancel()
        predictionJob = null
        candidates.clear()
        selectedCandidateIndex = -1
        isConverting = false
        symbolReplace = null
        composingFlickKana = ""
        lastFlickKeyId = ""
        lastFlickTapTime = 0L
        lastFlickTapCycle = null
    }

    // Symbol candidate state: when non-null, tapping a candidate replaces the last committed symbol
    private var symbolReplace: String? = null

    /** Commit a fullwidth symbol and show alternatives */
    private fun commitTextWithSymbol(ic: InputConnection, symbol: String, alternatives: List<String>) {
        ic.commitText(symbol, 1)
        showSymbolCandidates(symbol, alternatives)
    }

    /**
     * Show symbol alternatives in the candidate bar.
     * Tapping a candidate replaces the just-committed symbol.
     */
    private fun showSymbolCandidates(committed: String, alternatives: List<String>) {
        symbolReplace = committed
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            candidates.clear()
            candidates.addAll(alternatives.map { sym ->
                ConversionCandidate(surface = sym, reading = committed, cost = 0)
            })
            selectedCandidateIndex = 0 // First one is the already-committed symbol
            isConverting = false
        }
    }

    /**
     * Remove the last kana unit from romaji composing text.
     * Compares kana output to find how many romaji chars produce the last kana.
     * E.g. "ka" → "" (か), "kyo" → "" (きょ), "kakiko" → "kaki" (remove こ)
     * Trailing unconverted consonant: "kak" → "ka" (remove trailing k)
     */
    private fun removeLastKanaUnit(romaji: String): String {
        if (romaji.isEmpty()) return ""

        // Single character — always clear entirely
        if (romaji.length == 1) return ""

        val currentKana = japaneseEngine.romajiToHiragana(romaji, finalize = true)

        // If the romaji produces no kana (all unconverted), clear entirely
        if (currentKana == romaji) return ""

        // Try removing 1..N chars from the end — find the shortest removal
        // that actually changes the kana output (i.e., removes one kana unit)
        for (drop in 1..romaji.length) {
            val shortened = romaji.dropLast(drop)
            val shortenedKana = japaneseEngine.romajiToHiragana(shortened, finalize = true)
            if (shortenedKana.length < currentKana.length) {
                return shortened
            }
        }

        // Fallback: clear entirely (should not reach here)
        return ""
    }

    private fun commitText(text: String) {
        val ic = service.currentInputConnection ?: return
        ic.commitText(text, 1)

        // Single IPC call for both auto-convert and macro checks
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        val fullText = extracted?.text?.toString() ?: ""

        // Check auto-convert rules (e.g. "->" → "→")
        if (service.autoConvertEngine.isEnabled && fullText.isNotEmpty()) {
            service.autoConvertEngine.checkAndConvert(fullText, ic)
        }

        // Check macro triggers (e.g. ";gs" → "git status" + Enter)
        val macro = service.macroEngine.checkTrigger(fullText)
        if (macro != null && macro.trigger != null) {
            ic.deleteSurroundingText(macro.trigger.length, 0)
            // Re-fetch IC inside coroutine to avoid stale reference
            scope.launch {
                val freshIc = service.currentInputConnection ?: return@launch
                service.macroEngine.executeMacro(macro, freshIc)
            }
        }
    }

    private fun commitNewline(ic: InputConnection) {
        ic.finishComposingText()
        ic.commitText("\n", 1)
        if (service.layerManager.isAltActive) service.layerManager.consumeAlt()
        if (service.layerManager.isShifted) service.layerManager.toggleShift()
    }

    private fun performEditorActionOrEnter(ic: InputConnection) {
        val opts = editorInfo?.imeOptions ?: 0
        val imeAction = opts and EditorInfo.IME_MASK_ACTION
        when (imeAction) {
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_NEXT -> ic.performEditorAction(imeAction)
            else -> sendKeyEvent(KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun shouldCommitDirectTokenText(text: String, ic: InputConnection): Boolean {
        if (text.length != 1) return false
        val ch = text[0]
        if (!(ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.')) return false
        val before = ic.getTextBeforeCursor(80, 0)?.toString().orEmpty()
        return DIRECT_TOKEN_CONTEXT.containsMatchIn(before)
    }

    private fun commitDirectTokenText(text: String, ic: InputConnection) {
        if (composingText.isNotEmpty()) {
            composingText = ""
            composingKana = ""
            japaneseEngine.reset()
        }
        if (englishComposing.isNotEmpty()) {
            englishComposing = ""
        }
        clearCandidates()
        val out = if (service.layerManager.isShifted && text[0].isLetter()) {
            text.uppercase()
        } else {
            text
        }
        ic.finishComposingText()
        ic.commitText(out, 1)
        if (service.layerManager.isShifted) service.layerManager.toggleShift()
    }

    private fun sendKeyEvent(keyCode: Int) {
        val ic = service.currentInputConnection ?: return
        val now = System.currentTimeMillis()
        val altMeta = if (service.layerManager.isAltActive) KeyEvent.META_ALT_ON else 0
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, altMeta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, altMeta))
        if (altMeta != 0) service.layerManager.consumeAlt()
    }

    fun moveCursor(dx: Int, dy: Int) {
        val ic = service.currentInputConnection ?: return
        val ei = editorInfo

        if (isTerminalApp(ei)) {
            repeat(kotlin.math.abs(dx)) {
                sendKeyEvent(if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
            }
            repeat(kotlin.math.abs(dy)) {
                sendKeyEvent(if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
            }
        } else {
            if (dx != 0) {
                val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
                val text = extracted.text?.toString() ?: return
                val pos = extracted.selectionStart
                if (pos < 0) return
                val newPos = (pos + dx).coerceIn(0, text.length)
                ic.setSelection(newPos, newPos)
            }
            if (dy != 0) {
                repeat(kotlin.math.abs(dy)) {
                    sendKeyEvent(if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
                }
            }
        }
    }

    // Track last flick tap for toggle input (ガラケー打ち)
    private var lastFlickKeyId: String = ""
    private var lastFlickTapTime: Long = 0L
    private var lastFlickTapCycle: List<String>? = null
    private val TOGGLE_TIMEOUT_MS = 800L // Time window for toggle input

    /**
     * Direct kana input from flick keyboard.
     * Supports toggle input (ガラケー打ち): rapidly tapping the same key
     * cycles through its kana variants (あ→い→う→え→お→あ...).
     */
    fun processFlickKana(
        kana: String,
        flickKeyId: String = "",
        isFlickTap: Boolean = false,
        tapCycleOverride: List<String>? = null,
    ) {
        val ic = service.currentInputConnection ?: return
        if (isConverting) commitSelectedCandidate(ic)

        val now = System.currentTimeMillis()

        // Toggle input: same key tapped again within timeout
        if (isFlickTap && flickKeyId.isNotEmpty() && flickKeyId == lastFlickKeyId
            && (now - lastFlickTapTime) < TOGGLE_TIMEOUT_MS
            && composingFlickKana.isNotEmpty()
        ) {
            // Build tap cycle: prefer override, then stored cycle, then FlickKey.tapCycle, then flick directions
            val flickKey = FlickEngine.kanaKeys.find { it.id == flickKeyId }
            val cycle = tapCycleOverride
                ?: lastFlickTapCycle
                ?: flickKey?.tapCycle
                ?: flickKey?.let { listOfNotNull(it.tap, it.left, it.up, it.right, it.down) }
            if (cycle != null && cycle.isNotEmpty()) {
                val lastChar = composingFlickKana.last().toString()
                val currentIndex = cycle.indexOf(lastChar)
                val nextIndex = if (currentIndex >= 0) (currentIndex + 1) % cycle.size else 0
                val nextKana = cycle[nextIndex]

                composingFlickKana = composingFlickKana.dropLast(1) + nextKana
                composingKana = composingFlickKana
                ic.setComposingText(composingFlickKana, 1)
                updatePredictions(composingFlickKana)
                lastFlickTapTime = now
                return
            }
        }

        // Reset toggle state when switching to a different key
        if (isFlickTap && flickKeyId != lastFlickKeyId) {
            lastFlickKeyId = ""
            lastFlickTapTime = 0L
            lastFlickTapCycle = null
        }

        // Normal input (flick or first tap)
        composingFlickKana += kana
        composingKana = composingFlickKana
        ic.setComposingText(composingFlickKana, 1)
        updatePredictions(composingFlickKana)

        if (isFlickTap) {
            lastFlickKeyId = flickKeyId
            lastFlickTapTime = now
            lastFlickTapCycle = tapCycleOverride
        } else {
            lastFlickKeyId = ""
            lastFlickTapTime = 0L
            lastFlickTapCycle = null
        }
    }

    /**
     * Confirm current toggle input position (called by ▶ key in 12-key mode).
     * Resets the toggle state so the next tap on the same key starts a new character.
     */
    fun confirmFlickToggle() {
        lastFlickKeyId = ""
        lastFlickTapTime = 0L
        lastFlickTapCycle = null
    }

    /**
     * Apply dakuten/handakuten/small to the last kana.
     * When type is Dakuten (tap), cycles: base → dakuten → handakuten → small → base
     * e.g. は→ば→ぱ→は, つ→づ→っ→つ
     */
    fun processFlickDakuten(type: DakutenType) {
        if (composingFlickKana.isEmpty()) return
        val ic = service.currentInputConnection ?: return
        val lastChar = composingFlickKana.last()

        val replaced = when (type) {
            DakutenType.Dakuten -> {
                // Tap: cycle through dakuten → handakuten → small → base
                FlickEngine.applyDakuten(lastChar)
                    ?: FlickEngine.applyHandakuten(lastChar)
                    ?: FlickEngine.applySmall(lastChar)
            }
            DakutenType.Handakuten -> FlickEngine.applyHandakuten(lastChar)
            DakutenType.Small -> FlickEngine.applySmall(lastChar)
        } ?: return

        composingFlickKana = composingFlickKana.dropLast(1) + replaced
        composingKana = composingFlickKana
        ic.setComposingText(composingFlickKana, 1)
        updatePredictions(composingFlickKana)
    }

    /**
     * iOS-style ゛小゜ tap: cycle the last kana through base → small → dakuten →
     * handakuten (small first, dakuten next).
     */
    fun processFlickDakutenCycle() {
        if (composingFlickKana.isEmpty()) return
        val ic = service.currentInputConnection ?: return
        val lastChar = composingFlickKana.last()
        val replaced = FlickEngine.cycleKanaForm(lastChar) ?: return
        composingFlickKana = composingFlickKana.dropLast(1) + replaced
        composingKana = composingFlickKana
        ic.setComposingText(composingFlickKana, 1)
        updatePredictions(composingFlickKana)
    }

    /**
     * Flick-mode conversion. Uses composingFlickKana directly (no romaji).
     */
    private fun startFlickConversion(ic: android.view.inputmethod.InputConnection) {
        val kana = composingFlickKana
        val dict = dictionary
        if (dict != null) {
            val results = dict.convert(kana)
            if (results.isNotEmpty()) {
                fullKana = kana
                segmentBoundary = kana.length
                candidates.clear()
                candidates.addAll(results)
                selectedCandidateIndex = 0
                isConverting = true
                ic.setComposingText(results[0].surface, 1)
                return
            }
        }
        // No results: commit as kana
        ic.commitText(kana, 1)
        composingFlickKana = ""
        composingKana = ""
        clearCandidates()
    }

    fun processFlickBackspace() {
        val ic = service.currentInputConnection ?: return
        if (isConverting) {
            cancelConversion(ic)
        } else if (composingFlickKana.isNotEmpty()) {
            composingFlickKana = composingFlickKana.dropLast(1)
            composingKana = composingFlickKana
            if (composingFlickKana.isEmpty()) {
                ic.finishComposingText()
                clearCandidates()
            } else {
                ic.setComposingText(composingFlickKana, 1)
                updatePredictions(composingFlickKana)
            }
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    /**
     * Commit any active flick composition (called when switching away from flick layout).
     */
    fun commitFlickIfNeeded() {
        if (composingFlickKana.isNotEmpty()) {
            val ic = service.currentInputConnection ?: return
            if (isConverting) {
                commitSelectedCandidate(ic)
            } else {
                ic.commitText(composingFlickKana, 1)
                recordJapanesePhrase(composingFlickKana, composingFlickKana)
                composingFlickKana = ""
                composingKana = ""
                clearCandidates()
            }
        }
    }

    fun destroy() {
        llmReranker.destroy()
        scope.cancel()
    }

    private fun isTerminalApp(info: EditorInfo?): Boolean {
        val pkg = info?.packageName ?: return false
        return pkg.contains("termux") || pkg.contains("terminal") || pkg.contains("connectbot")
    }
}

enum class SwipeDirection { Up, Down, Left, Right }

data class ConversionCandidate(
    val surface: String,
    val reading: String,
    val cost: Int = 0,
    val segments: List<String> = emptyList(),
)

interface DictionaryProvider {
    fun convert(kana: String): List<ConversionCandidate>
    fun predict(kana: String, romaji: String = ""): List<ConversionCandidate>
    fun recordSelection(candidate: ConversionCandidate)
    fun predictEnglish(prefix: String, limit: Int = 20): List<ConversionCandidate>
    fun recordEnglishSelection(word: String)
}
