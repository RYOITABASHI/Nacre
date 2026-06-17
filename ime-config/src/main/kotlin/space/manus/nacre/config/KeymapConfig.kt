package space.manus.nacre.config

data class KeyDef(
    val primary: String,
    val label: String = primary,
    val longPress: String? = null,
    val swipeUp: String? = null,
    val swipeDown: String? = null,
    val swipeLeft: String? = null,
    val swipeRight: String? = null,
    val widthMultiplier: Float = 1f,
    val action: KeyAction = KeyAction.Text(primary),
)

sealed class KeyAction {
    data class Text(val text: String) : KeyAction()
    data class KeyCode(val code: Int, val ctrl: Boolean = false) : KeyAction()
    data object Backspace : KeyAction()
    data object Enter : KeyAction()
    data object Space : KeyAction()
    data object Shift : KeyAction()
    data object Fn : KeyAction()
    data object FnPage2 : KeyAction()
    data object Escape : KeyAction()
    data object Tab : KeyAction()
    data object SwitchIme : KeyAction()
    data object ToggleJapanese : KeyAction()
    data object Emoji : KeyAction()
    data object Symbols : KeyAction()
    data object Alt : KeyAction()
    data object Henkan : KeyAction()
}

data class KeyboardLayout(
    val rows: List<List<KeyDef>>,
)

object DefaultLayouts {

