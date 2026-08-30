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

private data class SymbolCategory(
    val icon: String,
    val label: String,
    val symbols: List<String>,
)

private val SYMBOL_CATEGORIES = listOf(
    SymbolCategory("🕐", "最近", emptyList()), // Placeholder — filled by recentSymbols
    SymbolCategory("+-", "算術", listOf(
        "+", "−", "×", "÷", "=", "≠", "≈", "≡", "±", "∓",
        "<", ">", "≤", "≥", "≪", "≫", "∝", "√", "∛", "∜",
        "∞", "∑", "∏", "∫", "∂", "∇", "∆", "∀", "∃", "∄",
        "∈", "∉", "∋", "∌", "⊂", "⊃", "⊄", "⊅", "⊆", "⊇",
        "∪", "∩", "∅", "⊕", "⊗", "⊖", "⊘", "⊙", "⊚", "⊛",
        "%", "‰", "‱", "°", "′", "″",
    )),
    SymbolCategory("αβ", "ギリシャ", listOf(
        "α", "β", "γ", "δ", "ε", "ζ", "η", "θ", "ι", "κ",
        "λ", "μ", "ν", "ξ", "ο", "π", "ρ", "σ", "τ", "υ",
        "φ", "χ", "ψ", "ω",
        "Α", "Β", "Γ", "Δ", "Ε", "Ζ", "Η", "Θ", "Ι", "Κ",
        "Λ", "Μ", "Ν", "Ξ", "Ο", "Π", "Ρ", "Σ", "Τ", "Υ",
        "Φ", "Χ", "Ψ", "Ω",
    )),
    SymbolCategory("→", "矢印", listOf(
        "→", "←", "↑", "↓", "↔", "↕", "↗", "↘", "↙", "↖",
        "⇒", "⇐", "⇑", "⇓", "⇔", "⇕", "⇗", "⇘", "⇙", "⇖",
        "⟹", "⟸", "⟺", "↦", "↤", "↩", "↪", "⟲", "⟳",
        "▶", "◀", "▲", "▼", "►", "◄", "△", "▽",
        "⤴", "⤵", "↰", "↱", "↲", "↳", "↴", "↵",
    )),
    SymbolCategory("「」", "括弧", listOf(
        "「", "」", "『", "』", "【", "】", "〈", "〉", "《", "》",
        "〔", "〕", "｛", "｝", "（", "）", "［", "］",
        "{", "}", "(", ")", "[", "]", "<", ">",
        "〝", "〟", "«", "»", "‹", "›", """, """, "'", "'",
        "「", "」", "⌈", "⌉", "⌊", "⌋", "⟨", "⟩", "⟪", "⟫",
    )),
    SymbolCategory("♪", "音楽・装飾", listOf(
        "♩", "♪", "♫", "♬", "♭", "♮", "♯",
        "★", "☆", "✦", "✧", "✪", "✫", "✬", "✭", "✮", "✯",
        "♠", "♣", "♥", "♦", "♤", "♧", "♡", "♢",
        "⚀", "⚁", "⚂", "⚃", "⚄", "⚅",
        "☀", "☁", "☂", "☃", "☄", "☽", "☾",
        "♿", "⚕", "⚖", "⚗", "⚘", "⚙", "⚛", "⚜",
        "†", "‡", "§", "¶", "©", "®", "™", "℠",
    )),
    SymbolCategory("#@", "プログラミング", listOf(
        "#", "@", "$", "&", "|", "\\", "/", "~", "`", "^",
        "!", "?", ";", ":", ",", ".", "_", "-",
        "=", "+", "*", "%", "<", ">",
        "{", "}", "[", "]", "(", ")",
        "\"", "'", "/*", "*/", "//", "=>", "->", "<-",
        "!=", "==", "===", "!==", ">=", "<=", "&&", "||",
        "::", "..", "...", "??", "?.", "|>", "<|",
        "#{", "<%", "%>", "<?", "?>", "<!--", "-->",
    )),
    SymbolCategory("¥$", "通貨", listOf(
        "¥", "$", "€", "£", "₩", "₹", "₽", "₺",
        "₿", "¢", "₮", "₱", "₫", "₴", "₪", "₡",
        "₣", "₤", "₦", "₧", "₨", "₭", "₯", "₰",
    )),
    SymbolCategory("①②", "囲み数字", listOf(
        "①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩",
        "⑪", "⑫", "⑬", "⑭", "⑮", "⑯", "⑰", "⑱", "⑲", "⑳",
        "⓪", "Ⓐ", "Ⓑ", "Ⓒ", "Ⓓ", "Ⓔ", "Ⓕ", "Ⓖ", "Ⓗ", "Ⓘ",
        "¹", "²", "³", "⁴", "⁵", "⁶", "⁷", "⁸", "⁹", "⁰",
        "₁", "₂", "₃", "₄", "₅", "₆", "₇", "₈", "₉", "₀",
        "½", "⅓", "⅔", "¼", "¾", "⅕", "⅖", "⅗", "⅘", "⅙",
        "⅛", "⅜", "⅝", "⅞",
    )),
    SymbolCategory("─┐", "罫線", listOf(
        "─", "━", "│", "┃", "┌", "┐", "└", "┘",
        "├", "┤", "┬", "┴", "┼",
        "┏", "┓", "┗", "┛", "┣", "┫", "┳", "┻", "╋",
        "╔", "╗", "╚", "╝", "╠", "╣", "╦", "╩", "╬",
        "═", "║", "╒", "╕", "╘", "╛", "╞", "╡", "╥", "╨",
        "▀", "▄", "█", "▌", "▐", "░", "▒", "▓",
        "■", "□", "▢", "▣", "▤", "▥", "▦", "▧", "▨", "▩",
        "●", "○", "◉", "◎", "◐", "◑", "◒", "◓",
        "◆", "◇", "◈", "◊", "◌", "◍", "◯",
    )),
    SymbolCategory("㍻㍼", "特殊日本語", listOf(
        "〒", "〓", "〃", "仝", "ゝ", "ゞ", "ヽ", "ヾ", "々",
        "㍉", "㌔", "㌢", "㍍", "㌘", "㌧", "㌃", "㌶", "㍑", "㍗",
        "㌍", "㌦", "㌣", "㌫", "㍊", "㌻",
        "㍻", "㍼", "㍽", "㍾", "㍿",
        "㈱", "㈲", "㈳", "㈴", "㈵", "㈶", "㈷", "㈸", "㈹", "㈺",
        "㊀", "㊁", "㊂", "㊃", "㊄", "㊅", "㊆", "㊇", "㊈", "㊉",
        "㋀", "㋁", "㋂", "㋃", "㋄", "㋅", "㋆", "㋇", "㋈", "㋉", "㋊", "㋋",
        "♨", "〠", "〶", "〄",
    )),
)

