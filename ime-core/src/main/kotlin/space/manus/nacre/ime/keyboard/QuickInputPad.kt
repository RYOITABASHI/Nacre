package space.manus.nacre.ime.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.manus.nacre.ime.NacreInputMethodService
import space.manus.nacre.ime.input.MacroEngine
import space.manus.nacre.ime.input.VoiceAsrUiState
import space.manus.nacre.ime.input.VoiceEditUiState
import space.manus.nacre.ime.input.VoiceRefineUiState
import space.manus.nacre.ime.trackball.TrackballView
import kotlinx.coroutines.launch

private val PadBackground = Color(0xFF0A0A1A)
private val MacroButtonBg = Color(0xFF1E1E3A)
private val MacroButtonText = Color(0xFFE0E0E0)
private val VoiceButtonBg = Color(0xFF00D4AA)
private val VoiceButtonText = Color(0xFF0A0A1A)

/**
 * Quick input pad for small sub-displays (e.g. Galaxy Z Flip cover screen).
 *
 * Layout:
 *  - 3x2 grid of customizable macro buttons (minimum 60dp touch targets)
 *  - Large voice input button in the center area
 *  - Mini trackball (40dp) at the bottom
 */
@Composable
fun QuickInputPad(service: NacreInputMethodService) {
    val macroEngine = service.macroEngine
    val macros = macroEngine.macros
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // Take up to 6 macros for the grid
    val gridMacros = macros.take(6)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PadBackground)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 3x2 macro button grid
        for (rowIndex in 0 until 2) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (colIndex in 0 until 3) {
                    val macroIndex = rowIndex * 3 + colIndex
                    val macro = gridMacros.getOrNull(macroIndex)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MacroButtonBg)
                            .then(
                                if (macro != null) {
                                    Modifier
                                        .clickable {
                                            haptic.performHapticFeedback(
                                                HapticFeedbackType.TextHandleMove,
                                            )
                                            val ic = service.currentInputConnection
                                            if (ic != null) {
                                                coroutineScope.launch {
                                                    macroEngine.executeMacro(macro, ic)
                                                }
                                            }
                                        }
                                        .semantics {
                                            contentDescription = "Macro: ${macro.name}"
                                        }
                                } else {
                                    Modifier.semantics {
                                        contentDescription = "Empty macro slot"
                                    }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = macro?.name ?: "",
                            color = MacroButtonText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Voice input button
        val voiceManager = service.voiceInputManager
        val isListening = voiceManager.isListening
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(if (isListening) Color(0xFFFF4444) else VoiceButtonBg)
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (isListening) {
                        voiceManager.stopListening()
                    } else {
                        val lang = if (service.layerManager.isJapanese) "ja-JP" else "en-US"
                        voiceManager.startListening(lang)
                    }
                }
                .semantics {
                    contentDescription = if (isListening) "Stop voice input" else "Voice input"
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isListening) "\u23F9" else "\uD83C\uDFA4",
                fontSize = 28.sp,
                color = if (isListening) Color.White else VoiceButtonText,
            )
        }
        // Show partial result
        if (voiceManager.partialText.isNotEmpty()) {
            Text(
                text = voiceManager.partialText,
                color = Color(0xFFE0E0E0),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        // Voice engine state, shown persistently (not only while listening)
        // so a missing ASR model, an external-recognition fallback, or a
        // dead refiner is visible BEFORE dictation — not silently after.
        Text(
            text = voiceManager.voiceStateSummary,
            color = if (
                voiceManager.voiceAsrUiState == VoiceAsrUiState.MODEL_MISSING ||
                voiceManager.voiceAsrUiState == VoiceAsrUiState.EXTERNAL ||
                voiceManager.voiceAsrUiState == VoiceAsrUiState.ERROR ||
                voiceManager.voiceRefineUiState == VoiceRefineUiState.UNAVAILABLE
            ) {
                Color(0xFFFFAA33)
            } else {
                Color(0xFF888888)
            },
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        // Speak to Edit status: edit mode must be visible BEFORE speaking
        // (the utterance becomes an instruction, not dictation), and its
        // outcome — applied, or failed with the original kept — after.
        val editStatusLine = voiceManager.voiceEditStatusLine
        if (editStatusLine != null) {
            Text(
                text = editStatusLine,
                color = if (voiceManager.voiceEditUiState == VoiceEditUiState.FAILED) {
                    Color(0xFFFF6666)
                } else {
                    Color(0xFF00D4AA)
                },
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Mini trackball
        TrackballView(
            service = service,
            modifier = Modifier.size(40.dp),
        )
    }
}
