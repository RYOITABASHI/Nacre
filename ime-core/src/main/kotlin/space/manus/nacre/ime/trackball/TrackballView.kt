package space.manus.nacre.ime.trackball

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull
import space.manus.nacre.ime.NacreInputMethodService
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

@Composable
fun TrackballView(
    service: NacreInputMethodService,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val theme = service.currentTheme
    val accent = Color(0xFFFF2222) // Trackball: red glow
    val bg = Color(theme.background.toInt())

    var dotX by remember { mutableFloatStateOf(0f) }
    var dotY by remember { mutableFloatStateOf(0f) }
    var isActive by remember { mutableStateOf(false) }
    val isVoiceActive = service.voiceInputManager.isListening
    val voiceRms = service.voiceInputManager.rmsLevel

    val stepThreshold = with(density) { 8.dp.toPx() }
    val maxDotOffset = with(density) { 12.dp.toPx() }
    val tapThreshold = with(density) { 4.dp.toPx() }
    val flickThreshold = with(density) { 20.dp.toPx() }
    val doubleTapTimeoutMs = 300L
    val longPressMs = 350L

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                var lastTapTime = 0L
                var isInSelectionMode = false

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downTime = System.currentTimeMillis()
                    isActive = true

                    var totalDragX = 0f
                    var totalDragY = 0f
                    var accumulatedX = 0f
                    var accumulatedY = 0f
                    var wasDragged = false
                    var longPressHandled = false

                    val isDoubleTapStart = downTime - lastTapTime < doubleTapTimeoutMs

                    try {
                        while (true) {
                            val event = withTimeoutOrNull(
                                if (longPressHandled) Long.MAX_VALUE
                                else maxOf(1L, longPressMs - (System.currentTimeMillis() - downTime)),
                            ) {
                                awaitPointerEvent()
                            }

                            if (event == null) {
                                if (!wasDragged && !longPressHandled) {
                                    longPressHandled = true
                                    service.feedbackManager.onLongPress()
                                    // Long press → voice input
                                    val lang = if (service.layerManager.isJapanese) "ja-JP" else "en-US"
                                    service.voiceInputManager.startListening(lang)
                                }
                                continue
                            }

                            val change = event.changes.firstOrNull() ?: break

                            if (change.pressed) {
                                val delta = change.positionChange()
                                totalDragX += delta.x
                                totalDragY += delta.y

                                if (abs(totalDragX) > tapThreshold || abs(totalDragY) > tapThreshold) {
                                    wasDragged = true
                                }

                                if (wasDragged) {
                                    if (isDoubleTapStart && !isInSelectionMode) {
                                        isInSelectionMode = true
                                        selectWordAtCursor(service)
                                    }

                                    val velocity = sqrt(delta.x * delta.x + delta.y * delta.y) /
                                        density.density * 60f
                                    val gain = cdGain(velocity)

                                    accumulatedX += delta.x * gain
                                    accumulatedY += delta.y * gain

                                    dotX = (dotX + delta.x * 0.3f).coerceIn(-maxDotOffset, maxDotOffset)
                                    dotY = (dotY + delta.y * 0.3f).coerceIn(-maxDotOffset, maxDotOffset)

                                    var dx = 0
                                    var dy = 0
                                    while (abs(accumulatedX) >= stepThreshold) {
                                        dx += if (accumulatedX > 0) 1 else -1
                                        accumulatedX -= if (accumulatedX > 0) stepThreshold else -stepThreshold
                                    }
                                    while (abs(accumulatedY) >= stepThreshold) {
                                        dy += if (accumulatedY > 0) 1 else -1
                                        accumulatedY -= if (accumulatedY > 0) stepThreshold else -stepThreshold
                                    }
                                    if (dx != 0 || dy != 0) {
                                        if (isInSelectionMode) {
                                            extendSelection(service, dx, dy)
                                        } else {
                                            service.inputEngine.moveCursor(dx, dy)
                                        }
                                        service.feedbackManager.onTrackballStep()
                                    }
                                }
                                change.consume()
                            } else {
                                change.consume()
                                break
                            }
                        }
                    } finally {
                        dotX = 0f
                        dotY = 0f
                        isActive = false
                        isInSelectionMode = false
                    }

                    when {
                        longPressHandled -> {
                            lastTapTime = 0L
                        }
                        wasDragged -> {
                            if (totalDragY < -flickThreshold && abs(totalDragX) < abs(totalDragY)) {
                                val lang = if (service.layerManager.isJapanese) "ja-JP" else "en-US"
                                service.voiceInputManager.startListening(lang)
                            }
                            lastTapTime = 0L
                        }
                        else -> {
                            val now = System.currentTimeMillis()
                            if (service.voiceInputManager.isListening) {
                                // Single tap stops voice input
                                service.voiceInputManager.stopListening()
                                service.feedbackManager.onLongPress()
                                lastTapTime = 0L
                            } else if (now - lastTapTime < doubleTapTimeoutMs) {
                                selectWordAtCursor(service)
                                lastTapTime = 0L
                            } else {
                                lastTapTime = now
                                service.feedbackManager.onKeyPress(
                                    space.manus.nacre.config.KeyAction.Enter,
                                )
                                // Route through InputEngine like the main keyboard's Enter key
                                // (not a raw sendKeyEvent) so pending Japanese/English composing
                                // text and candidates are flushed first, and the field's own
                                // IME action / multi-line flag is honored consistently — see
                                // InputEngine.computeEnterOutcome.
                                service.inputEngine.processAction(space.manus.nacre.config.KeyAction.Enter)
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = size.minDimension / 2f

            // Voice input mode: ring pulses with RMS level
            val ringAlpha = when {
                isVoiceActive -> 0.5f + voiceRms * 0.5f  // 0.5..1.0 based on volume
                isActive -> 0.9f
                else -> 0.35f
            }
            val glowRadius = when {
                isVoiceActive -> r * (1.0f + voiceRms * 0.3f)  // Expands with volume
                isActive -> r * 1.1f
                else -> r
            }
            val ringStroke = when {
                isVoiceActive -> (2f + voiceRms * 3f) * density.density  // Thicker ring on loud input
                else -> 2f * density.density
            }
            val activeAccent = if (isVoiceActive) Color(0xFFFF4444) else accent

            // Outer glow — soft radial gradient behind the ring
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        activeAccent.copy(alpha = if (isActive || isVoiceActive) 0.15f + voiceRms * 0.2f else 0.06f),
                        Color.Transparent,
                    ),
                    center = Offset(cx, cy),
                    radius = glowRadius,
                ),
                radius = glowRadius,
            )

            // Dark pit — the trackball well
            drawCircle(
                color = bg,
                radius = r * 0.85f,
            )

            // Glowing ring — the main visual element
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        activeAccent.copy(alpha = ringAlpha),
                        activeAccent.copy(alpha = ringAlpha * 0.3f),
                        activeAccent.copy(alpha = ringAlpha),
                        activeAccent.copy(alpha = ringAlpha * 0.3f),
                        activeAccent.copy(alpha = ringAlpha),
                    ),
                    center = Offset(cx, cy),
                ),
                radius = r * 0.92f,
                style = Stroke(width = ringStroke),
            )

            // Inner subtle ring
            drawCircle(
                color = activeAccent.copy(alpha = ringAlpha * 0.15f),
                radius = r * 0.55f,
                style = Stroke(width = 0.5f * density.density),
            )

            // Center dot — mic icon effect when voice active
            val dotCx = cx + dotX
            val dotCy = cy + dotY
            val dotR = if (isVoiceActive) (3f + voiceRms * 2f) * density.density else 2.5f * density.density

            // Dot glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        activeAccent.copy(alpha = if (isActive || isVoiceActive) 0.3f + voiceRms * 0.4f else 0.2f),
                        Color.Transparent,
                    ),
                    center = Offset(dotCx, dotCy),
                    radius = dotR * 4f,
                ),
                radius = dotR * 4f,
                center = Offset(dotCx, dotCy),
            )

            // Dot core
            drawCircle(
                color = activeAccent.copy(alpha = if (isActive || isVoiceActive) 1f else 0.5f),
                radius = dotR,
                center = Offset(dotCx, dotCy),
            )
        }
    }
}

