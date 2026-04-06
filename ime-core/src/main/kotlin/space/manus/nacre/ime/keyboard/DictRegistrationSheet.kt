package space.manus.nacre.ime.keyboard

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictRegistrationSheet(
    reading: String,
    surface: String,
    onRegister: (reading: String, surface: String, posCategory: String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp)) {
            Text("辞書に登録", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = reading,
                onValueChange = {},
                label = { Text("読み") },
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = surface,
                onValueChange = {},
                label = { Text("表記") },
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            var selectedPos by remember { mutableStateOf("名詞") }
            val posOptions = listOf("名詞", "固有名詞", "動詞")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                posOptions.forEach { pos ->
                    FilterChip(
                        selected = selectedPos == pos,
                        onClick = { selectedPos = pos },
                        label = { Text(pos) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onRegister(reading, surface, selectedPos); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("登録")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
