package space.manus.nacre.ime.keyboard

import android.view.KeyEvent
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
import space.manus.nacre.ime.input.SwipeDirection
import kotlin.math.abs

/** Input mode for the 12-key pad. Cycled by the single Gboard-style あa1 key in the left column. */
private enum class FlickMode { Kana, Alpha, Numbers }

/**
 * Main 12-key input pad composable. The grid follows the iPhone stock
 * Japanese kana keyboard (verified against real-device references —
 * see the layout PR): a 4-row × 5-column grid whose centre 3 columns
 * are the twelve kana keys. There is NO separate 変換 key (空白
 * converts / walks candidates, 改行 confirms — exactly the iPhone
 * split). Multi-tap (連打) on the centre keys cycles within a row
 * (か→き→く→け→こ).
 *
 * The flanking columns follow GBOARD's Japanese 12-key instead
 * (owner's own Gboard, screenshot-verified): the left column is
 * 📋 / ◀ / ☺記 / あa1 — ◀ sits in the left column SECOND from the
 * top and ▶ in the right strip SECOND from the top, both flanking
 * the た row, and mode switching is consolidated into the single
 * あa1 key at the bottom of the left column, which cycles
 * kana → alpha → numbers → kana (the former ☆123 / ABC / あいう
 * direct keys were retired by owner decision, 2026-11-16). The top
 * slot departs from Gboard: its ↩ (undo) seat held an inert
 * placeholder (Nacre has no undo) until 📋 moved into it
 * (2026-11-16) from the full-width bottom row it used to occupy —
 * that row is gone, so the pad now ends at the grid. The right
 * strip holds four equal slots (⌫ / ▶ / 空白 / 改行); PR #46's
 * iPhone-style tall 改行 gave way to Gboard's equal strip.
 * Layout: candidate bar → [left column | 3-col grid | right strip].
 */
