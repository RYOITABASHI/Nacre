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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

    androidx.compose.runtime.SideEffect {
        android.util.Log.i("NacreMic", "CandidateBar composed (mic rendered), listening=$voiceListening")
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(barBg),
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
                        android.util.Log.i("NacreMic", "mic TAP detected, isListening=${service.voiceInputManager.isListening}")
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
            Text(
                text = when {
                    voicePartial == "Thinking..." -> "🤔 Thinking..."
                    voiceError.isNotEmpty() -> "⚠ $voiceError"
                    else -> "🎤 音声入力中..."
                },
                color = when {
                    voicePartial == "Thinking..." -> Color(0xFFAADDFF)
                    voiceError.isNotEmpty() -> Color(0xFFFF6666)
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
) {
    val bg = if (isSelected) selectedBg else chipBg
    val textColor = if (isSelected) selectedText else chipText

    Box(
        modifier = Modifier
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
        )
    }
}
