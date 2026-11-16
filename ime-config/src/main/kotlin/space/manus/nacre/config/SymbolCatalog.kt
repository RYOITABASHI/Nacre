package space.manus.nacre.config

/**
* Symbol sets shared by the keyboard surfaces, as pure data so the
* contents are unit-testable without the Compose panels that render them.
*/
object SymbolCatalog {

/**
* Japanese punctuation and common full-width symbols — the set a
* Japanese user reaches for mid-sentence: 、。・, brackets, ！？,
* 〜, ellipses, and full-width operators.
*
* Until this existed, the symbols panel had NO Japanese punctuation
* category at all (its defaults were arithmetic / programming), and
* 「」・… were only reachable through conversion candidates or a
* category hunt. Ordered roughly by frequency; exactly 40 entries so
* the panel's 8-column grid fills complete rows.
*/
val japanesePunctuation: List<String> = listOf(
"、", "。", "・", "「", "」", "『", "』", "【", "】", "（", "）", "！", "？", "〜", "…", "‥",
"：", "；", "／", "｜", "―", "－", "＝", "＋",
"％", "＃", "＠", "＆", "＊", "￥", "〈", "〉",
"《", "》", "〔", "〕", "｛", "｝", "［", "］",
)
}
