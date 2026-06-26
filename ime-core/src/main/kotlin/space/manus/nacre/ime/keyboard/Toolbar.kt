package space.manus.nacre.ime.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import space.manus.nacre.ime.NacreInputMethodService

/**
 * Shows CandidateBar when there is active composing/conversion input,
 * otherwise shows the Toolbar with utility icons.
 */
@Composable
fun ToolbarOrCandidateBar(service: NacreInputMethodService) {
    val composingKana = service.inputEngine.composingKana
    val isConverting = service.inputEngine.isConverting

    // Show candidate bar only when actively composing or converting.
    // Next-word predictions (candidates present but no composing) show toolbar instead.
    if (composingKana.isNotEmpty() || isConverting) {
        CandidateBar(service = service)
    } else {
        Toolbar(service = service)
    }
}

/**
 * A row of four utility icon buttons: Clipboard, Voice, Emoji, Settings.
 * Icons are drawn with Canvas using stroke only (no fill), color #AAAAAA.
 */
@Composable
fun Toolbar(service: NacreInputMethodService) {
    val theme = service.currentTheme
    val bgColor = Color(theme.background.toInt())
    val iconColor = Color(0xFFAAAAAA)
    val strokeWidth = 1.8.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(bgColor)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Clipboard icon
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 30.dp)
                .clickable {
                    // TODO: service.layerManager.isClipboardRequested = true
                    // (isClipboardRequested state will be added to LayerManager later)
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(width = 20.dp, height = 22.dp)) {
                drawClipboardIcon(iconColor, strokeWidth.toPx())
            }
        }

        // Voice icon
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 30.dp)
                .clickable {
                    val lang = if (service.layerManager.isJapanese) "ja-JP" else "en-US"
                    service.voiceInputManager.startListening(lang)
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(width = 16.dp, height = 22.dp)) {
                drawVoiceIcon(iconColor, strokeWidth.toPx())
            }
        }

        // Emoji icon
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 30.dp)
                .clickable {
                    service.layerManager.isEmojiRequested = true
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(width = 20.dp, height = 20.dp)) {
                drawEmojiIcon(iconColor, strokeWidth.toPx())
            }
        }

        // Settings icon
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 30.dp)
                .clickable {
                    // TODO: open settings panel
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(width = 20.dp, height = 20.dp)) {
                drawSettingsIcon(iconColor, strokeWidth.toPx())
            }
        }
    }
}

/**
 * Clipboard: outer rounded rect body + small tab rect at top + horizontal lines inside.
 */
private fun DrawScope.drawClipboardIcon(color: Color, strokeWidthPx: Float) {
    val stroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val w = size.width
    val h = size.height

    // Tab at top center
    val tabW = w * 0.4f
    val tabH = h * 0.1f
    val tabLeft = (w - tabW) / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(tabLeft, 0f),
        size = androidx.compose.ui.geometry.Size(tabW, tabH * 2),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f),
        style = stroke,
    )

    // Main clipboard body
    val bodyTop = tabH
    drawRoundRect(
        color = color,
        topLeft = Offset(0f, bodyTop),
        size = androidx.compose.ui.geometry.Size(w, h - bodyTop),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f),
        style = stroke,
    )

    // Lines inside clipboard
    val lineLeft = w * 0.2f
    val lineRight = w * 0.8f
    val line1Y = h * 0.45f
    val line2Y = h * 0.62f
    val line3Y = h * 0.79f

    drawLine(color = color, start = Offset(lineLeft, line1Y), end = Offset(lineRight, line1Y), strokeWidth = strokeWidthPx, cap = StrokeCap.Round)
    drawLine(color = color, start = Offset(lineLeft, line2Y), end = Offset(lineRight, line2Y), strokeWidth = strokeWidthPx, cap = StrokeCap.Round)
    drawLine(color = color, start = Offset(lineLeft, line3Y), end = Offset(lineRight * 0.6f, line3Y), strokeWidth = strokeWidthPx, cap = StrokeCap.Round)
}

/**
 * Microphone: rounded rect capsule body + vertical stem + horizontal base line.
 */
internal fun DrawScope.drawVoiceIcon(color: Color, strokeWidthPx: Float) {
    val stroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val w = size.width
    val h = size.height

    // Mic capsule body (top 55% of height)
    val capsuleLeft = w * 0.15f
    val capsuleRight = w * 0.85f
    val capsuleWidth = capsuleRight - capsuleLeft
    val capsuleHeight = h * 0.55f
    drawRoundRect(
        color = color,
        topLeft = Offset(capsuleLeft, 0f),
        size = androidx.compose.ui.geometry.Size(capsuleWidth, capsuleHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(capsuleWidth / 2f),
        style = stroke,
    )

    // Arc below capsule (open-bottom arc)
    val arcPath = Path().apply {
        moveTo(0f, h * 0.42f)
        cubicTo(
            0f, h * 0.85f,
            w, h * 0.85f,
            w, h * 0.42f,
        )
    }
    drawPath(path = arcPath, color = color, style = stroke)

    // Vertical stem from arc bottom to base
    drawLine(
        color = color,
        start = Offset(w / 2f, h * 0.82f),
        end = Offset(w / 2f, h),
        strokeWidth = strokeWidthPx,
        cap = StrokeCap.Round,
    )

    // Horizontal base line
    drawLine(
        color = color,
        start = Offset(w * 0.2f, h),
        end = Offset(w * 0.8f, h),
        strokeWidth = strokeWidthPx,
        cap = StrokeCap.Round,
    )
}

/**
 * Smiley face: circle outline + two dot eyes + smile arc.
 */
private fun DrawScope.drawEmojiIcon(color: Color, strokeWidthPx: Float) {
    val stroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val r = minOf(w, h) / 2f - strokeWidthPx / 2f

    // Outer circle
    drawCircle(color = color, radius = r, center = Offset(cx, cy), style = stroke)

    // Left eye dot
    drawCircle(color = color, radius = strokeWidthPx * 0.9f, center = Offset(cx - r * 0.35f, cy - r * 0.25f))

    // Right eye dot
    drawCircle(color = color, radius = strokeWidthPx * 0.9f, center = Offset(cx + r * 0.35f, cy - r * 0.25f))

    // Smile arc (lower half)
    val smilePath = Path().apply {
        moveTo(cx - r * 0.4f, cy + r * 0.15f)
        cubicTo(
            cx - r * 0.4f, cy + r * 0.6f,
            cx + r * 0.4f, cy + r * 0.6f,
            cx + r * 0.4f, cy + r * 0.15f,
        )
    }
    drawPath(path = smilePath, color = color, style = stroke)
}

/**
 * Settings gear: center circle + radiating lines (sun/gear style).
 */
private fun DrawScope.drawSettingsIcon(color: Color, strokeWidthPx: Float) {
    val stroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val outerR = minOf(w, h) / 2f - strokeWidthPx / 2f
    val innerR = outerR * 0.45f
    val rayStart = outerR * 0.6f

    // Inner circle
    drawCircle(color = color, radius = innerR, center = Offset(cx, cy), style = stroke)

    // 8 radiating lines (rays) around the circle
    val rayCount = 8
    for (i in 0 until rayCount) {
        val angleDeg = i * (360f / rayCount)
        val angleRad = Math.toRadians(angleDeg.toDouble()).toFloat()
        val cosA = kotlin.math.cos(angleRad)
        val sinA = kotlin.math.sin(angleRad)
        drawLine(
            color = color,
            start = Offset(cx + cosA * rayStart, cy + sinA * rayStart),
            end = Offset(cx + cosA * outerR, cy + sinA * outerR),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round,
        )
    }
}
