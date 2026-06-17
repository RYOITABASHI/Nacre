package space.manus.nacre.ime.keyboard

import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeoutOrNull
import space.manus.nacre.ime.NacreInputMethodService
import space.manus.nacre.ime.pointer.FlexPointerBridge
import kotlin.math.abs

@Composable
fun FlexPointerPad(
    service: NacreInputMethodService,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val theme = service.currentTheme
    val bg = Color(theme.background.toInt())
    val surface = Color(theme.surface.toInt())
    val accent = Color(0xFFFF2222)
    val text = Color(theme.keyText.toInt())
    val textDim = Color(theme.keyTextSwipe.toInt())
    val isReady = FlexPointerBridge.isAccessibilityReady
    val isVisible = FlexPointerBridge.isPointerVisible

    DisposableEffect(Unit) {
        FlexPointerBridge.setImePointerVisible(true)
        onDispose { FlexPointerBridge.setImePointerVisible(false) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Flex Pointer",
                    color = accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (isReady && isVisible) "Accessibility ready" else "Text cursor fallback",
                    color = textDim,
                    fontSize = 10.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = surface,
                        contentColor = accent,
                    ),
                    modifier = Modifier.height(34.dp),
                ) {
                    Text("A11y", fontSize = 11.sp)
                }
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = surface,
                        contentColor = text,
                    ),
                    modifier = Modifier.height(34.dp),
                ) {
                    Text("ABC", fontSize = 11.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        var dotX by remember { mutableFloatStateOf(0f) }
        var dotY by remember { mutableFloatStateOf(0f) }
        var isActive by remember { mutableStateOf(false) }
        val tapThreshold = with(density) { 6.dp.toPx() }
        val scrollThreshold = with(density) { 24.dp.toPx() }
        val longPressMs = 450L

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(238.dp)
                .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                .background(surface.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downTime = System.currentTimeMillis()
                        isActive = true

                        var totalDragX = 0f
                        var totalDragY = 0f
                        var wasDragged = false
                        var longPressHandled = false
                        var multiTouch = false
                        var scrollY = 0f

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
                                        if (!FlexPointerBridge.longPress()) {
                                            service.feedbackManager.onLongPress()
                                        }
                                    }
                                    continue
                                }

                                val pressedChanges = event.changes.filter { it.pressed }
                                if (pressedChanges.isEmpty()) {
                                    event.changes.forEach { it.consume() }
                                    break
                                }

                                val primary = pressedChanges.first()
                                val delta = primary.positionChange()

                                if (pressedChanges.size >= 2) {
                                    multiTouch = true
                                    scrollY += delta.y
                                    if (abs(scrollY) >= scrollThreshold) {
                                        if (FlexPointerBridge.scrollBy(scrollY * 1.8f)) {
                                            service.feedbackManager.onTrackballStep()
                                        }
                                        scrollY = 0f
                                    }
                                } else if (!longPressHandled) {
                                    totalDragX += delta.x
                                    totalDragY += delta.y

                                    if (abs(totalDragX) > tapThreshold || abs(totalDragY) > tapThreshold) {
                                        wasDragged = true
                                    }

                                    if (wasDragged) {
                                        val dx = delta.x * 1.45f
                                        val dy = delta.y * 1.45f
                                        if (!FlexPointerBridge.moveBy(dx, dy)) {
                                            fallbackMoveTextCursor(service, dx, dy)
                                        }
                                        dotX = (dotX + delta.x * 0.12f).coerceIn(-22f * density.density, 22f * density.density)
                                        dotY = (dotY + delta.y * 0.12f).coerceIn(-22f * density.density, 22f * density.density)
                                        service.feedbackManager.onTrackballStep()
                                    }
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } finally {
                            dotX = 0f
                            dotY = 0f
                            isActive = false
                        }

                        if (!wasDragged && !longPressHandled && !multiTouch) {
                            if (!FlexPointerBridge.tap()) {
                                fallbackEnter(service)
                            }
                            service.feedbackManager.onTrackballStep()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val padGlow = if (isActive) 0.24f else 0.08f
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = padGlow), Color.Transparent),
                        center = Offset(cx, cy),
                        radius = size.minDimension * 0.75f,
                    ),
                )
                val gridColor = accent.copy(alpha = if (isActive) 0.18f else 0.10f)
                val step = 32f * density.density
                var x = step
                while (x < size.width) {
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 0.7f * density.density)
                    x += step
                }
                var y = step
                while (y < size.height) {
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.7f * density.density)
                    y += step
                }
                drawCircle(
                    color = accent.copy(alpha = if (isActive) 0.88f else 0.55f),
                    radius = 15f * density.density,
                    center = Offset(cx + dotX, cy + dotY),
                    style = Stroke(width = 2f * density.density),
                )
                drawCircle(
                    color = accent.copy(alpha = if (isActive) 1f else 0.65f),
                    radius = 3f * density.density,
                    center = Offset(cx + dotX, cy + dotY),
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PointerPadButton(label = "Tap", modifier = Modifier.weight(1f)) {
                if (!FlexPointerBridge.tap()) fallbackEnter(service)
            }
            PointerPadButton(label = "Hold", modifier = Modifier.weight(1f)) {
                if (!FlexPointerBridge.longPress()) service.feedbackManager.onLongPress()
            }
            PointerPadButton(label = "Up", modifier = Modifier.weight(1f)) {
                if (!FlexPointerBridge.scrollBy(-180f)) fallbackMoveTextCursor(service, 0f, -40f)
            }
            PointerPadButton(label = "Down", modifier = Modifier.weight(1f)) {
                if (!FlexPointerBridge.scrollBy(180f)) fallbackMoveTextCursor(service, 0f, 40f)
            }
        }
    }
}

@Composable
private fun PointerPadButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF241818),
            contentColor = Color(0xFFFF8888),
        ),
        modifier = modifier.height(36.dp),
    ) {
        Text(label, fontSize = 11.sp)
    }
}

private fun fallbackMoveTextCursor(service: NacreInputMethodService, dx: Float, dy: Float) {
    val horizontal = when {
        dx > 12f -> 1
        dx < -12f -> -1
        else -> 0
    }
    val vertical = when {
        dy > 12f -> 1
        dy < -12f -> -1
        else -> 0
    }
    if (horizontal != 0 || vertical != 0) {
        service.inputEngine.moveCursor(horizontal, vertical)
    }
}

private fun fallbackEnter(service: NacreInputMethodService) {
    val ic = service.currentInputConnection ?: return
    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
}
