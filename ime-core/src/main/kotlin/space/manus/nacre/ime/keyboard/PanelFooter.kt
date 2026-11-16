package space.manus.nacre.ime.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.manus.nacre.ime.NacreInputMethodService
import space.manus.nacre.ime.input.backspaceDeleteCount

private val FooterBg = Color(0xFF1A1A2E)
private val FooterKeyBg = Color(0xFF1E1E3A)
private val FooterKeyText = Color(0xFFE0E0E0)

/**
 * Gboard-style bottom bar for the full-width panels (emoji / symbols):
 * an explicit wide "ABC" return key, plus Space and Backspace, so an
 * emoji or symbol can be inserted mid-sentence — and a mistake undone —
 * without leaving the panel. Before this bar existed, the only way back
 * to the letters was the small ✕ tab in the panel's category row.
 */
@Composable
fun PanelFooter(
    service: NacreInputMethodService,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(FooterBg)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PanelFooterKey(label = "ABC", modifier = Modifier.weight(2f)) { onDismiss() }
        PanelFooterKey(label = "⎵", modifier = Modifier.weight(3f)) {
            service.currentInputConnection?.commitText(" ", 1)
        }
        PanelFooterKey(label = "⌫", modifier = Modifier.weight(1.5f)) {
            // Surrogate-aware, exactly like the engine's own backspace:
            // deleteSurroundingText counts UTF-16 code units, so a naive
            // delete of 1 would split an emoji's surrogate pair in half.
            val ic = service.currentInputConnection
            if (ic != null) {
                val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
                ic.deleteSurroundingText(backspaceDeleteCount(before), 0)
            }
        }
    }
}

@Composable
private fun PanelFooterKey(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(FooterKeyBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, fontSize = 14.sp, color = FooterKeyText)
    }
}
