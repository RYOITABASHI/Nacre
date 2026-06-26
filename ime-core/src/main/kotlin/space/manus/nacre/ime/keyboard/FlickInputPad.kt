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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import space.manus.nacre.config.KeyAction
import space.manus.nacre.config.KeyDef
import space.manus.nacre.ime.NacreInputMethodService
import space.manus.nacre.ime.input.DakutenType
import space.manus.nacre.ime.input.FlickEngine
import kotlin.math.abs

/** Input mode for the 12-key pad. Selected directly by the iOS-style ☆123 / ABC / あいう keys. */
private enum class FlickMode { Kana, Alpha, Numbers }

/**
 * Main 12-key input pad composable (iOS テンキー-style).
 * Left column = direct mode keys (☆123 numbers / ABC alpha / あいう kana) like iOS —
 * one tap jumps straight to a mode, no cycling. Multi-tap (連打) on the centre keys
 * cycles within a row (か→き→く→け→こ). Cursor lives in the bottom row + Flex Pointer.
 * Layout: candidate bar → 4×5 grid → 5-col bottom row.
 */
@Composable
fun FlickInputPad(
    service: NacreInputMethodService,
    onFlexPointer: () -> Unit = {},
    onClipboard: () -> Unit = {},
) {
    val theme = service.currentTheme
    val bgColor = Color(theme.background.toInt())
    var flickMode by remember { mutableStateOf(FlickMode.Kana) }

    // Emoji overlay
    var showEmoji by remember { mutableStateOf(false) }
    if (showEmoji) {
        EmojiPanel(service = service, onDismiss = { showEmoji = false })
        return
    }
    // Symbols overlay
    var showSymbols by remember { mutableStateOf(false) }
    if (showSymbols) {
        SymbolsPanel(service = service, onDismiss = { showSymbols = false })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor),
    ) {
        // Candidate bar only (no toolbar — functions covered by grid keys)
        CandidateBar(service = service)

        // Grid: switches based on flickMode. Mode is set directly (iOS-style),
        // not cycled — ☆123 / ABC / あいう jump straight to a mode.
        val onSetMode: (FlickMode) -> Unit = { flickMode = it }
        when (flickMode) {
            FlickMode.Kana -> FlickKanaGrid(
                service = service,
                flickMode = flickMode,
                onSetMode = onSetMode,
                onSymbols = { showSymbols = true },
                onEmoji = { showEmoji = true },
                onFlexPointer = onFlexPointer,
                onClipboard = onClipboard,
            )
            FlickMode.Alpha -> FlickAlphaGrid(
                service = service,
                flickMode = flickMode,
                onSetMode = onSetMode,
                onSymbols = { showSymbols = true },
                onEmoji = { showEmoji = true },
                onFlexPointer = onFlexPointer,
                onClipboard = onClipboard,
            )
            FlickMode.Numbers -> FlickNumberGrid(
                service = service,
                flickMode = flickMode,
                onSetMode = onSetMode,
                onSymbols = { showSymbols = true },
                onEmoji = { showEmoji = true },
                onFlexPointer = onFlexPointer,
                onClipboard = onClipboard,
            )
        }

        // 5-column bottom row: ◀ / ▶ / 変換 / Paste / Alt
        FlickBottomRow(service = service)
    }
}

// ─────────────────────────────────────────────────────────────────
// Kana 4×5 Gboard-style grid (all rows 50dp uniform)
// ─────────────────────────────────────────────────────────────────

private const val FLICK_ROW_HEIGHT = 50f
private const val SIDE_WEIGHT = 0.8f

