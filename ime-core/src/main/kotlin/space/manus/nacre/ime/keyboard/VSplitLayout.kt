package space.manus.nacre.ime.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.manus.nacre.ime.NacreInputMethodService
import space.manus.nacre.ime.trackball.TrackballView
import kotlin.math.cos

/**
 * V-split keyboard with candidate bar.
 *
 * Structure:
 *   Column (WRAP_CONTENT)
 *     ├─ CandidateBar (28dp, fully opaque)
 *     └─ Box (clipToBounds) ← prevents rotated keys from bleeding up
 *          └─ Column
 *               ├─ KeyRow 0 (qwerty)
 *               ├─ KeyRow 1 (asdf)
 *               ├─ KeyRow 2 (zxcv + trackball)
 *               └─ KeyRow 3 (Esc/Fn/Space/Tab/Enter)
 */
@Composable
fun VSplitKeyboardScreen(
    service: NacreInputMethodService,
    angle: Float = 4f,
    onFlexPointer: () -> Unit = {},
) {
    val layerManager = service.layerManager
    val layout = layerManager.currentLayout()
    val rows = layout.rows
    val clampedAngle = angle.coerceIn(0f, 15f)

    val keyRowHeight = 48.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(service.currentTheme.background.toInt()))
            .padding(horizontal = 4.dp),
    ) {
        // 1. Candidate bar (fixed 28dp, never overlapped)
        CandidateBar(service = service)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = "Ptr",
                color = Color(service.currentTheme.accent.toInt()),
                fontSize = 10.sp,
                modifier = Modifier.clickable { onFlexPointer() },
            )
        }

        // 2. Key area — clipToBounds prevents V-split rotation overflow into candidate bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Absorb rotation overflow at the top
                Spacer(modifier = Modifier.height(20.dp))

                for ((rowIndex, row) in rows.withIndex()) {
                    val mid = row.size / 2
                    val leftKeys = row.subList(0, mid)
                    val rightKeys = row.subList(mid, row.size)
                    val showTrackball = rowIndex == 2

                    VSplitRow(
                        leftKeys = leftKeys,
                        rightKeys = rightKeys,
                        service = service,
                        angle = clampedAngle,
                        showTrackball = showTrackball,
                        rowIndex = rowIndex,
                        rowHeight = keyRowHeight,
                    )
                }
            }
        }
    }
}

@Composable
private fun VSplitRow(
    leftKeys: List<space.manus.nacre.config.KeyDef>,
    rightKeys: List<space.manus.nacre.config.KeyDef>,
    service: NacreInputMethodService,
    angle: Float,
    showTrackball: Boolean,
    rowIndex: Int = 0,
    rowHeight: androidx.compose.ui.unit.Dp = 48.dp,
) {
    val angleRad = Math.toRadians(angle.toDouble())
    val cosA = cos(angleRad).toFloat()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left half
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .graphicsLayer {
                    rotationZ = angle
                    scaleX = cosA
                    clip = true
                    transformOrigin = TransformOrigin(1f, 0.5f)
                },
        ) {
            for ((colIndex, keyDef) in leftKeys.withIndex()) {
                KeyView(
                    keyDef = keyDef,
                    service = service,
                    modifier = Modifier.weight(keyDef.widthMultiplier),
                    row = rowIndex,
                    column = colIndex,
                    heightDp = 0f,
                )
            }
        }

        // Center: trackball or gap
        if (showTrackball) {
            Spacer(modifier = Modifier.width(2.dp))
            TrackballView(service = service, modifier = Modifier.size(36.dp))
            Spacer(modifier = Modifier.width(2.dp))
        } else {
            Spacer(modifier = Modifier.width(6.dp))
        }

        // Right half
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .graphicsLayer {
                    rotationZ = -angle
                    scaleX = cosA
                    clip = true
                    transformOrigin = TransformOrigin(0f, 0.5f)
                },
        ) {
            val mid = leftKeys.size
            for ((colIndex, keyDef) in rightKeys.withIndex()) {
                KeyView(
                    keyDef = keyDef,
                    service = service,
                    modifier = Modifier.weight(keyDef.widthMultiplier),
                    row = rowIndex,
                    column = mid + colIndex,
                    heightDp = 0f,
                )
            }
        }
    }
}
