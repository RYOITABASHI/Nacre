package space.manus.nacre.ime.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.manus.nacre.ime.NacreInputMethodService

private val PanelBg = Color(0xFF0F0F23)
private val CategoryBg = Color(0xFF1A1A2E)
private val CategoryActive = Color(0xFF00D4AA)

private data class EmojiCategory(
    val icon: String,
    val label: String,
    val emojis: List<String>,
)

private val CATEGORIES = listOf(
    EmojiCategory("😊", "スマイル", listOf(
        "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "😊",
        "😇", "🥰", "😍", "🤩", "😘", "😗", "😚", "😙", "🥲", "😋",
        "😛", "😜", "🤪", "😝", "🤑", "🤗", "🤭", "🤫", "🤔", "🫡",
        "🫢", "🫣", "🤐", "🤨", "😐", "😑", "😶", "🫥", "😏", "😒",
        "🙄", "😬", "😮‍💨", "🤥", "🫨", "😌", "😔", "😪", "🤤", "😴",
        "😷", "🤒", "🤕", "🤢", "🤮", "🥵", "🥶", "🥴", "😵", "😵‍💫",
        "🤯", "🤠", "🥳", "🥸", "😎", "🤓", "🧐", "😕", "🫤", "😟",
        "🙁", "☹️", "😮", "😯", "😲", "😳", "🥺", "🥹", "😦", "😧",
        "😨", "😰", "😥", "😢", "😭", "😱", "😖", "😣", "😞", "😓",
        "😩", "😫", "🥱", "😤", "😡", "😠", "🤬", "😈", "👿", "💀",
        "☠️", "💩", "🤡", "👹", "👺", "👻", "👽", "👾", "🤖", "🎃",
    )),
    EmojiCategory("👋", "手・体", listOf(
        "👋", "🤚", "🖐️", "✋", "🖖", "🫱", "🫲", "🫳", "🫴", "🫷",
        "🫸", "👌", "🤌", "🤏", "✌️", "🤞", "🫰", "🤟", "🤘", "🤙",
        "👈", "👉", "👆", "🖕", "👇", "☝️", "🫵", "👍", "👎", "✊",
        "👊", "🤛", "🤜", "👏", "🙌", "🫶", "👐", "🤲", "🤝", "🙏",
        "✍️", "💅", "🤳", "💪", "🦾", "🦿", "🦵", "🦶", "👂", "🦻",
        "👃", "🧠", "🫀", "🫁", "🦷", "🦴", "👀", "👁️", "👅", "👄",
        "🫦", "👶", "🧒", "👦", "👧", "🧑", "👱", "👨", "🧔", "👩",
        "🧓", "👴", "👵", "🙍", "🙎", "🙅", "🙆", "💁", "🙋", "🧏",
        "🙇", "🤦", "🤷", "💆", "💇", "🚶", "🧍", "🧎", "🏃", "💃",
        "🕺", "🧖", "🧗", "🤸", "⛹️", "🏋️", "🚴", "🤼", "🤽", "🤾",
    )),
    EmojiCategory("❤️", "ハート・愛", listOf(
        "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "🩷",
        "🩵", "🩶", "❤️‍🔥", "❤️‍🩹", "💔", "❣️", "💕", "💞", "💓", "💗",
        "💖", "💘", "💝", "💟", "♥️", "💋", "💌", "💐", "🌹", "🥀",
        "🌷", "🌸", "💮", "🏵️", "🌺", "🌻", "🌼", "🌿", "🍀", "🍁",
        "🍂", "🍃", "🪻", "🪷", "🪹", "🪺",
    )),
    EmojiCategory("🐱", "動物・自然", listOf(
        "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐻‍❄️", "🐨",
        "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🙈", "🙉", "🙊", "🐒",
        "🐔", "🐧", "🐦", "🐤", "🦆", "🦅", "🦉", "🦇", "🐺", "🐗",
        "🐴", "🦄", "🐝", "🐛", "🦋", "🐌", "🐞", "🐜", "🦟", "🦗",
        "🕷️", "🕸️", "🦂", "🐢", "🐍", "🦎", "🐙", "🦑", "🦐", "🦞",
        "🦀", "🐡", "🐠", "🐟", "🐬", "🐳", "🐋", "🦈", "🐊", "🐅",
        "🐆", "🦓", "🦍", "🦧", "🐘", "🦛", "🦏", "🐪", "🐫", "🦒",
        "🦘", "🦬", "🐃", "🐂", "🐄", "🐎", "🐖", "🐏", "🐑", "🦙",
        "🐐", "🦌", "🐕", "🐩", "🦮", "🐕‍🦺", "🐈", "🐈‍⬛", "🪶", "🐓",
        "🦃", "🦤", "🦚", "🦜", "🦢", "🦩", "🕊️", "🐇", "🦝", "🦨",
        "🦡", "🦫", "🦦", "🦥", "🐁", "🐀", "🐿️", "🦔",
        "🌍", "🌎", "🌏", "🌐", "🗺️", "🌋", "🗻", "🏔️", "⛰️", "🏕️",
        "🏖️", "🏜️", "🏝️", "🌅", "🌄", "🌠", "🎇", "🎆", "🌇", "🌆",
        "🌃", "🌌", "🌉", "🌁",
    )),
    EmojiCategory("🍔", "フード", listOf(
        "🍏", "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🫐",
        "🍈", "🍒", "🍑", "🥭", "🍍", "🥥", "🥝", "🍅", "🍆", "🥑",
        "🌶️", "🫑", "🥒", "🥬", "🥦", "🧄", "🧅", "🍄", "🥜", "🫘",
        "🌰", "🫚", "🫛", "🍞", "🥐", "🥖", "🫓", "🥨", "🥯", "🥞",
        "🧇", "🧀", "🍖", "🍗", "🥩", "🥓", "🍔", "🍟", "🍕", "🌭",
        "🥪", "🌮", "🌯", "🫔", "🥙", "🧆", "🥚", "🍳", "🥘", "🍲",
        "🫕", "🥣", "🥗", "🍿", "🧈", "🧂", "🥫", "🍱", "🍘", "🍙",
        "🍚", "🍛", "🍜", "🍝", "🍠", "🍢", "🍣", "🍤", "🍥", "🥮",
        "🍡", "🥟", "🥠", "🥡", "🦪", "🍦", "🍧", "🍨", "🍩", "🍪",
        "🎂", "🍰", "🧁", "🥧", "🍫", "🍬", "🍭", "🍮", "🍯",
        "🍼", "🥛", "☕", "🫖", "🍵", "🍶", "🍾", "🍷", "🍸", "🍹",
        "🍺", "🍻", "🥂", "🥃", "🫗", "🥤", "🧋", "🧃", "🧉", "🧊",
    )),
    EmojiCategory("⚽", "スポーツ・活動", listOf(
        "⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏉", "🥏", "🎱",
        "🏓", "🏸", "🏒", "🥅", "⛳", "🪁", "🏹", "🎣", "🤿", "🥊",
        "🥋", "🎽", "🛹", "🛼", "🛷", "⛸️", "🥌", "🎿", "⛷️", "🏂",
        "🪂", "🏋️", "🤼", "🤸", "🤺", "⛹️", "🏇", "🧘", "🏄", "🏊",
        "🤽", "🚣", "🧗", "🚵", "🚴", "🏆", "🥇", "🥈", "🥉", "🏅",
        "🎖️", "🎗️", "🎪", "🎭", "🎨", "🎬", "🎤", "🎧", "🎼", "🎹",
        "🥁", "🪘", "🎷", "🎺", "🪗", "🎸", "🪕", "🎻", "🪈", "🎲",
        "♟️", "🎯", "🎳", "🎮", "🕹️", "🎰",
    )),
    EmojiCategory("🚗", "乗り物・旅行", listOf(
        "🚗", "🚕", "🚙", "🚌", "🚎", "🏎️", "🚓", "🚑", "🚒", "🚐",
        "🛻", "🚚", "🚛", "🚜", "🏍️", "🛵", "🦽", "🦼", "🛺", "🚲",
        "🛴", "🛹", "🛼", "🚏", "🛤️", "🚃", "🚋", "🚞", "🚝", "🚄",
        "🚅", "🚈", "🚂", "🚆", "🚇", "🚊", "🚉", "✈️", "🛩️", "🛫",
        "🛬", "🪂", "💺", "🚀", "🛸", "🚁", "🛶", "⛵", "🚤", "🛥️",
        "🛳️", "⛴️", "🚢", "⚓", "🪝", "⛽", "🚧", "🚦", "🚥", "🗼",
        "🗽", "🗿", "🏰", "🏯", "🏟️", "🎡", "🎢", "🎠", "⛲", "⛱️",
        "🏖️", "🏝️", "🏜️", "🌋", "⛰️", "🏔️", "🗻", "🏕️", "⛺", "🛖",
    )),
    EmojiCategory("💻", "オブジェクト", listOf(
        "💻", "🖥️", "⌨️", "🖱️", "🖨️", "📱", "📲", "☎️", "📞", "📟",
        "📠", "🔋", "🪫", "🔌", "💡", "🔦", "🕯️", "📷", "📸", "📹",
        "🎥", "📽️", "🎬", "📺", "📻", "🎵", "🎶", "🎤", "🎧", "📢",
        "📣", "🔔", "🔕", "🪘", "📯", "🎼", "💎", "💍", "👑", "🎩",
        "🧢", "👓", "🕶️", "🥽", "🧳", "🎒", "👛", "👜", "💼", "📁",
        "📂", "📄", "📃", "📑", "📊", "📈", "📉", "🗒️", "🗓️", "📅",
        "📆", "📇", "🗃️", "🗄️", "🗑️", "📦", "📫", "📪", "📬", "📭",
        "📮", "📝", "✏️", "✒️", "🖋️", "🖊️", "🖌️", "🖍️", "📐", "📏",
        "🔒", "🔓", "🔏", "🔐", "🔑", "🗝️", "🔨", "🪓", "⛏️", "⚒️",
        "🛠️", "🗡️", "⚔️", "🔫", "🪃", "🏹", "🛡️", "🪚", "🔧", "🪛",
        "🔩", "⚙️", "🗜️", "⚖️", "🦯", "🔗", "⛓️", "🪝", "🧲", "🪜",
    )),
    EmojiCategory("⚡", "記号・マーク", listOf(
        "⚡", "🔥", "💥", "✨", "🌟", "⭐", "🌈", "☀️", "🌙", "💫",
        "✅", "❌", "⭕", "❗", "❓", "‼️", "⁉️", "💯", "🆒", "🆕",
        "🆓", "🆗", "🆙", "🆘", "🈁", "🈂️", "🈷️", "🈶", "🈯", "🉐",
        "🈹", "🈚", "🈲", "🉑", "🈸", "🈴", "🈳", "㊗️", "㊙️", "🈺",
        "🔴", "🟠", "🟡", "🟢", "🔵", "🟣", "⚫", "⚪", "🟤", "🟥",
        "🟧", "🟨", "🟩", "🟦", "🟪", "⬛", "⬜", "🟫", "▶️", "⏸️",
        "⏹️", "⏺️", "⏭️", "⏮️", "⏩", "⏪", "🔀", "🔁", "🔂", "🔼",
        "🔽", "➡️", "⬅️", "⬆️", "⬇️", "↗️", "↘️", "↙️", "↖️", "↕️",
        "↔️", "🔄", "♻️", "🔃", "✳️", "❇️", "💠", "🔰", "⚜️", "♾️",
        "🔱", "📛", "🔲", "🔳", "◻️", "◼️", "◽", "◾", "▪️", "▫️",
        "♠️", "♣️", "♥️", "♦️", "🃏", "🀄", "🎴", "🔮", "🪬", "🧿",
        "🎐", "🎑", "🎊", "🎉", "🎋", "🎍", "🎎", "🎏", "🎀", "🎁",
        "🎂", "🎄", "🎅", "🤶", "🧑‍🎄", "🎆", "🎇", "🧨", "✨", "🎈",
    )),
    EmojiCategory("🏳️", "フラグ", listOf(
        "🏁", "🚩", "🎌", "🏴", "🏳️", "🏳️‍🌈", "🏳️‍⚧️", "🏴‍☠️",
        "🇯🇵", "🇺🇸", "🇬🇧", "🇫🇷", "🇩🇪", "🇮🇹", "🇪🇸", "🇵🇹",
        "🇧🇷", "🇲🇽", "🇨🇦", "🇦🇺", "🇳🇿", "🇷🇺", "🇨🇳", "🇰🇷",
        "🇹🇼", "🇭🇰", "🇸🇬", "🇮🇳", "🇹🇭", "🇻🇳", "🇵🇭", "🇮🇩",
        "🇲🇾", "🇸🇦", "🇦🇪", "🇮🇱", "🇹🇷", "🇪🇬", "🇿🇦", "🇳🇬",
        "🇰🇪", "🇪🇹", "🇬🇭", "🇸🇪", "🇳🇴", "🇩🇰", "🇫🇮", "🇮🇸",
        "🇨🇭", "🇦🇹", "🇧🇪", "🇳🇱", "🇵🇱", "🇨🇿", "🇬🇷", "🇺🇦",
        "🇦🇷", "🇨🇱", "🇨🇴", "🇵🇪", "🇻🇪", "🇨🇺", "🇯🇲", "🇵🇷",
    )),
)