@Composable
fun FlickInputPad(
    service: NacreInputMethodService,
    onFlexPointer: () -> Unit = {},
    onClipboard: () -> Unit = {},
    onCommandPalette: () -> Unit = {},
) {
    val theme = service.currentTheme
    val bgColor = Color(theme.background.toInt())
    var flickMode by remember { mutableStateOf(FlickMode.Kana) }

    // Tell the engine when ABC mode is active so flick prediction + history route
    // to the English completer. Uses a dedicated flag — NOT layerManager.isJapanese —
    // so QWERTY and voice language are left untouched.
    androidx.compose.runtime.LaunchedEffect(flickMode) {
        service.inputEngine.flickAlphaMode = (flickMode == FlickMode.Alpha)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { service.inputEngine.flickAlphaMode = false }
    }

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

        // Grid: switches based on flickMode, cycled by the single あa1
        // key in the left column (Gboard-style consolidation).
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
                onCommandPalette = onCommandPalette,
            )
            FlickMode.Alpha -> FlickAlphaGrid(
                service = service,
                flickMode = flickMode,
                onSetMode = onSetMode,
                onSymbols = { showSymbols = true },
                onEmoji = { showEmoji = true },
                onFlexPointer = onFlexPointer,
                onClipboard = onClipboard,
                onCommandPalette = onCommandPalette,
            )
            FlickMode.Numbers -> FlickNumberGrid(
                service = service,
                flickMode = flickMode,
                onSetMode = onSetMode,
                onSymbols = { showSymbols = true },
                onEmoji = { showEmoji = true },
                onFlexPointer = onFlexPointer,
                onClipboard = onClipboard,
                onCommandPalette = onCommandPalette,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Kana grid — iPhone stock 4×5 furniture (all grid rows 50dp uniform)
// ─────────────────────────────────────────────────────────────────

private const val FLICK_ROW_HEIGHT = 50f

/**
 * Flick resolution distance: accumulated pointer movement below this stays a
 * tap. Raised from 10dp to 16dp so a tap-only user's finger wobble is not
 * resolved as a flick on release; direction logic itself is unchanged.
 */
private const val FLICK_THRESHOLD_DP = 16f
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
    onCommandPalette: () -> Unit,
) {
    val kanaKeys = FlickEngine.kanaKeys
    val rowH = FLICK_ROW_HEIGHT * keyboardHeightScale(service.configRepository)
    val h = rowH.dp
    val sw = SIDE_WEIGHT

    // The 、。？！ key is defined in FlickEngine (pure data, unit-tested):
    // iPhone cross order tap=、, left=。, up=？, right=！, down=…, and a tap
    // cycle in that same order so 。 takes 2 taps (it previously took 10 —
    // the cycle started at 。 while tap inserted 、, and up/right/down did
    // not match the iPhone key).
    val punctKey = FlickEngine.punctKey

    Row(modifier = Modifier.fillMaxWidth()) {
        FlickSideColumn(
            service = service,
            flickMode = flickMode,
            onSetMode = onSetMode,
            onSymbols = onSymbols,
            onEmoji = onEmoji,
            onClipboard = onClipboard,
            onCommandPalette = onCommandPalette,
            rowH = rowH,
            modifier = Modifier.weight(sw),
        )
        Column(modifier = Modifier.weight(1f + 1f + 1f)) {
            // Row 1: あ | か | さ
            Row(modifier = Modifier.fillMaxWidth().height(h)) {
                FlickKeyView(flickKey = kanaKeys[0], service = service, modifier = Modifier.weight(1f), row = 0, column = 1)
                FlickKeyView(flickKey = kanaKeys[1], service = service, modifier = Modifier.weight(1f), row = 0, column = 2)
                FlickKeyView(flickKey = kanaKeys[2], service = service, modifier = Modifier.weight(1f), row = 0, column = 3)
            }
            // Row 2: た | な | は
            Row(modifier = Modifier.fillMaxWidth().height(h)) {
                FlickKeyView(flickKey = kanaKeys[3], service = service, modifier = Modifier.weight(1f), row = 1, column = 1)
                FlickKeyView(flickKey = kanaKeys[4], service = service, modifier = Modifier.weight(1f), row = 1, column = 2)
                FlickKeyView(flickKey = kanaKeys[5], service = service, modifier = Modifier.weight(1f), row = 1, column = 3)
            }
            // Row 3: ま | や | ら
            Row(modifier = Modifier.fillMaxWidth().height(h)) {
                FlickKeyView(flickKey = kanaKeys[6], service = service, modifier = Modifier.weight(1f), row = 2, column = 1)
                FlickKeyView(flickKey = kanaKeys[7], service = service, modifier = Modifier.weight(1f), row = 2, column = 2)
                FlickKeyView(flickKey = kanaKeys[8], service = service, modifier = Modifier.weight(1f), row = 2, column = 3)
            }
            // Row 4: ゛゜小 | わ | 、。？！
            Row(modifier = Modifier.fillMaxWidth().height(h)) {
                DakutenKeyView(service = service, modifier = Modifier.weight(1f), row = 3, column = 1)
                FlickKeyView(flickKey = kanaKeys[9], service = service, modifier = Modifier.weight(1f), row = 3, column = 2)
                FlickKeyView(flickKey = punctKey, service = service, modifier = Modifier.weight(1f), row = 3, column = 3)
            }
        }
        FlickRightStrip(service = service, rowH = rowH, onFlexPointer = onFlexPointer, modifier = Modifier.weight(sw))
    }
}

// ─────────────────────────────────────────────────────────────────
// Left column: 📋 / ◀ / 絵記 / あa1 — four equal slots over the
// grid's height, rendered positionally from
// FlickEngine.sideColumnKeys (unit-tested). The lower three follow
// Gboard: ◀ takes the second slot, Gboard's arrow position; 絵記
// the third. The あa1 key closes the column at the pad's bottom
// edge and is the ONLY mode key: it cycles kana → alpha →
// numbers → kana. It replaces the ☆123 / ABC / あいう direct keys
// (owner decision, 2026-11-16) — the mode keys used to sit directly
// above 絵記, and a downward overshoot when switching back to kana
// kept opening the emoji panel instead. The top slot is the one
// departure from Gboard: its ↩ undo seat was an inert placeholder
// (Nacre has no undo) until 📋 moved into it (owner, 2026-11-16)
// from the full-width bottom row below the grid, which is gone —
// the pad is one row shorter for it.
// ─────────────────────────────────────────────────────────────────

