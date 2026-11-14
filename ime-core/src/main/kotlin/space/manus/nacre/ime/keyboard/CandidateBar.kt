package space.manus.nacre.ime.keyboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.manus.nacre.ime.NacreInputMethodService
import space.manus.nacre.ime.input.ConversionCandidate
import space.manus.nacre.ime.input.SwipeDirection
import space.manus.nacre.ime.input.VoiceAsrUiState
import space.manus.nacre.ime.input.VoiceRefineUiState
import space.manus.nacre.ime.input.ZenzUiState
import kotlin.math.abs

@Composable
fun CandidateBar(
    service: NacreInputMethodService,
    modifier: Modifier = Modifier,
) {
    val theme = service.currentTheme
    val barBg = Color(theme.candidateBackground.toInt())
    val accent = Color(theme.accent.toInt())
    val candidates = service.inputEngine.candidates
    val selectedIndex = service.inputEngine.selectedCandidateIndex
    val dictLoaded = service.inputEngine.dictionaryLoaded
    val isConverting = service.inputEngine.isConverting
    val voicePartial = service.voiceInputManager.partialText
    val voiceListening = service.voiceInputManager.isListening
    val voiceError = service.voiceInputManager.lastError
    val voiceAsrState = service.voiceInputManager.voiceAsrUiState
    val voiceRefineState = service.voiceInputManager.voiceRefineUiState

    // SPEC: hide candidate bar in password fields
    if (service.inputEngine.isPasswordField) {
        Spacer(modifier = modifier.fillMaxWidth().height(36.dp).background(barBg))
        return
    }

    val scrollState = rememberScrollState()
    val swipeThresholdPx = with(LocalDensity.current) { 20.dp.toPx() }

    LaunchedEffect(candidates.toList()) {
        scrollState.scrollTo(0)
    }

    // Expand-all grid state. The grid belongs to the current composing
    // session: typing the next character (which replaces the candidate
    // set) collapses it without committing, as does the set emptying
    // (commit / clear). Tapping a grid chip commits and closes it.
    var expanded by remember { mutableStateOf(false) }
    val composingKana = service.inputEngine.composingKana
    LaunchedEffect(composingKana) { expanded = false }
    LaunchedEffect(candidates.isEmpty()) {
        if (candidates.isEmpty()) expanded = false
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(barBg),
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fixed dictation mic (outline). Always visible; tap toggles voice input,
        // turns red while recording. This is the single voice entry point, shared
        // by every layout that shows the candidate bar.
        val micColor = if (voiceListening) Color(0xFFFF4444) else accent
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(44.dp)
                .pointerInput(Unit) {
                    detectTapGestures {
                        if (service.voiceInputManager.isListening) {
                            // Second tap = finalize & commit the transcription (NOT cancel/discard).
                            service.voiceInputManager.stopListening()
                        } else {
                            val lang = if (service.layerManager.isJapanese) "ja-JP" else "en-US"
                            service.voiceInputManager.startListening(lang)
                        }
                    }
                }
                .semantics { contentDescription = "音声入力" },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(width = 16.dp, height = 22.dp)) {
                drawVoiceIcon(micColor, 1.5.dp.toPx())
            }
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .then(
                    if (isConverting) {
                        // During conversion: detect left/right swipe for segment boundary adjustment
                        Modifier.pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var totalX = 0f
                                var totalY = 0f
                                var handled = false

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (change.pressed) {
                                        val delta = change.positionChange()
                                        totalX += delta.x
                                        totalY += delta.y
                                        change.consume()

                                        // Trigger segment adjustment on sufficient horizontal swipe
                                        if (!handled && abs(totalX) > swipeThresholdPx && abs(totalX) > abs(totalY)) {
                                            handled = true
                                            val dir = if (totalX > 0) SwipeDirection.Right else SwipeDirection.Left
                                            service.inputEngine.adjustSegmentBoundary(dir)
                                        }
                                    } else {
                                        change.consume()
                                        break
                                    }
                                }
                            }
                        }
                    } else {
                        Modifier
                    }
                )
                .horizontalScroll(scrollState)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
        if (voiceListening || voicePartial == "Thinking...") {
            // While dictating, the bar names the ACTIVE recognition engine
            // and flags a dead refiner — the SpeechRecognizer fallback and
            // LLM failures used to be completely invisible here.
            Text(
                text = when {
                    voicePartial == "Thinking..." -> "🤔 Thinking..."
                    voiceError.isNotEmpty() -> "⚠ $voiceError"
                    else -> buildString {
                        append("🎤 音声入力中（")
                        append(service.voiceInputManager.asrStateLabel())
                        append("）")
                        if (voiceRefineState == VoiceRefineUiState.UNAVAILABLE) {
                            append(" 整文不可")
                        }
                        if (service.voiceInputManager.isVoiceEditMode) {
                            append(" ✏️編集モード")
                        }
                    }
                },
                color = when {
                    voicePartial == "Thinking..." -> Color(0xFFAADDFF)
                    voiceError.isNotEmpty() -> Color(0xFFFF6666)
                    voiceAsrState == VoiceAsrUiState.EXTERNAL ||
                        voiceAsrState == VoiceAsrUiState.MODEL_MISSING ->
                        Color(0xFFFFAA33)
                    else -> Color(0xFFFF8888)
                },
                fontSize = 12.sp,
                maxLines = 1,
            )
        } else if (candidates.isEmpty() && !dictLoaded) {
            Text(
                text = "辞書loading...",
                color = Color(0xFF555555),
                fontSize = 10.sp,
            )
        }

        if (candidates.isNotEmpty()) {
            // Show segment boundary hint during conversion
            if (isConverting) {
                Text(
                    text = "◀▶",
                    color = accent.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }

            candidates.forEachIndexed { index, candidate ->
                CandidateChip(
                    candidate = candidate,
                    isSelected = index == selectedIndex,
                    onClick = {
                        if (isConverting) {
                            service.inputEngine.selectCandidate(index)
                        } else {
                            service.inputEngine.commitCandidate(index)
                        }
                    },
                    onLongClick = {
                        // Long-press a candidate → register it (読み→表記) to the user
                        // dictionary so it converts first next time.
                        if (candidate.reading.isNotEmpty()) {
                            service.inputEngine.registerUserWord(candidate.reading, candidate.surface)
                            service.feedbackManager.onLongPress()
                        }
                    },
                    index = index,
                    chipBg = Color(theme.keyBackground.toInt()),
                    chipText = Color(theme.keyText.toInt()),
                    selectedBg = accent,
                    selectedText = Color(theme.background.toInt()),
                )
                if (index < candidates.size - 1) {
                    Spacer(modifier = Modifier.width(4.dp))
                }
            }
        }
        }

        // Expand-all toggle (Gboard/iPhone style chevron). It sits between
        // the scrolling candidates and the zenz status label, which keeps
        // the far right end of the bar. Shown only when there are more
        // candidates than the strip can usefully show, and never while
        // dictating (the bar then carries the voice status instead).
        if (candidates.size > 3 && !voiceListening && voicePartial != "Thinking...") {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(36.dp)
                    .clickable { expanded = !expanded }
                    .semantics {
                        contentDescription = if (expanded) "候補を閉じる" else "候補をすべて表示"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(width = 16.dp, height = 12.dp)) {
                    drawChevronIcon(accent, up = expanded, strokeWidthPx = 1.8.dp.toPx())
                }
            }
        }

        // zenz readiness indicator (fixed at the bar's right end, both
        // layouts): a silently-dead neural converter was indistinguishable
        // from "conversion is just weak" — this makes the state diagnosable
        // from the keyboard. Hidden when the user turned zenz off, in ABC
        // mode, and in non-Japanese layers (zenz converts Japanese only).
        val zenzState = service.voiceInputManager.zenzUiState
        if (zenzState != ZenzUiState.OFF &&
            service.layerManager.isJapanese &&
            !service.inputEngine.flickAlphaMode
        ) {
            Text(
                text = when (zenzState) {
                    ZenzUiState.READY -> "zenz"
                    ZenzUiState.LOADING -> "zenz…"
                    ZenzUiState.NOT_DOWNLOADED -> "zenz未DL"
                    ZenzUiState.ERROR -> "zenz⚠"
                    ZenzUiState.OFF -> ""
                },
                color = when (zenzState) {
                    ZenzUiState.READY -> accent.copy(alpha = 0.55f)
                    ZenzUiState.LOADING -> Color(0xFF888888)
                    ZenzUiState.NOT_DOWNLOADED -> Color(0xFFFFAA33)
                    ZenzUiState.ERROR -> Color(0xFFFF6666)
                    ZenzUiState.OFF -> Color.Transparent
                },
                fontSize = 9.sp,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    // Expanded candidate grid: the full candidate list as chips in rows,
    // directly above the keys. A chip here behaves exactly like the same
    // chip in the strip (select during conversion, commit otherwise,
    // long-press registers a user word) and closes the panel.
    if (expanded && candidates.isNotEmpty()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 124.dp)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(
                candidates,
                key = { index, candidate -> "$index|${candidate.reading}|${candidate.surface}" },
            ) { index, candidate ->
                CandidateChip(
                    candidate = candidate,
                    isSelected = index == selectedIndex,
                    onClick = {
                        if (isConverting) {
                            service.inputEngine.selectCandidate(index)
                        } else {
                            service.inputEngine.commitCandidate(index)
                        }
                        expanded = false
                    },
                    onLongClick = {
                        if (candidate.reading.isNotEmpty()) {
                            service.inputEngine.registerUserWord(candidate.reading, candidate.surface)
                            service.feedbackManager.onLongPress()
                        }
                    },
                    index = index,
                    chipBg = Color(theme.keyBackground.toInt()),
                    chipText = Color(theme.keyText.toInt()),
                    selectedBg = accent,
                    selectedText = Color(theme.background.toInt()),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CandidateChip(
    candidate: ConversionCandidate,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    index: Int,
    chipBg: Color,
    chipText: Color,
    selectedBg: Color,
    selectedText: Color,
    modifier: Modifier = Modifier,
) {
    val bg = if (isSelected) selectedBg else chipBg
    val textColor = if (isSelected) selectedText else chipText

    Box(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics {
                contentDescription = "候補${index + 1}: ${candidate.surface}"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = candidate.surface,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** Chevron for the expand-all toggle, drawn like the mic icon (no icon dependency). */
private fun DrawScope.drawChevronIcon(color: Color, up: Boolean, strokeWidthPx: Float) {
    val w = size.width
    val h = size.height
    val yEnds = if (up) h * 0.70f else h * 0.30f
    val yApex = if (up) h * 0.30f else h * 0.70f
    drawLine(
        color = color,
        start = Offset(w * 0.10f, yEnds),
        end = Offset(w * 0.50f, yApex),
        strokeWidth = strokeWidthPx,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color,
        start = Offset(w * 0.50f, yApex),
        end = Offset(w * 0.90f, yEnds),
        strokeWidth = strokeWidthPx,
        cap = StrokeCap.Round,
    )
}
