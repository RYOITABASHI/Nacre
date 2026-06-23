package space.manus.nacre.ime.keyboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.manus.nacre.ime.NacreInputMethodService
import space.manus.nacre.ime.input.ClipboardEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PanelBackground = Color(0xFF0F0F23)
private val EntryBackground = Color(0xFF2A2A4A)
private val EntryText = Color(0xFFE0E0E0)
private val TimestampText = Color(0xFF6666AA)
private val AccentColor = Color(0xFF00D4AA)
private val HeaderText = Color(0xFFE0E0E0)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClipboardPanel(
    service: NacreInputMethodService,
    onDismiss: () -> Unit,
) {
    val clipboardManager = service.clipboardManager
    val entries = clipboardManager.history
    val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .background(PanelBackground)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Clipboard",
                color = HeaderText,
                fontSize = 14.sp,
            )
            Row {
                TextButton(onClick = {
                    clipboardManager.clearHistory()
                }) {
                    Text(text = "Clear All", color = AccentColor, fontSize = 12.sp)
                }
                TextButton(onClick = onDismiss) {
                    Text(text = "Close", color = AccentColor, fontSize = 12.sp)
                }
            }
        }

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No clipboard history",
                    color = TimestampText,
                    fontSize = 13.sp,
                )
            }
        } else {
            // Pinned entries first, then most-recent. Actions map back to the
            // entry's index in the unsorted history (identity = text + timestamp).
            val sorted = entries.toList().sortedWith(
                compareByDescending<ClipboardEntry> { it.pinned }.thenByDescending { it.timestamp },
            )
            fun historyIndexOf(entry: ClipboardEntry): Int =
                clipboardManager.history.indexOfFirst { it.text == entry.text && it.timestamp == entry.timestamp }
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(
                    items = sorted,
                    key = { _, entry -> "${entry.timestamp}_${entry.text.hashCode()}" },
                ) { _, entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(EntryBackground)
                            .combinedClickable(
                                onClick = {
                                    // Paste the entry
                                    service.currentInputConnection?.commitText(entry.text, 1)
                                    onDismiss()
                                },
                                onLongClick = {
                                    val hi = historyIndexOf(entry)
                                    if (hi >= 0) clipboardManager.removeEntry(hi)
                                },
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = entry.text.take(50).replace('\n', ' '),
                            color = EntryText,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = dateFormat.format(Date(entry.timestamp)),
                            color = TimestampText,
                            fontSize = 11.sp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // Pin toggle: pinned entries are kept forever and shown first.
                        Text(
                            text = if (entry.pinned) "★" else "☆",
                            color = if (entry.pinned) androidx.compose.ui.graphics.Color(0xFFFFC107) else TimestampText,
                            fontSize = 16.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .combinedClickable(onClick = {
                                    val hi = historyIndexOf(entry)
                                    if (hi >= 0) clipboardManager.togglePin(hi)
                                })
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