/** Cycle order of the consolidated あa1 switch key: kana → alpha → numbers → kana. */
private fun nextFlickMode(mode: FlickMode): FlickMode = when (mode) {
    FlickMode.Kana -> FlickMode.Alpha
    FlickMode.Alpha -> FlickMode.Numbers
    FlickMode.Numbers -> FlickMode.Kana
}

@Composable
private fun FlickSideColumn(
    service: NacreInputMethodService,
    flickMode: FlickMode,
    onSetMode: (FlickMode) -> Unit,
    onSymbols: () -> Unit,
    onEmoji: () -> Unit,
    onClipboard: () -> Unit,
    onCommandPalette: () -> Unit,
    rowH: Float,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.height((rowH * 4).dp)) {
        FlickEngine.sideColumnKeys.forEach { key ->
            when (key) {
                FlickEngine.SideColumnKey.Clipboard ->
                    FlickClipboardKey(service = service, onClipboard = onClipboard, onCommandPalette = onCommandPalette, modifier = Modifier.weight(1f).fillMaxWidth())
                FlickEngine.SideColumnKey.CursorLeft ->
                    FlickCursorKey(service = service, direction = SwipeDirection.Left, modifier = Modifier.weight(1f).fillMaxWidth())
                FlickEngine.SideColumnKey.Emoji ->
                    SymbolEmojiKey(label = "絵記", modifier = Modifier.weight(1f).fillMaxWidth(), service = service, onTap = onEmoji, onLongPress = onSymbols)
                FlickEngine.SideColumnKey.ModeCycle ->
                    ModeCycleKey(service = service, flickMode = flickMode, modifier = Modifier.weight(1f).fillMaxWidth(), onClick = { onSetMode(nextFlickMode(flickMode)) })
            }
        }
    }
}

/**
 * Cursor key (◀ in the left column, ▶ in the right strip). While a
 * conversion is active it adjusts the segment boundary — the same
 * adjustSegmentBoundary call the candidate bar's horizontal swipe
 * makes, but tappable, which matters for a tap-input user; otherwise
 * it routes to InputEngine.onFlickCursorKey, which seals a pending
 * flick toggle on ▶ and moves the caret with setSelection (never a
 * raw DPAD event — an unconsumed one can escape to the activity's
 * focus search and hide the keyboard). Exactly one key-press feedback
 * (haptic + sound) fires per tap on either route, like the letter
 * keys: the conversion route's tick comes from adjustSegmentBoundary
 * itself, the cursor-move route fires it here.
 * Styled after ModeCycleKey.
 */
