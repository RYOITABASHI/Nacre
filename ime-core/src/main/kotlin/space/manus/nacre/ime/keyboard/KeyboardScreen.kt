package space.manus.nacre.ime.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import space.manus.nacre.ime.NacreInputMethodService
import space.manus.nacre.ime.input.Layer
import space.manus.nacre.ime.trackball.TrackballView

@Composable
fun KeyboardScreen(service: NacreInputMethodService) {
    // Re-pick the layout whenever the epoch bumps (keyboard show / settings change).
    // The input view is cached and won't recompose on its own, so without this a
    // layout toggle (e.g. "12キー入力") would not appear until fold/unfold or restart.
    val layoutEpoch = service.layoutEpoch.value
    val layoutMode = remember(layoutEpoch) { service.layoutSelector.selectLayout() }

    // Animate lighting tick (updates every frame when lighting is active)
    val lighting = service.keyLighting
    if (lighting.mode != KeyLighting.Mode.OFF) {
        LaunchedEffect(lighting.mode) {
            while (true) {
                lighting.animationTick = System.currentTimeMillis()
                delay(50L) // 20fps for lighting
            }
        }
    }

    // Panel state
    var showClipboard by remember { mutableStateOf(false) }
    var showCommandPalette by remember { mutableStateOf(false) }
    var showEmoji by remember { mutableStateOf(false) }
    var showSymbols by remember { mutableStateOf(false) }
    var showFlexPointer by remember { mutableStateOf(false) }

    // Check if command palette should open (Fn+Space triggers it)
    val isCommandPaletteRequested = service.layerManager.isCommandPaletteRequested
    LaunchedEffect(isCommandPaletteRequested) {
        if (isCommandPaletteRequested) {
            showCommandPalette = true
            service.layerManager.isCommandPaletteRequested = false
        }
    }

    // Check if emoji panel should open
    val isEmojiRequested = service.layerManager.isEmojiRequested
    LaunchedEffect(isEmojiRequested) {
        if (isEmojiRequested) {
            showEmoji = true
            service.layerManager.isEmojiRequested = false
        }
    }

    // Check if symbols panel should open
    val isSymbolsRequested = service.layerManager.isSymbolsRequested
    LaunchedEffect(isSymbolsRequested) {
        if (isSymbolsRequested) {
            showSymbols = true
            service.layerManager.isSymbolsRequested = false
        }
    }

    // Overlay panels take over the whole view
    if (showClipboard) {
        ClipboardPanel(service = service, onDismiss = { showClipboard = false })
        return
    }
    if (showCommandPalette) {
        CommandPalette(service = service, onDismiss = { showCommandPalette = false })
        return
    }
    if (showEmoji) {
        EmojiPanel(service = service, onDismiss = { showEmoji = false })
        return
    }
    if (showSymbols) {
        SymbolsPanel(service = service, onDismiss = { showSymbols = false })
        return
    }
    if (showFlexPointer) {
        FlexPointerPad(service = service, onDismiss = { showFlexPointer = false })
        return
    }

    when (layoutMode) {
        space.manus.nacre.ime.foldable.LayoutMode.FullVSplit ->
            VSplitKeyboardScreen(
                service = service,
                angle = 4f,
                onFlexPointer = { showFlexPointer = true },
            )
        space.manus.nacre.ime.foldable.LayoutMode.FlickInput12Key ->
            FlickInputPad(
                service = service,
                onFlexPointer = { showFlexPointer = true },
                onClipboard = { showClipboard = true },
            )
        space.manus.nacre.ime.foldable.LayoutMode.StandardQwerty,
        space.manus.nacre.ime.foldable.LayoutMode.CompactQwerty,
        space.manus.nacre.ime.foldable.LayoutMode.QuickInputPad ->
            StandardKeyboardScreen(
                service = service,
                compactBase = layoutMode == space.manus.nacre.ime.foldable.LayoutMode.CompactQwerty,
                onFlexPointer = { showFlexPointer = true },
            )
    }
}

