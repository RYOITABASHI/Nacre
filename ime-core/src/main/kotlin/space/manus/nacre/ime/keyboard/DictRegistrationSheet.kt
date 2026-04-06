package space.manus.nacre.ime.keyboard

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Inline dictionary registration panel.
 *
 * Uses a Card instead of ModalBottomSheet because IME services don't have
 * an Activity window token, which Dialog-based components require.
 */
@Composable
fun DictRegistrationPanel(
    reading: String,
    surface: String,
    onRegister: (reading: String, surface: String, posCategory: String) -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("辞書に登録", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("読み: $reading", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text("表記: $surface", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            var selectedPos by remember { mutableStateOf("名詞") }
            val posOptions = listOf("名詞", "固有名詞", "動詞")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                posOptions.forEach { pos ->
                    FilterChip(
                        selected = selectedPos == pos,
                        onClick = { selectedPos = pos },
                        label = { Text(pos, style = MaterialTheme.typography.labelSmall) },
                    )
                }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    onClick = { onRegister(reading, surface, selectedPos); onDismiss() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text("登録")
                }
                TextButton(onClick = onDismiss) {
                    Text("✕")
                }
            }
        }
    }
}