@Composable
private fun FlickCursorKey(
    service: NacreInputMethodService,
    direction: SwipeDirection,
    modifier: Modifier = Modifier,
) {
    val theme = service.currentTheme
    val keyBg = Color(theme.keyBackground.toInt())
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
            .background(keyBg)
            .border(0.5.dp, surfaceColor.copy(alpha = 0.5f), shape)
            .pointerInput(direction) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = withTimeoutOrNull(Long.MAX_VALUE) { awaitPointerEvent() } ?: break
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) { change.consume(); break }
                        change.consume()
                    }
                    // Read live at release: pointerInput is keyed on
                    // direction only, so a value captured at composition
                    // would go stale the moment a conversion starts or
                    // ends — the key must route by the CURRENT state.
                    if (service.inputEngine.isConverting) {
                        // adjustSegmentBoundary already fires the tap
                        // tick itself (FeedbackManager.onSwipe inside
                        // InputEngine) — no feedback is added here.
                        service.inputEngine.adjustSegmentBoundary(direction)
                    } else {
                        service.inputEngine.onFlickCursorKey(direction)
                        // Same key feedback as the letter keys (the
                        // PR #53 one-shot tap via
                        // FeedbackManager.onKeyPress, with its strength /
                        // system-toggle / power-save gating and cached
                        // availability checks) — the cursor-move path
                        // used to fire none at all (owner feedback,
                        // 2026-11-16), so exactly one feedback fires
                        // per tap on either path. The KeyCode token
                        // mirrors the QWERTY ↑↓ keys, which reach
                        // onKeyPress through KeyView.
                        service.feedbackManager.onKeyPress(
                            KeyAction.KeyCode(
                                if (direction == SwipeDirection.Left) {
                                    KeyEvent.KEYCODE_DPAD_LEFT
                                } else {
                                    KeyEvent.KEYCODE_DPAD_RIGHT
                                },
                            ),
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (direction == SwipeDirection.Left) "◀" else "▶",
            color = accentColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// Right strip (Gboard): ⌫ / ▶ / 空白 / 改行 in four equal slots,
// rendered positionally from FlickEngine.rightStripKeys (unit-tested).
//
// This was PR #46's iPhone-stock strip (⌫ / 空白 / TALL 改行 spanning
// rows 3-4). Seating ▶ in Gboard's position — second from the top,
// directly under ⌫ — takes one slot back, so 改行 is now a normal
// one-row key (50dp at scale 1.0, the same height as every other key
// on the pad) instead of the tall iPhone key. While a composition is
// active the same physical keys become 変換/次候補 (空白 —
// KeyAction.Space already starts conversion / walks candidates in
// InputEngine) and 確定 (改行 — the Enter branch commits the selected
// candidate while converting); ▶ then adjusts the segment boundary.
// ─────────────────────────────────────────────────────────────────

@Composable
private fun FlickRightStrip(
    service: NacreInputMethodService,
    rowH: Float,
    onFlexPointer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        FlickEngine.rightStripKeys.forEach { key ->
            when (key) {
                // 削除 — KeyView keeps its long-press repeat-delete.
                FlickEngine.RightStripKey.Backspace ->
                    KeyView(keyDef = KeyDef("⌫", action = KeyAction.Backspace), service = service, modifier = Modifier.fillMaxWidth(), row = 0, column = 4, heightDp = rowH)
                // ▶ — segment-extend while converting, toggle-confirm /
                // cursor-right otherwise (routing inside FlickCursorKey).
                FlickEngine.RightStripKey.CursorRight ->
                    FlickCursorKey(service = service, direction = SwipeDirection.Right, modifier = Modifier.fillMaxWidth().height(rowH.dp))
                // 空白 — tap: space / convert / next candidate (engine
                // semantics); long-press opens the Flex Pointer (iOS
                // space-bar trackpad analogue).
                FlickEngine.RightStripKey.Space ->
                    Box(modifier = Modifier.fillMaxWidth().height(rowH.dp)) {
                        FlickSpaceKey(service = service, onFlexPointer = onFlexPointer, modifier = Modifier.fillMaxSize())
                    }
                // 改行 — one equal slot (see the deviation note above).
                FlickEngine.RightStripKey.Enter ->
                    KeyView(keyDef = KeyDef("改行", action = KeyAction.Enter), service = service, modifier = Modifier.fillMaxWidth(), row = 3, column = 4, heightDp = rowH)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Alpha grid (English letters via flick, same iPhone-style skeleton)
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
    onCommandPalette: () -> Unit,
) {
    val rowH = FLICK_ROW_HEIGHT * keyboardHeightScale(service.configRepository)
    val h = rowH.dp
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

    Row(modifier = Modifier.fillMaxWidth()) {
        FlickSideColumn(
            service = service,
            flickMode = flickMode,
            onSetMode = onSetMode,
            onSymbols = onSymbols,
            onEmoji = onEmoji,
            onClipboard = onClipboard,
            onCommandPalette = onCommandPalette,
            rowH = rowH,
            modifier = Modifier.weight(sw),
        )
        Column(modifier = Modifier.weight(1f + 1f + 1f)) {
            // Row 1: @#& | abc | def
            Row(modifier = Modifier.fillMaxWidth().height(h)) {
                FlickKeyView(flickKey = alphaKeys[0], service = service, modifier = Modifier.weight(1f), row = 0, column = 1, directAlpha = true)
                FlickKeyView(flickKey = alphaKeys[1], service = service, modifier = Modifier.weight(1f), row = 0, column = 2, directAlpha = true)
                FlickKeyView(flickKey = alphaKeys[2], service = service, modifier = Modifier.weight(1f), row = 0, column = 3, directAlpha = true)
            }
            // Row 2: ghi | jkl | mno
            Row(modifier = Modifier.fillMaxWidth().height(h)) {
                FlickKeyView(flickKey = alphaKeys[3], service = service, modifier = Modifier.weight(1f), row = 1, column = 1, directAlpha = true)
                FlickKeyView(flickKey = alphaKeys[4], service = service, modifier = Modifier.weight(1f), row = 1, column = 2, directAlpha = true)
                FlickKeyView(flickKey = alphaKeys[5], service = service, modifier = Modifier.weight(1f), row = 1, column = 3, directAlpha = true)
            }
            // Row 3: pqrs | tuv | wxyz
            Row(modifier = Modifier.fillMaxWidth().height(h)) {
                FlickKeyView(flickKey = alphaKeys[6], service = service, modifier = Modifier.weight(1f), row = 2, column = 1, directAlpha = true)
                FlickKeyView(flickKey = alphaKeys[7], service = service, modifier = Modifier.weight(1f), row = 2, column = 2, directAlpha = true)
                FlickKeyView(flickKey = alphaKeys[8], service = service, modifier = Modifier.weight(1f), row = 2, column = 3, directAlpha = true)
            }
            // Row 4: Shift | - | .,!?
            Row(modifier = Modifier.fillMaxWidth().height(h)) {
                KeyView(keyDef = KeyDef("Shift", action = KeyAction.Shift), service = service, modifier = Modifier.weight(1f), row = 3, column = 1, heightDp = rowH)
                FlickDirectKey(label = "-", service = service, modifier = Modifier.weight(1f), row = 3, column = 2, swipeUp = "/", swipeDown = "\\")
                FlickKeyView(flickKey = alphaPunct, service = service, modifier = Modifier.weight(1f), row = 3, column = 3, directAlpha = true)
            }
        }
        FlickRightStrip(service = service, rowH = rowH, onFlexPointer = onFlexPointer, modifier = Modifier.weight(sw))
    }
}

// ─────────────────────────────────────────────────────────────────
// Number grid (same skeleton: Gboard left column + right strip)
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
    onCommandPalette: () -> Unit,
) {
    val rowH = FLICK_ROW_HEIGHT * keyboardHeightScale(service.configRepository)
    val h = rowH.dp
    val sw = SIDE_WEIGHT

    Row(modifier = Modifier.fillMaxWidth()) {
        FlickSideColumn(
            service = service,
            flickMode = flickMode,
            onSetMode = onSetMode,
            onSymbols = onSymbols,
            onEmoji = onEmoji,
            onClipboard = onClipboard,
            onCommandPalette = onCommandPalette,
            rowH = rowH,
            modifier = Modifier.weight(sw),
        )
        Column(modifier = Modifier.weight(1f + 1f + 1f)) {
            // Row 1: 1 | 2 | 3
            // Digits commit immediately (iPhone number page) via FlickDirectKey —
            // they no longer route through the romaji composing buffer.
            Row(modifier = Modifier.fillMaxWidth().height(h)) {
                FlickDirectKey(label = "1", service = service, modifier = Modifier.weight(1f), row = 0, column = 1)
                FlickDirectKey(label = "2", service = service, modifier = Modifier.weight(1f), row = 0, column = 2)
                FlickDirectKey(label = "3", service = service, modifier = Modifier.weight(1f), row = 0, column = 3)
            }
            // Row 2: 4 | 5 | 6
            Row(modifier = Modifier.fillMaxWidth().height(h)) {
                FlickDirectKey(label = "4", service = service, modifier = Modifier.weight(1f), row = 1, column = 1)
                FlickDirectKey(label = "5", service = service, modifier = Modifier.weight(1f), row = 1, column = 2)
                FlickDirectKey(label = "6", service = service, modifier = Modifier.weight(1f), row = 1, column = 3)
            }
            // Row 3: 7 | 8 | 9
            Row(modifier = Modifier.fillMaxWidth().height(h)) {
                FlickDirectKey(label = "7", service = service, modifier = Modifier.weight(1f), row = 2, column = 1)
                FlickDirectKey(label = "8", service = service, modifier = Modifier.weight(1f), row = 2, column = 2)
                FlickDirectKey(label = "9", service = service, modifier = Modifier.weight(1f), row = 2, column = 3)
            }
            // Row 4: + | 0 | .
            Row(modifier = Modifier.fillMaxWidth().height(h)) {
                FlickDirectKey(label = "+", service = service, modifier = Modifier.weight(1f), row = 3, column = 1, swipeUp = "-", swipeDown = "=")
                FlickDirectKey(label = "0", service = service, modifier = Modifier.weight(1f), row = 3, column = 2)
                FlickDirectKey(label = ".", service = service, modifier = Modifier.weight(1f), row = 3, column = 3, swipeUp = ",", swipeDown = ":")
            }
        }
        FlickRightStrip(service = service, rowH = rowH, onFlexPointer = onFlexPointer, modifier = Modifier.weight(sw))
    }
}

// ─────────────────────────────────────────────────────────────────
// Direct-commit key (number-grid digits, alpha "-"): tap / swipe-up / swipe-down
// insert IMMEDIATELY via InputEngine.processFlickDirectText — iPhone-style,
// never the romaji/flick composing buffer, so no 確定 press is needed.
// Visuals mirror FlickKeyView; vertical swipes use the shared threshold.
// ─────────────────────────────────────────────────────────────────

@Composable
private fun FlickDirectKey(
    label: String,
    service: NacreInputMethodService,
    modifier: Modifier = Modifier,
    row: Int = 0,
    column: Int = 0,
    swipeUp: String? = null,
    swipeDown: String? = null,
) {
    var isPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 30),
        label = "flickDirectScale",
    )

    val theme = service.currentTheme
    val keyBg = Color(theme.keyBackground.toInt())
    val keyBgPressed = Color(theme.keyBackgroundPressed.toInt())
    val keyText = Color(theme.keyText.toInt())
    val accentColor = Color(theme.accent.toInt())
    val surfaceColor = Color(theme.surface.toInt())

    val lighting = service.keyLighting
    @Suppress("UNUSED_VARIABLE")
    val tick = lighting.animationTick
    val lightingColor = lighting.getKeyColor(label, row, column)

    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.8f else 0f,
        animationSpec = tween(durationMillis = if (isPressed) 30 else 200),
        label = "flickDirectGlow",
    )
    val pressGlow = if (isPressed) accentColor.copy(alpha = glowAlpha) else Color.Transparent
    val borderColor = when {
        pressGlow != Color.Transparent -> pressGlow
        lightingColor != Color.Transparent -> lightingColor
        else -> Color.Transparent
    }

    val shape = RoundedCornerShape(6.dp)
    val flickThresholdPx = with(LocalDensity.current) { FLICK_THRESHOLD_DP.dp.toPx() }

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
            .pointerInput(label, swipeUp, swipeDown) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    var totalY = 0f
                    var resolved = label
                    try {
                        while (true) {
                            val event = withTimeoutOrNull(Long.MAX_VALUE) {
                                awaitPointerEvent()
                            } ?: break
                            val change = event.changes.firstOrNull() ?: break
                            if (change.pressed) {
                                totalY += change.positionChange().y
                                resolved = when {
                                    totalY < -flickThresholdPx -> swipeUp ?: label
                                    totalY > flickThresholdPx -> swipeDown ?: label
                                    else -> label
                                }
                                change.consume()
                            } else {
                                change.consume()
                                break
                            }
                        }
                    } finally {
                        isPressed = false
                    }
                    service.inputEngine.processFlickDirectText(resolved)
                    service.feedbackManager.onKeyPress(KeyAction.Text(resolved))
                    lighting.onKeyPress(label, column)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (isPressed) accentColor else keyText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Default,
            textAlign = TextAlign.Center,
        )
        swipeUp?.let { hint ->
            Text(
                text = hint,
                color = keyText.copy(alpha = 0.5f),
                fontSize = 8.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 2.dp),
            )
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
// Clipboard key — tap opens the saved clipboard history panel (pinned
// clips); long-press (>=350ms, same pattern as the symbol/space keys) opens
// the command palette. The palette was previously unreachable from the
// 12-key pad (it needed Fn+Space, and this pad has no Fn key). The sub-label
// under the icon advertises the long-press. The key sits at the TOP of the
// left column; it used to span a full-width row below the grid until the
// owner moved it into the column's then-empty top slot (2026-11-16).
// ─────────────────────────────────────────────────────────────────