@Composable
private fun StandardKeyboardScreen(
    service: NacreInputMethodService,
    compactBase: Boolean = false,
    onFlexPointer: () -> Unit,
) {
    val layerManager = service.layerManager
    val layout = layerManager.currentLayout(compactBase = compactBase)
    val theme = service.currentTheme
    val bgColor = Color(theme.background.toInt())
    val accentColor = Color(theme.accent.toInt())

    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val isLargeScreen = screenWidthDp > 500.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        // Candidate bar (prediction/conversion)
        CandidateBar(service = service)

        // Status bar: layer + Japanese mode + shift + voice indicators
        StatusBar(
            service = service,
            layerManager = layerManager,
            accentColor = accentColor,
            onFlexPointer = onFlexPointer,
        )

        // Keyboard rows with trackball in the middle
        val rows = layout.rows
        for ((rowIndex, row) in rows.withIndex()) {
            val isModRow = rowIndex == rows.lastIndex
            val keyHeight = when {
                isModRow && compactBase -> 28f
                isModRow -> 26f
                compactBase -> 44f
                isLargeScreen -> 34f
                else -> 40f
            }

            if (rowIndex == 2) {
                KeyRowWithTrackball(
                    keys = row,
                    service = service,
                    showTrackball = rowIndex == 2,
                    rowIndex = rowIndex,
                    keyHeightDp = keyHeight,
                )
            } else {
                KeyRow(keys = row, service = service, rowIndex = rowIndex, keyHeightDp = keyHeight)
            }
        }
    }
}

@Composable
private fun StatusBar(
    service: NacreInputMethodService,
    layerManager: space.manus.nacre.ime.input.LayerManager,
    accentColor: Color,
    onFlexPointer: () -> Unit,
) {
    val showLayer = layerManager.currentLayer != Layer.Base
    val showJa = layerManager.isJapanese
    val showShift = layerManager.isShifted
    val showAlt = layerManager.isAltActive
    val voiceManager = service.voiceInputManager
    val isListening = voiceManager.isListening

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (showJa) {
                Text(text = "あ", color = accentColor, fontSize = 10.sp)
            }
            if (showShift) {
                Text(text = "⇧", color = accentColor, fontSize = 10.sp)
            }
            if (showAlt) {
                Text(text = "Alt", color = accentColor, fontSize = 10.sp)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Ptr",
                color = accentColor,
                fontSize = 10.sp,
                modifier = Modifier.clickable { onFlexPointer() },
            )
            // Voice input indicator / partial text
            if (isListening) {
                Text(
                    text = voiceManager.partialText.ifEmpty { "🎤 …" },
                    color = Color(0xFFFF4444),
                    fontSize = 10.sp,
                )
            }
            if (showLayer) {
                Text(
                    text = when (layerManager.currentLayer) {
                        Layer.Fn1 -> "Fn"
                        Layer.Fn2 -> "Fn2"
                        else -> ""
                    },
                    color = accentColor,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
fun KeyRow(
    keys: List<space.manus.nacre.config.KeyDef>,
    service: NacreInputMethodService,
    rowIndex: Int = 0,
    keyHeightDp: Float = 52f,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(keyHeightDp.dp + 3.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((colIndex, keyDef) in keys.withIndex()) {
            KeyView(
                keyDef = keyDef,
                service = service,
                modifier = Modifier.weight(keyDef.widthMultiplier),
                row = rowIndex,
                column = colIndex,
                heightDp = keyHeightDp,
            )
        }
    }
}

@Composable
fun KeyRowWithTrackball(
    keys: List<space.manus.nacre.config.KeyDef>,
    service: NacreInputMethodService,
    showTrackball: Boolean,
    rowIndex: Int = 0,
    keyHeightDp: Float = 52f,
) {
    val mid = keys.size / 2
    val leftKeys = keys.subList(0, mid)
    val rightKeys = keys.subList(mid, keys.size)

    val rowHeight = if (showTrackball) 42.dp else (keyHeightDp + 3).dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f)) {
            for ((colIndex, keyDef) in leftKeys.withIndex()) {
                KeyView(
                    keyDef = keyDef,
                    service = service,
                    modifier = Modifier.weight(keyDef.widthMultiplier),
                    row = rowIndex,
                    column = colIndex,
                    heightDp = keyHeightDp,
                )
            }
        }

        if (showTrackball) {
            Spacer(modifier = Modifier.width(2.dp))
            TrackballView(service = service, modifier = Modifier.size(38.dp))
            Spacer(modifier = Modifier.width(2.dp))
        } else {
            Spacer(modifier = Modifier.width(8.dp))
        }

        Row(modifier = Modifier.weight(1f)) {
            for ((colIndex, keyDef) in rightKeys.withIndex()) {
                KeyView(
                    keyDef = keyDef,
                    service = service,
                    modifier = Modifier.weight(keyDef.widthMultiplier),
                    row = rowIndex,
                    column = mid + colIndex,
                    heightDp = keyHeightDp,
                )
            }
        }
    }
}
