package space.manus.nacre.ime.keyboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import space.manus.nacre.config.KeyAction
import space.manus.nacre.config.KeyDef
import space.manus.nacre.ime.NacreInputMethodService
import space.manus.nacre.ime.input.SwipeDirection
import kotlin.math.abs

// Colors are read from service.currentTheme at runtime

@Composable
fun KeyView(
    keyDef: KeyDef,
    service: NacreInputMethodService,
    modifier: Modifier = Modifier,
    row: Int = 0,
    column: Int = 0,
    heightDp: Float = 52f,
) {
    var isPressed by remember { mutableStateOf(false) }
    var bsRepeating by remember { mutableStateOf(false) }
    var showLongPressPopup by remember { mutableStateOf(false) }
    var longPressChar by remember { mutableStateOf("") }

    // BS long-press repeat delete
    LaunchedEffect(bsRepeating) {
        if (!bsRepeating) return@LaunchedEffect
        delay(80L) // Initial pause before repeat starts
        while (bsRepeating) {
            val wasComposing = service.inputEngine.composingKana.isNotEmpty()
            service.inputEngine.processAction(KeyAction.Backspace)
            service.feedbackManager.onKeyPress(KeyAction.Backspace)
            val isNowEmpty = service.inputEngine.composingKana.isEmpty()
            if (wasComposing && isNowEmpty) {
                // Composing text just cleared — pause before deleting committed text
                delay(400L)
            } else {
                delay(50L) // Fast repeat for smooth continuous delete
            }
        }
    }
    val scale by animateFloatAsState(if (isPressed) 0.95f else 1f, label = "keyScale")
    val swipeThresholdPx = with(LocalDensity.current) { 12.dp.toPx() }

    // Theme colors
    val theme = service.currentTheme
    val KeyBg = Color(theme.keyBackground.toInt())
    val KeyBgPressed = Color(theme.keyBackgroundPressed.toInt())
    val KeyText = Color(theme.keyText.toInt())
    val KeyTextSwipe = Color(theme.keyTextSwipe.toInt())
    val GlowColor = Color(theme.accent.toInt())
    val KeyEdgeLight = Color(theme.surface.toInt())

    val isFnKey = keyDef.action is KeyAction.Fn ||
        keyDef.action is KeyAction.FnPage2

    val isAltKey = keyDef.action is KeyAction.Alt
    val isAltActive = isAltKey && service.layerManager.isAltActive

    val bgColor = when {
        isPressed -> KeyBgPressed
        isAltActive -> KeyBgPressed
        isFnKey -> KeyBg
        else -> KeyBg
    }

    val textColor = when {
        isPressed -> GlowColor
        isAltActive -> GlowColor
        else -> KeyText
    }

    // Mechanical glow effect — combines press glow + lighting engine
    val lighting = service.keyLighting
    val lightingColor = lighting.getKeyColor(keyDef.primary, row, column)

    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.8f else 0f,
        animationSpec = tween(durationMillis = if (isPressed) 30 else 200),
        label = "glow",
    )
    val pressGlow = if (isPressed) GlowColor.copy(alpha = glowAlpha) else Color.Transparent
    val borderColor = if (pressGlow != Color.Transparent) pressGlow
        else if (lightingColor != Color.Transparent) lightingColor
        else Color.Transparent

    val shape = RoundedCornerShape(6.dp)

    Box(
        modifier = modifier
            .then(if (heightDp > 0f) Modifier.height(heightDp.dp) else Modifier.fillMaxHeight())
            .padding(horizontal = 2.dp, vertical = 1.5.dp)
            .scale(scale)
            .clip(shape)
            // Outer shell — bottom edge highlight for mechanical depth
            .background(if (isPressed) KeyBgPressed else KeyEdgeLight)
            .padding(bottom = 1.5.dp) // Creates the "keycap edge" effect
            .clip(shape)
            .background(bgColor)
            .then(
                if (borderColor != Color.Transparent) {
                    Modifier.border(
                        width = 1.dp,
                        color = borderColor,
                        shape = shape,
                    )
                } else {
                    // Subtle edge border for non-glowing keys
                    Modifier.border(
                        width = 0.5.dp,
                        color = KeyEdgeLight.copy(alpha = 0.5f),
                        shape = shape,
                    )
                }
            )
            .semantics {
                contentDescription = when (keyDef.action) {
                    is KeyAction.Backspace -> "Backspace"
                    is KeyAction.Enter -> "Enter"
                    is KeyAction.Space -> "Space"
                    is KeyAction.Tab -> "Tab"
                    is KeyAction.Escape -> "Escape"
                    is KeyAction.Fn -> "Function"
                    is KeyAction.FnPage2 -> "Function page 2"
                    is KeyAction.SwitchIme -> "Switch keyboard"
                    is KeyAction.Shift -> "Shift"
                    is KeyAction.ToggleJapanese -> "Toggle Japanese"
                    is KeyAction.Emoji -> "Emoji"
                    is KeyAction.Alt -> "Alt"
                    is KeyAction.Henkan -> "Henkan"
                    else -> keyDef.label
                }
            }
            .pointerInput(keyDef) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    val downTime = System.currentTimeMillis()

                    val isFnAction = keyDef.action is KeyAction.Fn
                    if (isFnAction) {
                        service.layerManager.fnDown()
                    }

                    var totalDragX = 0f
                    var totalDragY = 0f
                    var wasDragged = false
                    var longPressHandled = false

                    try {
                        while (true) {
                            val event = withTimeoutOrNull(
                                if (longPressHandled) Long.MAX_VALUE
                                else maxOf(1L, 350L - (System.currentTimeMillis() - downTime)),
                            ) {
                                awaitPointerEvent()
                            }

                            if (event == null) {
                                // Timeout = long press threshold reached while finger is still down
                                if (!wasDragged && !longPressHandled) {
                                    longPressHandled = true
                                    val feedback = service.feedbackManager
                                    feedback.onLongPress()
                                    if (keyDef.action is KeyAction.Fn) {
                                        service.inputEngine.processAction(KeyAction.Escape)
                                    } else if (keyDef.action is KeyAction.Backspace) {
                                        // BS long press: start repeat delete via isPressed flag
                                        bsRepeating = true
                                    } else if (keyDef.action is KeyAction.SwitchIme) {
                                        // GL long press: open emoji panel
                                        service.inputEngine.processAction(KeyAction.Emoji)
                                    } else if (keyDef.action is KeyAction.Space) {
                                        // Space long press: start voice input
                                        val lang = if (service.layerManager.isJapanese) "ja-JP" else "en-US"
                                        service.voiceInputManager.startListening(lang)
                                    } else if (keyDef.longPress != null) {
                                        // SPEC: Gboard-style popup bubble for long-press character
                                        longPressChar = keyDef.longPress!!
                                        showLongPressPopup = true
                                        service.inputEngine.processAction(KeyAction.Text(keyDef.longPress!!))
                                    }
                                }
                                continue
                            }

                            val change = event.changes.firstOrNull() ?: break

                            if (change.pressed) {
                                val delta = change.positionChange()
                                totalDragX += delta.x
                                totalDragY += delta.y

                                if (abs(totalDragX) > swipeThresholdPx ||
                                    abs(totalDragY) > swipeThresholdPx
                                ) {
                                    wasDragged = true
                                }
                                change.consume()
                            } else {
                                change.consume()
                                break
                            }
                        }
                    } finally {
                        isPressed = false
                        bsRepeating = false
                        showLongPressPopup = false
                    }

                    val feedback = service.feedbackManager

                    val holdDuration = System.currentTimeMillis() - downTime

                    // Fn key: handle hold vs tap
                    if (isFnAction) {
                        if (longPressHandled) {
                            // Long press = Esc already handled, return to base
                            service.layerManager.fnUp(wasTap = false)
                        } else if (wasDragged) {
                            service.layerManager.fnUp(wasTap = false)
                            val direction = if (abs(totalDragX) > abs(totalDragY)) {
                                if (totalDragX > 0) SwipeDirection.Right else SwipeDirection.Left
                            } else {
                                if (totalDragY > 0) SwipeDirection.Down else SwipeDirection.Up
                            }
                            feedback.onSwipe()
                            service.inputEngine.processSwipe(keyDef, direction)
                        } else {
                            // Short tap (<350ms): toggle Fn (fnDown already set Fn1)
                            // fnUp(wasTap=true) keeps Fn1
                            service.layerManager.fnUp(wasTap = true)
                            feedback.onKeyPress(keyDef.action)
                            feedback.onLayerChange()
                        }
                    } else when {
                        wasDragged -> {
                            val direction = if (abs(totalDragX) > abs(totalDragY)) {
                                if (totalDragX > 0) SwipeDirection.Right else SwipeDirection.Left
                            } else {
                                if (totalDragY > 0) SwipeDirection.Down else SwipeDirection.Up
                            }
                            feedback.onSwipe()
                            service.inputEngine.processSwipe(keyDef, direction)
                        }
                        longPressHandled -> {
                            // Already handled during hold — do nothing on release
                        }
                        else -> {
                            feedback.onKeyPress(keyDef.action)
                            lighting.onKeyPress(keyDef.primary, column)
                            service.inputEngine.processKey(keyDef)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Main label
        if (keyDef.action is KeyAction.ToggleJapanese) {
            // If label is custom (e.g. "あa1"), show it as-is
            // Otherwise show "あ" when Japanese / "A" when English
            val toggleLabel = if (keyDef.label != keyDef.primary && keyDef.label.length > 1) {
                keyDef.label
            } else {
                if (service.layerManager.isJapanese) "あ" else "A"
            }
            Text(
                text = toggleLabel,
                color = textColor,
                fontSize = if (toggleLabel.length > 2) 11.sp else 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                textAlign = TextAlign.Center,
            )
        } else {
            val displayLabel = if (service.layerManager.isJapanese) {
                when (keyDef.label) {
                    "," -> "、"
                    "." -> "。"
                    // かな入力時: アルファベットキーを大文字表示（モード判別用）
                    else -> if (keyDef.primary.length == 1 && keyDef.primary[0] in 'a'..'z') {
                        keyDef.label.uppercase()
                    } else if (service.layerManager.isShifted && keyDef.primary.length == 1) {
                        keyDef.label.uppercase()
                    } else {
                        keyDef.label
                    }
                }
            } else if (service.layerManager.isShifted && keyDef.primary.length == 1) {
                keyDef.label.uppercase()
            } else {
                keyDef.label
            }
            Text(
                text = displayLabel,
                color = textColor,
                fontSize = if (keyDef.label.length > 2) 11.sp else 15.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                textAlign = TextAlign.Center,
                style = if (isPressed) {
                    androidx.compose.ui.text.TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = GlowColor,
                            blurRadius = 12f,
                        ),
                    )
                } else {
                    androidx.compose.ui.text.TextStyle.Default
                },
            )
        }

        // SPEC: Gboard-style popup bubble for long-press characters
        if (showLongPressPopup && longPressChar.isNotEmpty()) {
            Popup(
                alignment = Alignment.TopCenter,
                offset = androidx.compose.ui.unit.IntOffset(0, with(LocalDensity.current) { -56.dp.roundToPx() }),
                properties = PopupProperties(focusable = false),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp, 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF3A3A6A)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = longPressChar,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // Swipe hints
        if (keyDef.swipeUp != null) {
            Text(
                text = keyDef.swipeUp!!,
                color = KeyTextSwipe,
                fontSize = 9.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 3.dp, top = 1.dp),
            )
        }
    }
}

/** Minimal globe outline icon drawn with Canvas (no icon dependency needed) */
@Composable
private fun GlobeIcon(color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 1.5f * density,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension / 2f - stroke.width

        // Outer circle
        drawCircle(color = color, radius = r, style = stroke)
        // Vertical ellipse (meridian)
        drawOval(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(cx - r * 0.35f, cy - r),
            size = androidx.compose.ui.geometry.Size(r * 0.7f, r * 2f),
            style = stroke,
        )
        // Horizontal line (equator)
        drawLine(color, androidx.compose.ui.geometry.Offset(cx - r, cy), androidx.compose.ui.geometry.Offset(cx + r, cy), strokeWidth = stroke.width)
        // Upper latitude
        drawLine(color, androidx.compose.ui.geometry.Offset(cx - r * 0.85f, cy - r * 0.45f), androidx.compose.ui.geometry.Offset(cx + r * 0.85f, cy - r * 0.45f), strokeWidth = stroke.width)
        // Lower latitude
        drawLine(color, androidx.compose.ui.geometry.Offset(cx - r * 0.85f, cy + r * 0.45f), androidx.compose.ui.geometry.Offset(cx + r * 0.85f, cy + r * 0.45f), strokeWidth = stroke.width)
    }
}