@Composable
private fun FlickClipboardKey(
    service: NacreInputMethodService,
    onClipboard: () -> Unit,
    onCommandPalette: () -> Unit = {},
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
                        onCommandPalette()
                    } else {
                        onClipboard()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "📋",
                color = keyText,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "長押し⌘",
                color = keyText.copy(alpha = 0.6f),
                fontSize = 7.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Consolidated mode key (Gboard's あa1): the pad's ONLY mode key.
// One tap cycles kana → alpha → numbers → kana (see nextFlickMode).
// The label shows all three modes; the current mode's glyph is
// highlighted in the accent colour — the active style of the direct
// ☆123 / ABC / あいう keys this replaces — the others stay dimmed.
// ─────────────────────────────────────────────────────────────────

@Composable
private fun ModeCycleKey(
    service: NacreInputMethodService,
    flickMode: FlickMode,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val theme = service.currentTheme
    val keyBg = Color(theme.keyBackground.toInt())
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
            .background(keyBg)
            .border(0.5.dp, surfaceColor.copy(alpha = 0.5f), shape)
            // Keyed on flickMode: the click target (the NEXT mode) is
            // derived from the current one, so the gesture handler
            // must restart with the fresh onClick after every cycle.
            .pointerInput(flickMode) {
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
        Row {
            listOf(
                "あ" to FlickMode.Kana,
                "a" to FlickMode.Alpha,
                "1" to FlickMode.Numbers,
            ).forEach { (glyph, mode) ->
                val current = flickMode == mode
                Text(
                    text = glyph,
                    color = if (current) accentColor else keyText.copy(alpha = 0.55f),
                    fontSize = 14.sp,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                )
            }
        }
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
    /** ABC grid only: commit immediately (iPhone-style) instead of composing. */
    directAlpha: Boolean = false,
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
    // NOTE: no animationTick / getKeyColor read in this body anymore. The
    // lighting border is drawn by KeyLightingBorder (below, in the Box
    // content) inside a derivedStateOf, so the 20fps tick recomposes only
    // that tiny border scope — never this whole key.

    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.8f else 0f,
        animationSpec = tween(durationMillis = if (isPressed) 30 else 200),
        label = "flickGlow",
    )
    val pressGlow = if (isPressed) accentColor.copy(alpha = glowAlpha) else Color.Transparent
    val borderColor = pressGlow

    val shape = RoundedCornerShape(6.dp)
    val flickThresholdPx = with(LocalDensity.current) { FLICK_THRESHOLD_DP.dp.toPx() }

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
                        if (directAlpha) {
                            service.inputEngine.processFlickAlpha(
                                kana,
                                flickKeyId = flickKey.id,
                                isFlickTap = isTap,
                                tapCycle = flickKey.tapCycle,
                            )
                        } else {
                            service.inputEngine.processFlickKana(
                                kana,
                                flickKeyId = flickKey.id,
                                isFlickTap = isTap,
                                tapCycleOverride = flickKey.tapCycle,
                            )
                        }
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

        // Lighting border overlay (own recomposition scope; hidden while
        // pressed, where the press glow above takes precedence)
        KeyLightingBorder(
            lighting = lighting,
            keyId = flickKey.id,
            row = row,
            column = column,
            shape = shape,
            suppressed = isPressed,
            modifier = Modifier.matchParentSize(),
        )

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
 * ゛゜小 key — iOS behavior verified against real-device references:
 * unlike the kana keys, this key has no plus-shaped 4-direction flick map.
 *   Tap        → cycle the last kana through its variants: small → dakuten →
 *                handakuten → base (e.g. つ→っ→づ→つ, は→ば→ぱ→は, か→が→か).
 *   Flick left  → jump straight to dakuten (゛) — same target as one tap when
 *                 the base form has no small variant (a faster alternate
 *                 gesture, not a different result).
 *   Flick right → jump straight to handakuten (゜) — same target as two taps.
 *   Up / down   → no assigned meaning on iOS; falls back to the tap cycle.
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
    var popupLabel by remember { mutableStateOf("゛゜") }

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
    // Lighting color is read only inside KeyLightingBorder (see FlickKeyView).

    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.8f else 0f,
        animationSpec = tween(durationMillis = if (isPressed) 30 else 200),
        label = "dakutenGlow",
    )
    val pressGlow = if (isPressed) accentColor.copy(alpha = glowAlpha) else Color.Transparent
    val borderColor = pressGlow

    val shape = RoundedCornerShape(6.dp)
    val flickThresholdPx = with(LocalDensity.current) { FLICK_THRESHOLD_DP.dp.toPx() }

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
                    popupLabel = "゛゜"
                    showPopup = true

                    var totalX = 0f
                    var totalY = 0f
                    // iOS: only left/right are meaningful on this key (direct
                    // shortcuts to dakuten / handakuten); tap, up, and down all
                    // advance the small→dakuten→handakuten cycle below.

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

                                val isHorizontal = abs(totalX) > abs(totalY)
                                popupLabel = when {
                                    abs(totalX) < flickThresholdPx && abs(totalY) < flickThresholdPx -> "゛゜"
                                    isHorizontal && totalX < -flickThresholdPx -> "゛" // left = dakuten
                                    isHorizontal && totalX > flickThresholdPx -> "゜" // right = handakuten
                                    else -> "゛゜" // up/down: no direct meaning, falls back to cycle
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

                    val isHorizontal = abs(totalX) > abs(totalY)
                    val isLeftFlick = isHorizontal && totalX < -flickThresholdPx
                    val isRightFlick = isHorizontal && totalX > flickThresholdPx

                    when {
                        isLeftFlick -> service.inputEngine.processFlickDakuten(DakutenType.Dakuten)
                        isRightFlick -> service.inputEngine.processFlickDakuten(DakutenType.Handakuten)
                        // Tap, up-flick, or down-flick: iOS-style cycle (small first, then
                        // dakuten, then handakuten) — this key has no up/down flick target.
                        else -> service.inputEngine.processFlickDakutenCycle()
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

        // Lighting border overlay (own recomposition scope)
        KeyLightingBorder(
            lighting = lighting,
            keyId = "゛",
            row = row,
            column = column,
            shape = shape,
            suppressed = isPressed,
            modifier = Modifier.matchParentSize(),
        )

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
            service.feedbackManager.onDeleteRepeat()
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
    // Lighting color is read only inside KeyLightingBorder (see FlickKeyView).

    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.8f else 0f,
        animationSpec = tween(durationMillis = if (isPressed) 30 else 200),
        label = "modKeyGlow",
    )
    val pressGlow = if (isPressed) accentColor.copy(alpha = glowAlpha) else Color.Transparent
    val borderColor = pressGlow

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

        // Lighting border overlay (own recomposition scope)
        KeyLightingBorder(
            lighting = lighting,
            keyId = label,
            row = row,
            column = column,
            shape = shape,
            suppressed = isPressed,
            modifier = Modifier.matchParentSize(),
        )
    }
}