// Index of the "プログラミング" category above — the panel defaults to this
// tab during Shelly Dev Mode (see SymbolsPanel below) since it already covers
// every symbol a terminal/code session commonly needs (/, -, _, ., ~, |, &, $,
// {, }, [, ]) without any reordering. Looked up by label (not hardcoded) so a
// future reordering of SYMBOL_CATEGORIES can't silently point this at the
// wrong tab (Codex review, 2026-08-30).
private val PROGRAMMING_CATEGORY_INDEX =
    SYMBOL_CATEGORIES.indexOfFirst { it.label == "プログラミング" }.let { if (it < 0) 1 else it }

// Recently used symbols — persisted
private val recentSymbols = mutableStateListOf<String>()
private var recentSymbolsLoaded = false

private fun loadRecentSymbols(service: NacreInputMethodService) {
    if (recentSymbolsLoaded) return
    recentSymbolsLoaded = true
    try {
        val prefs = service.getSharedPreferences("nacre_symbols", android.content.Context.MODE_PRIVATE)
        val data = prefs.getString("recent", null) ?: return
        val items = data.split("\t").filter { it.isNotEmpty() }
        recentSymbols.clear()
        recentSymbols.addAll(items.take(40))
    } catch (_: Exception) {}
}

private fun saveRecentSymbols(service: NacreInputMethodService) {
    try {
        val prefs = service.getSharedPreferences("nacre_symbols", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("recent", recentSymbols.joinToString("\t")).apply()
    } catch (_: Exception) {}
}

@Composable
fun SymbolsPanel(
    service: NacreInputMethodService,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { loadRecentSymbols(service) }
    // Default to recent if available; otherwise, in Shelly Dev Mode, jump
    // straight to "プログラミング" (already has every commonly-needed dev
    // symbol) instead of the generic "算術" tab — makes symbol access easier
    // without touching the flick-key layout itself.
    val isDevMode = service.inputEngine.isShellyDevModeActive
    var selectedCategory by remember(isDevMode) {
        mutableIntStateOf(
            when {
                recentSymbols.isNotEmpty() -> 0
                isDevMode -> PROGRAMMING_CATEGORY_INDEX
                else -> 1
            },
        )
    }
    val currentSymbols = if (selectedCategory == 0 && recentSymbols.isNotEmpty()) {
        recentSymbols.toList()
    } else {
        SYMBOL_CATEGORIES.getOrNull(selectedCategory)?.symbols ?: emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .background(PanelBg),
    ) {
        // Category tabs (scrollable)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CategoryBg)
                .padding(horizontal = 2.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SYMBOL_CATEGORIES.forEachIndexed { index, cat ->
                // Skip "最近" tab if no history
                if (index == 0 && recentSymbols.isEmpty()) return@forEachIndexed
                SymbolCategoryTab(
                    icon = cat.icon,
                    isSelected = selectedCategory == index,
                    onClick = { selectedCategory = index },
                )
            }
            // Close
            SymbolCategoryTab(
                icon = "✕",
                isSelected = false,
                onClick = onDismiss,
            )
        }

        // Symbol grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 4.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(currentSymbols) { symbol ->
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .padding(1.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF1E1E3A))
                        .clickable {
                            val ic = service.currentInputConnection
                            ic?.commitText(symbol, 1)
                            recentSymbols.remove(symbol)
                            recentSymbols.add(0, symbol)
                            if (recentSymbols.size > 40) {
                                recentSymbols.removeRange(40, recentSymbols.size)
                            }
                            saveRecentSymbols(service)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = symbol,
                        fontSize = if (symbol.length > 2) 11.sp else 18.sp,
                        color = Color(0xFFE0E0E0),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SymbolCategoryTab(
    icon: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(28.dp)
            .widthIn(min = 28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) CategoryActive.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = icon,
            fontSize = if (icon == "✕") 11.sp else if (icon.length > 2) 9.sp else 13.sp,
            color = if (icon == "✕") Color(0xFF6B7280) else if (isSelected) CategoryActive else Color(0xFFB0B0B0),
        )
    }
}