    val qwertyBase: KeyboardLayout = KeyboardLayout(
        rows = listOf(
            // Row 1: Q-P
            listOf(
                key("q", swipeUp = "1", swipeLeft = "~", swipeRight = "`"),
                key("w", swipeUp = "2"), key("e", swipeUp = "3"),
                key("r", swipeUp = "4"), key("t", swipeUp = "5"),
                key("y", swipeUp = "6"), key("u", swipeUp = "7"),
                key("i", swipeUp = "8"), key("o", swipeUp = "9"),
                key("p", swipeUp = "0", swipeLeft = "[", swipeRight = "]"),
            ),
            // Row 2: A-L + ー
            listOf(
                key("a", swipeUp = "@", swipeLeft = "{", swipeRight = "}"),
                key("s", swipeUp = "#"), key("d", swipeUp = "$"),
                key("f", swipeUp = "%"), key("g", swipeUp = "&"),
                key("h", swipeUp = "*"), key("j", swipeUp = "("),
                key("k", swipeUp = ")"), key("l", swipeUp = "-", swipeRight = "="),
                key("ー", label = "ー", swipeUp = "〜", swipeDown = ":", swipeRight = ";"),
            ),
            // Row 3: Z-. + ⌫
            listOf(
                key("z", swipeUp = "!", swipeLeft = "\""),
                key("x", swipeUp = "\"", swipeRight = "'"),
                key("c", swipeUp = "'"),
                key("v", swipeUp = "/", swipeRight = "|"),
                key("b", swipeUp = "\\"), key("n", swipeUp = "?"),
                key("m", swipeUp = "+", swipeRight = "="),
                key(",", label = ",", swipeUp = "<", swipeRight = ">"),
                key(".", swipeUp = "！", swipeDown = "。"),
                KeyDef("⌫", action = KeyAction.Backspace, swipeLeft = "⌫w"),
            ),
            // Row 4: Shift ↑ ↓ Fn Space Alt Enter
            listOf(
                KeyDef("Shift", action = KeyAction.Shift, widthMultiplier = 0.9f),
                KeyDef("↑", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_UP), widthMultiplier = 0.45f),
                KeyDef("↓", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_DOWN), widthMultiplier = 0.45f),
                KeyDef("Fn", action = KeyAction.Fn, widthMultiplier = 0.6f),
                KeyDef(" ", label = "⎵", action = KeyAction.Space, widthMultiplier = 3f,
                    swipeUp = "Tab", swipeLeft = "ToggleJa", swipeRight = "ToggleJa"),
                KeyDef("Alt", action = KeyAction.Alt, widthMultiplier = 0.7f),
                KeyDef("↵", action = KeyAction.Enter, widthMultiplier = 1.2f),
            ),
        ),
    )

    /**
     * Compact cover-screen layout for Z Fold class sub-displays.
     *
     * Keeps the Japanese-first base behavior, but trims the bottom row down to
     * the essentials so the cover display stays usable with a thumb.
     */
    val compactJapaneseQwerty: KeyboardLayout = KeyboardLayout(
        rows = listOf(
            // Row 1: Q-P
            listOf(
                key("q", swipeUp = "1", swipeLeft = "~", swipeRight = "`"),
                key("w", swipeUp = "2"), key("e", swipeUp = "3"),
                key("r", swipeUp = "4"), key("t", swipeUp = "5"),
                key("y", swipeUp = "6"), key("u", swipeUp = "7"),
                key("i", swipeUp = "8"), key("o", swipeUp = "9"),
                key("p", swipeUp = "0", swipeLeft = "[", swipeRight = "]"),
            ),
            // Row 2: A-L + ー
            listOf(
                key("a", swipeUp = "@", swipeLeft = "{", swipeRight = "}"),
                key("s", swipeUp = "#"), key("d", swipeUp = "$"),
                key("f", swipeUp = "%"), key("g", swipeUp = "&"),
                key("h", swipeUp = "*"), key("j", swipeUp = "("),
                key("k", swipeUp = ")"), key("l", swipeUp = "-", swipeRight = "="),
                key("ー", label = "ー", swipeUp = "〜", swipeDown = ":", swipeRight = ";"),
            ),
            // Row 3: Z-. + ⌫
            listOf(
                key("z", swipeUp = "!", swipeLeft = "\""),
                key("x", swipeUp = "\"", swipeRight = "'"),
                key("c", swipeUp = "'"),
                key("v", swipeUp = "/", swipeRight = "|"),
                key("b", swipeUp = "\\"), key("n", swipeUp = "?"),
                key("m", swipeUp = "+", swipeRight = "="),
                key(",", label = ",", swipeUp = "<", swipeRight = ">"),
                key(".", swipeUp = "！", swipeDown = "。"),
                KeyDef("⌫", action = KeyAction.Backspace, swipeLeft = "⌫w"),
            ),
            // Row 4: Japanese-first compact modifier row for cover screens.
            listOf(
                KeyDef("Shift", action = KeyAction.Shift, widthMultiplier = 0.95f),
                KeyDef("😀", action = KeyAction.Emoji, widthMultiplier = 0.6f),
                KeyDef("#+", label = "#+", action = KeyAction.Symbols, widthMultiplier = 0.6f),
                KeyDef("Fn", action = KeyAction.Fn, widthMultiplier = 0.6f),
                KeyDef(" ", label = "⎵", action = KeyAction.Space, widthMultiplier = 2.2f,
                    swipeUp = "Tab", swipeLeft = "ToggleJa", swipeRight = "ToggleJa"),
                KeyDef("Alt", action = KeyAction.Alt, widthMultiplier = 0.75f),
                KeyDef("↵", action = KeyAction.Enter, widthMultiplier = 1.85f),
            ),
        ),
    )

    val fnLayer1: KeyboardLayout = KeyboardLayout(
        rows = listOf(
            listOf(
                key("1"), key("2"), key("3"), key("4"), key("5"),
                key("6"), key("7"), key("8"), key("9"), key("0"),
            ),
            listOf(
                key("!"), key("@"), key("#"), key("$"), key("%"),
                key("^"), key("&"), key("*"),
                key("(", swipeUp = "[", swipeLeft = "{"),
                key(")", swipeUp = "]", swipeRight = "}"),
            ),
            listOf(
                KeyDef("C-c", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_C, ctrl = true)),
                KeyDef("C-d", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_D, ctrl = true)),
                KeyDef("C-z", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_Z, ctrl = true)),
                key("|"),
                KeyDef("←", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_LEFT)),
                KeyDef("↓", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_DOWN)),
                KeyDef("↑", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_UP)),
                KeyDef("→", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)),
                key("~"),
                KeyDef("⌫", action = KeyAction.Backspace, swipeLeft = "⌫w"),
            ),
            // Row 4: Shift ↑ ↓ 😀 #+ Fn2 Space Alt Enter
            listOf(
                KeyDef("Shift", action = KeyAction.Shift, widthMultiplier = 0.9f),
                KeyDef("↑", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_UP), widthMultiplier = 0.45f),
                KeyDef("↓", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_DOWN), widthMultiplier = 0.45f),
                KeyDef("😀", action = KeyAction.Emoji, widthMultiplier = 0.6f),
                KeyDef("#+", label = "#+", action = KeyAction.Symbols, widthMultiplier = 0.6f),
                KeyDef("Fn2", action = KeyAction.FnPage2, widthMultiplier = 0.6f),
                KeyDef(" ", label = "⎵", action = KeyAction.Space, widthMultiplier = 1.8f,
                    swipeUp = "Tab", swipeLeft = "ToggleJa", swipeRight = "ToggleJa"),
                KeyDef("Alt", action = KeyAction.Alt, widthMultiplier = 0.7f),
                KeyDef("↵", action = KeyAction.Enter, widthMultiplier = 1.2f),
            ),
        ),
    )

    val fnLayer2: KeyboardLayout = KeyboardLayout(
        rows = listOf(
            listOf(
                KeyDef("F1", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_F1)),
                KeyDef("F2", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_F2)),
                KeyDef("F3", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_F3)),
                KeyDef("F4", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_F4)),
                KeyDef("F5", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_F5)),
                KeyDef("F6", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_F6)),
            ),
            listOf(
                KeyDef("F7", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_F7)),
                KeyDef("F8", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_F8)),
                KeyDef("F9", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_F9)),
                KeyDef("F10", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_F10)),
                KeyDef("F11", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_F11)),
                KeyDef("Del", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_FORWARD_DEL)),
                key("`"),
                KeyDef("Ins", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_INSERT)),
                KeyDef("F12", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_F12)),
                key("_"),
            ),
            listOf(
                KeyDef("Home", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_MOVE_HOME)),
                KeyDef("End", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_MOVE_END)),
                KeyDef("PgUp", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_PAGE_UP)),
                KeyDef("PgDn", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_PAGE_DOWN)),
                key("|"), key("\\"), key("~"),
                key("`"),
                key("_"),
                KeyDef("⌫", action = KeyAction.Backspace, swipeLeft = "⌫w"),
            ),
            // Row 4: Shift ↑ ↓ Fn Space Alt Enter
            listOf(
                KeyDef("Shift", action = KeyAction.Shift, widthMultiplier = 0.9f),
                KeyDef("↑", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_UP), widthMultiplier = 0.45f),
                KeyDef("↓", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_DOWN), widthMultiplier = 0.45f),
                KeyDef("Fn", action = KeyAction.Fn, widthMultiplier = 0.6f),
                KeyDef(" ", label = "⎵", action = KeyAction.Space, widthMultiplier = 3f,
                    swipeUp = "Tab", swipeLeft = "ToggleJa", swipeRight = "ToggleJa"),
                KeyDef("Alt", action = KeyAction.Alt, widthMultiplier = 0.7f),
                KeyDef("↵", action = KeyAction.Enter, widthMultiplier = 1.2f),
            ),
        ),
    )

    private fun key(
        primary: String,
        label: String = primary,
        swipeUp: String? = null,
        swipeDown: String? = null,
        swipeLeft: String? = null,
        swipeRight: String? = null,
    ) = KeyDef(
        primary = primary,
        label = label,
        swipeUp = swipeUp,
        swipeDown = swipeDown,
        swipeLeft = swipeLeft,
        swipeRight = swipeRight,
    )
}