// Recently used — persisted to SharedPreferences
private val recentEmojis = mutableStateListOf<String>()
private var recentEmojisLoaded = false

private fun loadRecentEmojis(service: NacreInputMethodService) {
    if (recentEmojisLoaded) return
    recentEmojisLoaded = true
    try {
        val prefs = service.getSharedPreferences("nacre_emoji", android.content.Context.MODE_PRIVATE)
        val data = prefs.getString("recent", null) ?: return
        val items = data.split("\t").filter { it.isNotEmpty() }
        recentEmojis.clear()
        recentEmojis.addAll(items.take(40))
    } catch (_: Exception) {}
}

private fun saveRecentEmojis(service: NacreInputMethodService) {
    try {
        val prefs = service.getSharedPreferences("nacre_emoji", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("recent", recentEmojis.joinToString("\t")).apply()
    } catch (_: Exception) {}
}

@Composable
fun EmojiPanel(
    service: NacreInputMethodService,
    onDismiss: () -> Unit,
) {
    // Load recent emojis from persistence on first display
    LaunchedEffect(Unit) { loadRecentEmojis(service) }
    var selectedCategory by remember { mutableIntStateOf(0) }
    val currentEmojis = if (recentEmojis.isNotEmpty() && selectedCategory == -1) {
        recentEmojis.toList()
    } else {
        CATEGORIES.getOrNull(selectedCategory)?.emojis ?: emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .background(PanelBg),
    ) {
        // Category tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CategoryBg)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Recent
            if (recentEmojis.isNotEmpty()) {
                CategoryTab(
                    icon = "🕐",
                    isSelected = selectedCategory == -1,
                    onClick = { selectedCategory = -1 },
                )
            }
            // Categories
            CATEGORIES.forEachIndexed { index, cat ->
                CategoryTab(
                    icon = cat.icon,
                    isSelected = selectedCategory == index,
                    onClick = { selectedCategory = index },
                )
            }
            // Close
            CategoryTab(
                icon = "✕",
                isSelected = false,
                onClick = onDismiss,
            )
        }

        // Emoji grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 4.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(currentEmojis) { emoji ->
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            val ic = service.currentInputConnection
                            ic?.commitText(emoji, 1)
                            recentEmojis.remove(emoji)
                            recentEmojis.add(0, emoji)
                            if (recentEmojis.size > 40) {
                                recentEmojis.removeRange(40, recentEmojis.size)
                            }
                            saveRecentEmojis(service)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = emoji,
                        fontSize = 24.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // Gboard-style return bar: ABC back to the letters, Space / ⌫
        // without leaving the panel.
        PanelFooter(service = service, onDismiss = onDismiss)
    }
}

@Composable
private fun CategoryTab(
    icon: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) CategoryActive.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = icon,
            fontSize = if (icon == "✕") 12.sp else 16.sp,
            color = if (icon == "✕") Color(0xFF6B7280) else Color.Unspecified,
        )
    }
}