@Composable
private fun FlickKanaGrid(
    service: NacreInputMethodService,
    flickMode: FlickMode,
    onSetMode: (FlickMode) -> Unit,
    onSymbols: () -> Unit,
    onEmoji: () -> Unit,
    onFlexPointer: () -> Unit,
    onClipboard: () -> Unit,
) {
    val kanaKeys = FlickEngine.kanaKeys
    val h = FLICK_ROW_HEIGHT.dp
    val sw = SIDE_WEIGHT

    val punctKey = FlickEngine.FlickKey(
        id = "punct", label = "、。",
        tap = "、", left = "。", up = "？", right = "！", down = "…",
        tapCycle = listOf("。", "、", "！", "？", "ー", "〜", "…", "「", "」", "・"),
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        // Row 1: ☆123 | あ | か | さ | ⌫
        Row(modifier = Modifier.fillMaxWidth().height(h)) {
            ModeSwitchKey(label = "☆123", active = flickMode == FlickMode.Numbers, modifier = Modifier.weight(sw), service = service, onClick = { onSetMode(FlickMode.Numbers) })
            FlickKeyView(flickKey = kanaKeys[0], service = service, modifier = Modifier.weight(1f), row = 0, column = 1)
            FlickKeyView(flickKey = kanaKeys[1], service = service, modifier = Modifier.weight(1f), row = 0, column = 2)
            FlickKeyView(flickKey = kanaKeys[2], service = service, modifier = Modifier.weight(1f), row = 0, column = 3)
            KeyView(keyDef = KeyDef("⌫", action = KeyAction.Backspace), service = service, modifier = Modifier.weight(sw), row = 0, column = 4, heightDp = FLICK_ROW_HEIGHT)
        }
        // Row 2: ABC | た | な | は | 空白
        Row(modifier = Modifier.fillMaxWidth().height(h)) {
            ModeSwitchKey(label = "ABC", active = flickMode == FlickMode.Alpha, modifier = Modifier.weight(sw), service = service, onClick = { onSetMode(FlickMode.Alpha) })
            FlickKeyView(flickKey = kanaKeys[3], service = service, modifier = Modifier.weight(1f), row = 1, column = 1)
            FlickKeyView(flickKey = kanaKeys[4], service = service, modifier = Modifier.weight(1f), row = 1, column = 2)
            FlickKeyView(flickKey = kanaKeys[5], service = service, modifier = Modifier.weight(1f), row = 1, column = 3)
            FlickSpaceKey(service = service, onFlexPointer = onFlexPointer, modifier = Modifier.weight(sw))
        }
        // Row 3: あいう | ま | や | ら | 改行
        Row(modifier = Modifier.fillMaxWidth().height(h)) {
            ModeSwitchKey(label = "あいう", active = flickMode == FlickMode.Kana, modifier = Modifier.weight(sw), service = service, onClick = { onSetMode(FlickMode.Kana) })
            FlickKeyView(flickKey = kanaKeys[6], service = service, modifier = Modifier.weight(1f), row = 2, column = 1)
            FlickKeyView(flickKey = kanaKeys[7], service = service, modifier = Modifier.weight(1f), row = 2, column = 2)
            FlickKeyView(flickKey = kanaKeys[8], service = service, modifier = Modifier.weight(1f), row = 2, column = 3)
            KeyView(keyDef = KeyDef("改行", action = KeyAction.Enter), service = service, modifier = Modifier.weight(sw), row = 2, column = 4, heightDp = FLICK_ROW_HEIGHT)
        }
        // Row 4: 絵記 | ゛゜ | わ | 、。 | 📋 (clipboard)
        Row(modifier = Modifier.fillMaxWidth().height(h)) {
            SymbolEmojiKey(label = "絵記", modifier = Modifier.weight(sw), service = service, onTap = onEmoji, onLongPress = onSymbols)
            DakutenKeyView(service = service, modifier = Modifier.weight(1f), row = 3, column = 1)
            FlickKeyView(flickKey = kanaKeys[9], service = service, modifier = Modifier.weight(1f), row = 3, column = 2)
            FlickKeyView(flickKey = punctKey, service = service, modifier = Modifier.weight(1f), row = 3, column = 3)
            FlickClipboardKey(service = service, onClipboard = onClipboard, modifier = Modifier.weight(sw))
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Alpha grid (English letters via flick, Gboard-style)
// ─────────────────────────────────────────────────────────────────

@Composable
private fun FlickAlphaGrid(
    service: NacreInputMethodService,
    flickMode: FlickMode,
    onSetMode: (FlickMode) -> Unit,
    onSymbols: () -> Unit,
    onEmoji: () -> Unit,
    onFlexPointer: () -> Unit,
    onClipboard: () -> Unit,
) {
    val h = FLICK_ROW_HEIGHT.dp
    val sw = SIDE_WEIGHT

    // Alpha keys: flick for directional input, multi-tap for garakei-style cycling
    val alphaKeys = listOf(
        FlickEngine.FlickKey("@", "@", "@", "#", "&", "_", "%",
            tapCycle = listOf("@", "#", "&", "_", "%")),
        FlickEngine.FlickKey("abc", "abc", "a", left = "a", up = "b", right = "c",
            tapCycle = listOf("a", "b", "c")),
        FlickEngine.FlickKey("def", "def", "d", left = "d", up = "e", right = "f",
            tapCycle = listOf("d", "e", "f")),
        FlickEngine.FlickKey("ghi", "ghi", "g", left = "g", up = "h", right = "i",
            tapCycle = listOf("g", "h", "i")),
        FlickEngine.FlickKey("jkl", "jkl", "j", left = "j", up = "k", right = "l",
            tapCycle = listOf("j", "k", "l")),
        FlickEngine.FlickKey("mno", "mno", "m", left = "m", up = "n", right = "o",
            tapCycle = listOf("m", "n", "o")),
        FlickEngine.FlickKey("pqrs", "pqrs", "p", left = "q", up = "r", right = "s",
            tapCycle = listOf("p", "q", "r", "s")),
        FlickEngine.FlickKey("tuv", "tuv", "t", left = "t", up = "u", right = "v",
            tapCycle = listOf("t", "u", "v")),
        FlickEngine.FlickKey("wxyz", "wxyz", "w", left = "x", up = "y", right = "z",
            tapCycle = listOf("w", "x", "y", "z")),
    )

    // Punctuation for alpha mode
    val alphaPunct = FlickEngine.FlickKey(
        id = "apunct", label = ".,!?",
        tap = ".", left = ",", up = "!", right = "?", down = "'",
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        // Row 1: ☆123 | @#& | abc | def | ⌫
        Row(modifier = Modifier.fillMaxWidth().height(h)) {
            ModeSwitchKey(label = "☆123", active = flickMode == FlickMode.Numbers, modifier = Modifier.weight(sw), service = service, onClick = { onSetMode(FlickMode.Numbers) })
            FlickKeyView(flickKey = alphaKeys[0], service = service, modifier = Modifier.weight(1f), row = 0, column = 1)
            FlickKeyView(flickKey = alphaKeys[1], service = service, modifier = Modifier.weight(1f), row = 0, column = 2)
            FlickKeyView(flickKey = alphaKeys[2], service = service, modifier = Modifier.weight(1f), row = 0, column = 3)
            KeyView(keyDef = KeyDef("⌫", action = KeyAction.Backspace), service = service, modifier = Modifier.weight(sw), row = 0, column = 4, heightDp = FLICK_ROW_HEIGHT)
        }
        // Row 2: ABC | ghi | jkl | mno | 空白
        Row(modifier = Modifier.fillMaxWidth().height(h)) {
            ModeSwitchKey(label = "ABC", active = flickMode == FlickMode.Alpha, modifier = Modifier.weight(sw), service = service, onClick = { onSetMode(FlickMode.Alpha) })
            FlickKeyView(flickKey = alphaKeys[3], service = service, modifier = Modifier.weight(1f), row = 1, column = 1)
            FlickKeyView(flickKey = alphaKeys[4], service = service, modifier = Modifier.weight(1f), row = 1, column = 2)
            FlickKeyView(flickKey = alphaKeys[5], service = service, modifier = Modifier.weight(1f), row = 1, column = 3)
            FlickSpaceKey(service = service, onFlexPointer = onFlexPointer, modifier = Modifier.weight(sw))
        }
        // Row 3: あいう | pqrs | tuv | wxyz | 改行
        Row(modifier = Modifier.fillMaxWidth().height(h)) {
            ModeSwitchKey(label = "あいう", active = flickMode == FlickMode.Kana, modifier = Modifier.weight(sw), service = service, onClick = { onSetMode(FlickMode.Kana) })
            FlickKeyView(flickKey = alphaKeys[6], service = service, modifier = Modifier.weight(1f), row = 2, column = 1)
            FlickKeyView(flickKey = alphaKeys[7], service = service, modifier = Modifier.weight(1f), row = 2, column = 2)
            FlickKeyView(flickKey = alphaKeys[8], service = service, modifier = Modifier.weight(1f), row = 2, column = 3)
            KeyView(keyDef = KeyDef("改行", action = KeyAction.Enter), service = service, modifier = Modifier.weight(sw), row = 2, column = 4, heightDp = FLICK_ROW_HEIGHT)
        }
        // Row 4: 絵記 | Shift | - | .,!? | 📋 (clipboard)
        Row(modifier = Modifier.fillMaxWidth().height(h)) {
            SymbolEmojiKey(label = "絵記", modifier = Modifier.weight(sw), service = service, onTap = onEmoji, onLongPress = onSymbols)
            KeyView(keyDef = KeyDef("Shift", action = KeyAction.Shift), service = service, modifier = Modifier.weight(1f), row = 3, column = 1, heightDp = FLICK_ROW_HEIGHT)
            KeyView(keyDef = KeyDef("-", swipeUp = "/", swipeDown = "\\"), service = service, modifier = Modifier.weight(1f), row = 3, column = 2, heightDp = FLICK_ROW_HEIGHT)
            FlickKeyView(flickKey = alphaPunct, service = service, modifier = Modifier.weight(1f), row = 3, column = 3)
            FlickClipboardKey(service = service, onClipboard = onClipboard, modifier = Modifier.weight(sw))
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Number grid (5-column with side keys)
// ─────────────────────────────────────────────────────────────────

@Composable
private fun FlickNumberGrid(
    service: NacreInputMethodService,
    flickMode: FlickMode,
    onSetMode: (FlickMode) -> Unit,
    onSymbols: () -> Unit,
    onEmoji: () -> Unit,
    onFlexPointer: () -> Unit,
    onClipboard: () -> Unit,
) {
    val h = FLICK_ROW_HEIGHT.dp
    val sw = SIDE_WEIGHT

    Column(modifier = Modifier.fillMaxWidth()) {
        // Row 1: ☆123 | 1 | 2 | 3 | ⌫
        Row(modifier = Modifier.fillMaxWidth().height(h)) {
            ModeSwitchKey(label = "☆123", active = flickMode == FlickMode.Numbers, modifier = Modifier.weight(sw), service = service, onClick = { onSetMode(FlickMode.Numbers) })
            KeyView(keyDef = KeyDef("1"), service = service, modifier = Modifier.weight(1f), row = 0, column = 1, heightDp = FLICK_ROW_HEIGHT)
            KeyView(keyDef = KeyDef("2"), service = service, modifier = Modifier.weight(1f), row = 0, column = 2, heightDp = FLICK_ROW_HEIGHT)
            KeyView(keyDef = KeyDef("3"), service = service, modifier = Modifier.weight(1f), row = 0, column = 3, heightDp = FLICK_ROW_HEIGHT)
            KeyView(keyDef = KeyDef("⌫", action = KeyAction.Backspace), service = service, modifier = Modifier.weight(sw), row = 0, column = 4, heightDp = FLICK_ROW_HEIGHT)
        }
        // Row 2: ABC | 4 | 5 | 6 | 空白
        Row(modifier = Modifier.fillMaxWidth().height(h)) {
            ModeSwitchKey(label = "ABC", active = flickMode == FlickMode.Alpha, modifier = Modifier.weight(sw), service = service, onClick = { onSetMode(FlickMode.Alpha) })
            KeyView(keyDef = KeyDef("4"), service = service, modifier = Modifier.weight(1f), row = 1, column = 1, heightDp = FLICK_ROW_HEIGHT)
            KeyView(keyDef = KeyDef("5"), service = service, modifier = Modifier.weight(1f), row = 1, column = 2, heightDp = FLICK_ROW_HEIGHT)
            KeyView(keyDef = KeyDef("6"), service = service, modifier = Modifier.weight(1f), row = 1, column = 3, heightDp = FLICK_ROW_HEIGHT)
            FlickSpaceKey(service = service, onFlexPointer = onFlexPointer, modifier = Modifier.weight(sw))
        }
        // Row 3: あいう | 7 | 8 | 9 | 改行
        Row(modifier = Modifier.fillMaxWidth().height(h)) {
            ModeSwitchKey(label = "あいう", active = flickMode == FlickMode.Kana, modifier = Modifier.weight(sw), service = service, onClick = { onSetMode(FlickMode.Kana) })
            KeyView(keyDef = KeyDef("7"), service = service, modifier = Modifier.weight(1f), row = 2, column = 1, heightDp = FLICK_ROW_HEIGHT)
            KeyView(keyDef = KeyDef("8"), service = service, modifier = Modifier.weight(1f), row = 2, column = 2, heightDp = FLICK_ROW_HEIGHT)
            KeyView(keyDef = KeyDef("9"), service = service, modifier = Modifier.weight(1f), row = 2, column = 3, heightDp = FLICK_ROW_HEIGHT)
            KeyView(keyDef = KeyDef("改行", action = KeyAction.Enter), service = service, modifier = Modifier.weight(sw), row = 2, column = 4, heightDp = FLICK_ROW_HEIGHT)
        }
        // Row 4: 絵記 | + | 0 | . | 📋 (clipboard)
        Row(modifier = Modifier.fillMaxWidth().height(h)) {
            SymbolEmojiKey(label = "絵記", modifier = Modifier.weight(sw), service = service, onTap = onEmoji, onLongPress = onSymbols)
            KeyView(keyDef = KeyDef("+", swipeUp = "-", swipeDown = "="), service = service, modifier = Modifier.weight(1f), row = 3, column = 1, heightDp = FLICK_ROW_HEIGHT)
            KeyView(keyDef = KeyDef("0"), service = service, modifier = Modifier.weight(1f), row = 3, column = 2, heightDp = FLICK_ROW_HEIGHT)
            KeyView(keyDef = KeyDef(".", swipeUp = ",", swipeDown = ":"), service = service, modifier = Modifier.weight(1f), row = 3, column = 3, heightDp = FLICK_ROW_HEIGHT)
            FlickClipboardKey(service = service, onClipboard = onClipboard, modifier = Modifier.weight(sw))
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Symbol/Emoji key (tap=symbols, long-press=emoji)
// ─────────────────────────────────────────────────────────────────

@Composable
private fun SymbolEmojiKey(
    label: String,
    service: NacreInputMethodService,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val theme = service.currentTheme
    val keyBg = Color(theme.keyBackground.toInt())
    val keyText = Color(theme.keyText.toInt())
    val accentColor = Color(theme.accent.toInt())
    val surfaceColor = Color(theme.surface.toInt())
    val shape = RoundedCornerShape(6.dp)
    var isPressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 1.dp, vertical = 1.dp)
            .clip(shape)
            .background(if (isPressed) Color(theme.keyBackgroundPressed.toInt()) else surfaceColor)
            .padding(bottom = 1.5.dp)
            .clip(shape)
            .background(if (isPressed) Color(theme.keyBackgroundPressed.toInt()) else keyBg)
            .border(0.5.dp, surfaceColor.copy(alpha = 0.5f), shape)
            .pointerInput(label) {
                awaitEachGesture {
                    val downTime = System.currentTimeMillis()
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    var longPressHandled = false

                    try {
                        while (true) {
                            val elapsed = System.currentTimeMillis() - downTime
                            val timeout = maxOf(1L, 350L - elapsed)
                            val event = withTimeoutOrNull(timeout) { awaitPointerEvent() }

                            if (event == null && !longPressHandled) {
                                longPressHandled = true
                                service.feedbackManager.onLongPress()
                                continue
                            }

                            val change = event?.changes?.firstOrNull() ?: break
                            if (!change.pressed) { change.consume(); break }
                            change.consume()
                        }
                    } finally {
                        isPressed = false
                    }

                    if (longPressHandled) {
                        onLongPress()
                    } else {
                        onTap()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (isPressed) accentColor else keyText,
            fontSize = if (label.length > 2) 10.sp else 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// Space key — tap inserts a space; long-press opens the Flex Pointer
// trackpad (matches iOS's "long-press space = cursor trackpad" UX).
// ─────────────────────────────────────────────────────────────────

@Composable
private fun FlickSpaceKey(
    service: NacreInputMethodService,
    onFlexPointer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = service.currentTheme
    val keyBg = Color(theme.keyBackground.toInt())
    val keyText = Color(theme.keyText.toInt())
    val accentColor = Color(theme.accent.toInt())
    val surfaceColor = Color(theme.surface.toInt())
    val shape = RoundedCornerShape(6.dp)
    var isPressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 1.dp, vertical = 1.dp)
            .clip(shape)
            .background(if (isPressed) Color(theme.keyBackgroundPressed.toInt()) else surfaceColor)
            .padding(bottom = 1.5.dp)
            .clip(shape)
            .background(if (isPressed) Color(theme.keyBackgroundPressed.toInt()) else keyBg)
            .border(0.5.dp, surfaceColor.copy(alpha = 0.5f), shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val downTime = System.currentTimeMillis()
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    var longPressHandled = false
                    try {
                        while (true) {
                            val elapsed = System.currentTimeMillis() - downTime
                            val timeout = maxOf(1L, 350L - elapsed)
                            val event = withTimeoutOrNull(timeout) { awaitPointerEvent() }
                            if (event == null && !longPressHandled) {
                                longPressHandled = true
                                service.feedbackManager.onLongPress()
                                continue
                            }
                            val change = event?.changes?.firstOrNull() ?: break
                            if (!change.pressed) { change.consume(); break }
                            change.consume()
                        }
                    } finally {
                        isPressed = false
                    }
                    if (longPressHandled) {
                        onFlexPointer()
                    } else {
                        service.inputEngine.processAction(KeyAction.Space)
                        service.feedbackManager.onKeyPress(KeyAction.Space)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "空白",
            color = if (isPressed) accentColor else keyText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// Clipboard-stock key — opens the saved clipboard history panel (pinned clips).
// ─────────────────────────────────────────────────────────────────

@Composable
private fun FlickClipboardKey(
    service: NacreInputMethodService,
    onClipboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = service.currentTheme
    val keyBg = Color(theme.keyBackground.toInt())
    val keyText = Color(theme.keyText.toInt())
    val surfaceColor = Color(theme.surface.toInt())
    val shape = RoundedCornerShape(6.dp)
    var isPressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 1.dp, vertical = 1.dp)
            .clip(shape)
            .background(if (isPressed) Color(theme.keyBackgroundPressed.toInt()) else surfaceColor)
            .padding(bottom = 1.5.dp)
            .clip(shape)
            .background(if (isPressed) Color(theme.keyBackgroundPressed.toInt()) else keyBg)
            .border(0.5.dp, surfaceColor.copy(alpha = 0.5f), shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    while (true) {
                        val event = withTimeoutOrNull(Long.MAX_VALUE) { awaitPointerEvent() } ?: break
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) { change.consume(); break }
                        change.consume()
                    }
                    isPressed = false
                    onClipboard()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "📋",
            color = keyText,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// iOS-style direct mode key (☆123 / ABC / あいう); highlighted when active
// ─────────────────────────────────────────────────────────────────

@Composable
private fun ModeSwitchKey(
    label: String,
    service: NacreInputMethodService,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val theme = service.currentTheme
    val keyBg = Color(theme.keyBackground.toInt())
    val keyBgPressed = Color(theme.keyBackgroundPressed.toInt())
    val keyText = Color(theme.keyText.toInt())
    val accentColor = Color(theme.accent.toInt())
    val surfaceColor = Color(theme.surface.toInt())
    val shape = RoundedCornerShape(6.dp)

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 1.dp, vertical = 1.dp)
            .clip(shape)
            .background(surfaceColor)
            .padding(bottom = 1.5.dp)
            .clip(shape)
            .background(if (active) keyBgPressed else keyBg)
            .border(
                width = if (active) 1.dp else 0.5.dp,
                color = if (active) accentColor else surfaceColor.copy(alpha = 0.5f),
                shape = shape,
            )
            .pointerInput(label) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = withTimeoutOrNull(Long.MAX_VALUE) { awaitPointerEvent() } ?: break
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) { change.consume(); break }
                        change.consume()
                    }
                    onClick()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) accentColor else keyText,
            fontSize = if (label.length > 2) 10.sp else 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// Individual flick key
// ─────────────────────────────────────────────────────────────────

@Composable
private fun FlickKeyView(
    flickKey: FlickEngine.FlickKey,
    service: NacreInputMethodService,
    modifier: Modifier = Modifier,
    row: Int = 0,
    column: Int = 0,
) {
    var isPressed by remember { mutableStateOf(false) }
    var flickDir by remember { mutableStateOf(FlickEngine.Direction.Tap) }
    var showPopup by remember { mutableStateOf(false) }
    var popupKana by remember { mutableStateOf("") }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 30),
        label = "flickKeyScale",
    )

    val theme = service.currentTheme
    val keyBg = Color(theme.keyBackground.toInt())
    val keyBgPressed = Color(theme.keyBackgroundPressed.toInt())
    val keyText = Color(theme.keyText.toInt())
    val accentColor = Color(theme.accent.toInt())
    val surfaceColor = Color(theme.surface.toInt())

    val lighting = service.keyLighting
    // Read animationTick to trigger recomposition on lighting updates
    @Suppress("UNUSED_VARIABLE")
    val tick = lighting.animationTick
    val lightingColor = lighting.getKeyColor(flickKey.id, row, column)

    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.8f else 0f,
        animationSpec = tween(durationMillis = if (isPressed) 30 else 200),
        label = "flickGlow",
    )
    val pressGlow = if (isPressed) accentColor.copy(alpha = glowAlpha) else Color.Transparent
    val borderColor = when {
        pressGlow != Color.Transparent -> pressGlow
        lightingColor != Color.Transparent -> lightingColor
        else -> Color.Transparent
    }

    val shape = RoundedCornerShape(6.dp)
    val flickThresholdPx = with(LocalDensity.current) { 10.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 1.dp, vertical = 1.dp)
            .scale(scale)
            .clip(shape)
            .background(if (isPressed) keyBgPressed else surfaceColor)
            .padding(bottom = 1.5.dp)
            .clip(shape)
            .background(if (isPressed) keyBgPressed else keyBg)
            .then(
                if (borderColor != Color.Transparent) {
                    Modifier.border(width = 1.dp, color = borderColor, shape = shape)
                } else {
                    Modifier.border(
                        width = 0.5.dp,
                        color = surfaceColor.copy(alpha = 0.5f),
                        shape = shape,
                    )
                }
            )
            .pointerInput(flickKey) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    flickDir = FlickEngine.Direction.Tap
                    popupKana = flickKey.tap
                    showPopup = true

                    var totalX = 0f
                    var totalY = 0f
                    var resolved = FlickEngine.Direction.Tap

                    try {
                        while (true) {
                            val event = withTimeoutOrNull(Long.MAX_VALUE) {
                                awaitPointerEvent()
                            } ?: break

                            val change = event.changes.firstOrNull() ?: break
                            if (change.pressed) {
                                val delta = change.positionChange()
                                totalX += delta.x
                                totalY += delta.y

                                // Resolve direction dynamically for popup
                                resolved = when {
                                    abs(totalX) < flickThresholdPx && abs(totalY) < flickThresholdPx ->
                                        FlickEngine.Direction.Tap
                                    abs(totalX) > abs(totalY) ->
                                        if (totalX > 0) FlickEngine.Direction.Right else FlickEngine.Direction.Left
                                    else ->
                                        if (totalY > 0) FlickEngine.Direction.Down else FlickEngine.Direction.Up
                                }
                                flickDir = resolved
                                popupKana = FlickEngine.resolveFlick(flickKey, resolved) ?: flickKey.tap
                                change.consume()
                            } else {
                                change.consume()
                                break
                            }
                        }
                    } finally {
                        isPressed = false
                        showPopup = false
                    }

                    // Commit resolved kana (apply Shift for uppercase if active)
                    var kana = FlickEngine.resolveFlick(flickKey, resolved)
                    if (kana != null) {
                        if (service.layerManager.isShifted && kana.first().isLetter()) {
                            kana = kana.uppercase()
                            service.layerManager.toggleShift() // auto-reset after one char
                        }
                        val isTap = resolved == FlickEngine.Direction.Tap
                        service.inputEngine.processFlickKana(
                            kana,
                            flickKeyId = flickKey.id,
                            isFlickTap = isTap,
                            tapCycleOverride = flickKey.tapCycle,
                        )
                        service.feedbackManager.onKeyPress(KeyAction.Text(kana))
                        lighting.onKeyPress(flickKey.id, column)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = flickKey.label,
            color = if (isPressed) accentColor else keyText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Default,
            textAlign = TextAlign.Center,
            style = if (isPressed) {
                androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = accentColor,
                        blurRadius = 12f,
                    ),
                )
            } else {
                androidx.compose.ui.text.TextStyle.Default
            },
        )

        // Sub-labels for flick directions (small hints in corners)
        flickKey.up?.let { hint ->
            Text(
                text = hint,
                color = keyText.copy(alpha = 0.5f),
                fontSize = 8.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 2.dp),
            )
        }

        // Flick popup: 48×48dp bubble above the key showing resolved kana
        if (showPopup && popupKana.isNotEmpty()) {
            val popupOffsetPx = with(LocalDensity.current) { -56.dp.roundToPx() }
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, popupOffsetPx),
                properties = PopupProperties(focusable = false),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp, 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accentColor.copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = popupKana,
                        color = Color(0xFF000000),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Dakuten key (゛゜小)
// ─────────────────────────────────────────────────────────────────

/**
 * ゛゜小 key:
 *   Tap  → dakuten (゛)
 *   Up   → handakuten (゜)
 *   Down → small kana (小)
 */
@Composable
private fun DakutenKeyView(
    service: NacreInputMethodService,
    modifier: Modifier = Modifier,
    row: Int = 3,
    column: Int = 0,
) {
    var isPressed by remember { mutableStateOf(false) }
    var showPopup by remember { mutableStateOf(false) }
    var popupLabel by remember { mutableStateOf("゛") }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 30),
        label = "dakutenScale",
    )

    val theme = service.currentTheme
    val keyBg = Color(theme.keyBackground.toInt())
    val keyBgPressed = Color(theme.keyBackgroundPressed.toInt())
    val keyText = Color(theme.keyText.toInt())
    val accentColor = Color(theme.accent.toInt())
    val surfaceColor = Color(theme.surface.toInt())

    val lighting = service.keyLighting
    @Suppress("UNUSED_VARIABLE")
    val tick2 = lighting.animationTick
    val lightingColor = lighting.getKeyColor("゛", row, column)

    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.8f else 0f,
        animationSpec = tween(durationMillis = if (isPressed) 30 else 200),
        label = "dakutenGlow",
    )
    val pressGlow = if (isPressed) accentColor.copy(alpha = glowAlpha) else Color.Transparent
    val borderColor = when {
        pressGlow != Color.Transparent -> pressGlow
        lightingColor != Color.Transparent -> lightingColor
        else -> Color.Transparent
    }

    val shape = RoundedCornerShape(6.dp)
    val flickThresholdPx = with(LocalDensity.current) { 10.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 1.dp, vertical = 1.dp)
            .scale(scale)
            .clip(shape)
            .background(if (isPressed) keyBgPressed else surfaceColor)
            .padding(bottom = 1.5.dp)
            .clip(shape)
            .background(if (isPressed) keyBgPressed else keyBg)
            .then(
                if (borderColor != Color.Transparent) {
                    Modifier.border(width = 1.dp, color = borderColor, shape = shape)
                } else {
                    Modifier.border(
                        width = 0.5.dp,
                        color = surfaceColor.copy(alpha = 0.5f),
                        shape = shape,
                    )
                }
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    popupLabel = "゛"
                    showPopup = true

                    var totalX = 0f
                    var totalY = 0f
                    // Gboard style: tap=toggle dakuten/handakuten, up=small, left=handakuten
                    var resolved = DakutenType.Dakuten

                    try {
                        while (true) {
                            val event = withTimeoutOrNull(Long.MAX_VALUE) {
                                awaitPointerEvent()
                            } ?: break

                            val change = event.changes.firstOrNull() ?: break
                            if (change.pressed) {
                                val delta = change.positionChange()
                                totalX += delta.x
                                totalY += delta.y

                                resolved = when {
                                    abs(totalX) < flickThresholdPx && abs(totalY) < flickThresholdPx ->
                                        DakutenType.Dakuten // tap = toggle dakuten (が↔か)
                                    totalY < -flickThresholdPx -> DakutenType.Small // up = small (つ→っ)
                                    totalX < -flickThresholdPx -> DakutenType.Handakuten // left = handakuten (は→ぱ)
                                    else -> DakutenType.Dakuten
                                }
                                popupLabel = when (resolved) {
                                    DakutenType.Dakuten -> "゛"
                                    DakutenType.Handakuten -> "゜"
                                    DakutenType.Small -> "小"
                                }
                                change.consume()
                            } else {
                                change.consume()
                                break
                            }
                        }
                    } finally {
                        isPressed = false
                        showPopup = false
                    }

                    // Tap (no flick) → iOS-style cycle (small first, then dakuten);
                    // explicit up/left flicks still force small / handakuten.
                    val isTap = abs(totalX) < flickThresholdPx && abs(totalY) < flickThresholdPx
                    if (isTap) {
                        service.inputEngine.processFlickDakutenCycle()
                    } else {
                        service.inputEngine.processFlickDakuten(resolved)
                    }
                    service.feedbackManager.onKeyPress(KeyAction.Text(popupLabel))
                    lighting.onKeyPress("゛", column)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "゛゜",
                color = if (isPressed) accentColor else keyText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                style = if (isPressed) {
                    androidx.compose.ui.text.TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = accentColor,
                            blurRadius = 12f,
                        ),
                    )
                } else {
                    androidx.compose.ui.text.TextStyle.Default
                },
            )
            Text(
                text = "小",
                color = (if (isPressed) accentColor else keyText).copy(alpha = 0.6f),
                fontSize = 9.sp,
                textAlign = TextAlign.Center,
            )
        }

        // Popup
        if (showPopup) {
            val popupOffsetPx = with(LocalDensity.current) { -56.dp.roundToPx() }
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, popupOffsetPx),
                properties = PopupProperties(focusable = false),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp, 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accentColor.copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = popupLabel,
                        color = Color(0xFF000000),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Modifier key (backspace with long-press repeat)
// ─────────────────────────────────────────────────────────────────

@Composable
private fun FlickModKeyView(
    label: String,
    service: NacreInputMethodService,
    modifier: Modifier = Modifier,
    row: Int = 0,
    column: Int = 0,
) {
    var isPressed by remember { mutableStateOf(false) }
    var bsRepeating by remember { mutableStateOf(false) }

    // Long-press repeat backspace
    LaunchedEffect(bsRepeating) {
        if (!bsRepeating) return@LaunchedEffect
        delay(80L)
        while (bsRepeating) {
            service.inputEngine.processFlickBackspace()
            service.feedbackManager.onKeyPress(KeyAction.Backspace)
            delay(50L)
        }
    }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 30),
        label = "modKeyScale",
    )

    val theme = service.currentTheme
    val keyBg = Color(theme.keyBackground.toInt())
    val keyBgPressed = Color(theme.keyBackgroundPressed.toInt())
    val keyText = Color(theme.keyText.toInt())
    val accentColor = Color(theme.accent.toInt())
    val surfaceColor = Color(theme.surface.toInt())

    val lighting = service.keyLighting
    @Suppress("UNUSED_VARIABLE")
    val tick3 = lighting.animationTick
    val lightingColor = lighting.getKeyColor(label, row, column)

    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.8f else 0f,
        animationSpec = tween(durationMillis = if (isPressed) 30 else 200),
        label = "modKeyGlow",
    )
    val pressGlow = if (isPressed) accentColor.copy(alpha = glowAlpha) else Color.Transparent
    val borderColor = when {
        pressGlow != Color.Transparent -> pressGlow
        lightingColor != Color.Transparent -> lightingColor
        else -> Color.Transparent
    }

    val shape = RoundedCornerShape(6.dp)

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 1.dp, vertical = 1.dp)
            .scale(scale)
            .clip(shape)
            .background(if (isPressed) keyBgPressed else surfaceColor)
            .padding(bottom = 1.5.dp)
            .clip(shape)
            .background(if (isPressed) keyBgPressed else keyBg)
            .then(
                if (borderColor != Color.Transparent) {
                    Modifier.border(width = 1.dp, color = borderColor, shape = shape)
                } else {
                    Modifier.border(
                        width = 0.5.dp,
                        color = surfaceColor.copy(alpha = 0.5f),
                        shape = shape,
                    )
                }
            )
            .pointerInput(label) {
                awaitEachGesture {
                    val downTime = System.currentTimeMillis()
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    var longPressHandled = false

                    try {
                        while (true) {
                            val elapsed = System.currentTimeMillis() - downTime
                            val timeout = maxOf(1L, 350L - elapsed)
                            val event = withTimeoutOrNull(timeout) {
                                awaitPointerEvent()
                            }

                            if (event == null) {
                                // Long press: start repeat
                                if (!longPressHandled) {
                                    longPressHandled = true
                                    bsRepeating = true
                                    service.feedbackManager.onLongPress()
                                }
                                continue
                            }

                            val change = event.changes.firstOrNull() ?: break
                            if (change.pressed) {
                                change.consume()
                            } else {
                                change.consume()
                                break
                            }
                        }
                    } finally {
                        isPressed = false
                        bsRepeating = false
                    }

                    if (!longPressHandled) {
                        service.inputEngine.processFlickBackspace()
                        service.feedbackManager.onKeyPress(KeyAction.Backspace)
                        lighting.onKeyPress(label, column)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (isPressed) accentColor else keyText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            style = if (isPressed) {
                androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = accentColor,
                        blurRadius = 12f,
                    ),
                )
            } else {
                androidx.compose.ui.text.TextStyle.Default
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// Bottom row
// ─────────────────────────────────────────────────────────────────

/**
 * Bottom row: 5-column aligned with kana grid.
 * [◀(0.8x)] [▶(1x)] [変換(1x)] [Paste(1x)] [Alt(0.8x)]
 * ◀▶ give char-level cursor movement (the grid dropped its arrow keys for the
 * iOS layout); finer/2-D movement lives in the Flex Pointer, which is untouched.
 */
@Composable
private fun FlickBottomRow(service: NacreInputMethodService) {
    val theme = service.currentTheme
    val bgColor = Color(theme.background.toInt())
    val keyBg = Color(theme.keyBackground.toInt())
    val keyText = Color(theme.keyText.toInt())
    val accentColor = Color(theme.accent.toInt())
    val surfaceColor = Color(theme.surface.toInt())

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .padding(top = 2.dp),
    ) {
        // ◀
        BottomKey(label = "◀", color = accentColor, surfaceColor = surfaceColor, keyBg = keyBg, modifier = Modifier.weight(SIDE_WEIGHT)) {
            service.inputEngine.processAction(KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_LEFT))
        }
        Spacer(modifier = Modifier.width(3.dp))
        // ▶
        BottomKey(label = "▶", color = accentColor, surfaceColor = surfaceColor, keyBg = keyBg, modifier = Modifier.weight(1f)) {
            service.inputEngine.processAction(KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
        }
        Spacer(modifier = Modifier.width(3.dp))
        // 変換
        BottomKey(label = "変換", color = keyText, surfaceColor = surfaceColor, keyBg = keyBg, modifier = Modifier.weight(1f)) {
            service.inputEngine.processAction(KeyAction.Henkan)
        }
        Spacer(modifier = Modifier.width(3.dp))
        // Paste
        BottomKey(label = "Paste", color = Color(0xFF88AAFF), surfaceColor = surfaceColor, keyBg = keyBg, modifier = Modifier.weight(1f)) {
            val clip = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val text = clip?.primaryClip?.getItemAt(0)?.text?.toString()
            if (text != null) {
                service.currentInputConnection?.commitText(text, 1)
            }
        }
        Spacer(modifier = Modifier.width(3.dp))
        // Alt
        BottomKey(
            label = "Alt",
            color = if (service.layerManager.isAltActive) accentColor else Color(0xFFFF9944),
            surfaceColor = surfaceColor,
            keyBg = if (service.layerManager.isAltActive) Color(theme.keyBackgroundPressed.toInt()) else keyBg,
            modifier = Modifier.weight(SIDE_WEIGHT),
        ) {
            service.inputEngine.processAction(KeyAction.Alt)
        }
    }
}

@Composable
private fun BottomKey(
    label: String,
    color: Color,
    surfaceColor: Color,
    keyBg: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .background(surfaceColor)
            .padding(bottom = 1.dp)
            .clip(shape)
            .background(keyBg)
            .border(0.5.dp, surfaceColor.copy(alpha = 0.5f), shape)
            .pointerInput(label) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // Wait for up
                    while (true) {
                        val event = withTimeoutOrNull(Long.MAX_VALUE) { awaitPointerEvent() } ?: break
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) { change.consume(); break }
                        change.consume()
                    }
                    onClick()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
        )
    }
}