private fun selectWordAtCursor(service: NacreInputMethodService) {
    val ic = service.currentInputConnection ?: return
    val extracted = ic.getExtractedText(
        android.view.inputmethod.ExtractedTextRequest(), 0,
    ) ?: return
    val text = extracted.text?.toString() ?: return
    val pos = extracted.selectionStart
    if (pos < 0 || pos > text.length) return

    var start = pos
    var end = pos
    while (start > 0 && text[start - 1].isLetterOrDigit()) start--
    while (end < text.length && text[end].isLetterOrDigit()) end++
    if (start < end) {
        ic.setSelection(start, end)
    }
}

private fun extendSelection(service: NacreInputMethodService, dx: Int, dy: Int) {
    val ic = service.currentInputConnection ?: return
    val now = System.currentTimeMillis()
    repeat(abs(dx)) {
        val code = if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, KeyEvent.META_SHIFT_ON))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, KeyEvent.META_SHIFT_ON))
    }
    repeat(abs(dy)) {
        val code = if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, KeyEvent.META_SHIFT_ON))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, KeyEvent.META_SHIFT_ON))
    }
}

private fun cdGain(velocityDpPerSec: Float): Float {
    val gMin = 1.5f
    val gMax = 6.0f
    val k = 0.015f
    val vMid = 300f
    return gMin + (gMax - gMin) / (1f + exp(-k * (velocityDpPerSec - vMid)).toFloat())
}
