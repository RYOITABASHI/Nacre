package space.manus.nacre.ai

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PostProcessorTest {

    private lateinit var processor: PostProcessor

    @Before
    fun setup() {
        processor = PostProcessor()
    }

    // ══════════════════════════════════════════════════════
    //  1. Filler removal — Japanese
    // ══════════════════════════════════════════════════════

    @Test
    fun `removeFiller strips Japanese fillers at start`() {
        assertEquals("今日は天気がいい", processor.removeFiller("えーと今日は天気がいい"))
        assertEquals("明日会議があります", processor.removeFiller("あのー明日会議があります"))
        assertEquals("そうですね", processor.removeFiller("うーんそうですね"))
        assertEquals("はい", processor.removeFiller("あーはい"))
        assertEquals("質問です", processor.removeFiller("そのー質問です"))
        assertEquals("やります", processor.removeFiller("なんかやります"))
        assertEquals("見て", processor.removeFiller("ほら見て"))
    }

    @Test
    fun `removeFiller strips Japanese fillers with elongation`() {
        assertEquals("今日は", processor.removeFiller("えーーーと今日は"))
        assertEquals("明日", processor.removeFiller("あのーーー明日"))
        assertEquals("そう", processor.removeFiller("うーーーん そう"))
        assertEquals("はい", processor.removeFiller("あーーーはい"))
        assertEquals("質問", processor.removeFiller("えーーー質問"))
    }

    @Test
    fun `removeFiller strips extreme elongation`() {
        assertEquals("今日は", processor.removeFiller("えーーーーーと今日は"))
        assertEquals("明日", processor.removeFiller("あのーーーー明日"))
    }

    @Test
    fun `removeFiller strips mid-sentence Japanese fillers`() {
        assertEquals("今日は天気がいいですね", processor.removeFiller("今日はえーと天気がいいですね"))
        assertEquals("明日の会議に出ます", processor.removeFiller("明日のあのー会議に出ます"))
    }

    @Test
    fun `removeFiller strips multiple Japanese fillers`() {
        assertEquals("今日は天気がいい", processor.removeFiller("えーとあのー今日は天気がいい"))
    }

    @Test
    fun `removeFiller strips combined consecutive fillers`() {
        assertEquals("行きます", processor.removeFiller("えーとあのーうーん行きます"))
    }

    @Test
    fun `removeFiller strips えっと variant`() {
        assertEquals("今日は", processor.removeFiller("えっと今日は"))
    }

    @Test
    fun `removeFiller strips ええと variant`() {
        assertEquals("はい", processor.removeFiller("ええとはい"))
    }

    @Test
    fun `removeFiller strips んーと variant`() {
        assertEquals("そうだね", processor.removeFiller("んーとそうだね"))
    }

    @Test
    fun `removeFiller strips んー variant`() {
        assertEquals("わかった", processor.removeFiller("んーわかった"))
    }

    @Test
    fun `removeFiller strips ああ variant`() {
        assertEquals("そうか", processor.removeFiller("ああそうか"))
    }

    @Test
    fun `removeFiller strips まあ`() {
        assertEquals("いいよ", processor.removeFiller("まあいいよ"))
    }

    @Test
    fun `removeFiller preserves meaningful words that look like fillers`() {
        // そう as a response is NOT a filler
        assertEquals("そうですね", processor.removeFiller("そうですね"))
    }

    // ══════════════════════════════════════════════════════
    //  2. Filler removal — English
    // ══════════════════════════════════════════════════════

    @Test
    fun `removeFiller strips English fillers`() {
        assertEquals("I think so", processor.removeFiller("um I think so"))
        assertEquals("that's right", processor.removeFiller("uh that's right"))
        assertEquals("let me check", processor.removeFiller("you know let me check"))
        assertEquals("I see", processor.removeFiller("basically I see"))
        assertEquals("yes", processor.removeFiller("uh-huh yes"))
    }

    @Test
    fun `removeFiller strips start-only English fillers at beginning`() {
        assertEquals("that works", processor.removeFiller("like that works"))
        assertEquals("let's go", processor.removeFiller("well let's go"))
        assertEquals("it's fine", processor.removeFiller("actually it's fine"))
        assertEquals("let's begin", processor.removeFiller("so let's begin"))
    }

    @Test
    fun `removeFiller strips literally and right at start`() {
        assertEquals("the best", processor.removeFiller("literally the best"))
        assertEquals("let's do it", processor.removeFiller("right let's do it"))
    }

    @Test
    fun `removeFiller strips okay so at start`() {
        assertEquals("we need to", processor.removeFiller("okay so we need to"))
    }

    @Test
    fun `removeFiller preserves ambiguous English fillers mid-sentence`() {
        assertEquals("I think so", processor.removeFiller("I think so"))
        assertEquals("I like it", processor.removeFiller("I like it"))
        assertEquals("that went well", processor.removeFiller("that went well"))
        assertEquals("it's actually good", processor.removeFiller("it's actually good"))
    }

    @Test
    fun `removeFiller handles English fillers case-insensitively`() {
        assertEquals("I think so", processor.removeFiller("Um I think so"))
        assertEquals("that's right", processor.removeFiller("UH that's right"))
    }

    @Test
    fun `removeFiller strips mid-sentence English fillers`() {
        assertEquals("I think that's correct", processor.removeFiller("I think um that's correct"))
    }

    @Test
    fun `removeFiller strips English fillers with commas`() {
        assertEquals("I think so", processor.removeFiller("um, I think so"))
    }

    // ══════════════════════════════════════════════════════
    //  3. Filler removal — edge cases
    // ══════════════════════════════════════════════════════

    @Test
    fun `removeFiller preserves text without fillers`() {
        assertEquals("普通のテキスト", processor.removeFiller("普通のテキスト"))
        assertEquals("Hello world", processor.removeFiller("Hello world"))
    }

    @Test
    fun `removeFiller returns empty for only fillers`() {
        assertEquals("", processor.removeFiller("えーと"))
        assertEquals("", processor.removeFiller("um"))
        assertEquals("", processor.removeFiller("えーとあのー"))
    }

    @Test
    fun `removeFiller handles empty input`() {
        assertEquals("", processor.removeFiller(""))
    }

    @Test
    fun `removeFiller handles whitespace-only input`() {
        assertEquals("", processor.removeFiller("   "))
    }

    // ══════════════════════════════════════════════════════
    //  4. Self-correction detection — Japanese
    // ══════════════════════════════════════════════════════

    @Test
    fun `resolveCorrections keeps final intent for janakute`() {
        assertEquals("水曜日に会議", processor.resolveCorrections("火曜日じゃなくて水曜日に会議"))
    }

    @Test
    fun `resolveCorrections keeps final intent for dewanaku`() {
        assertEquals("水曜日", processor.resolveCorrections("火曜日ではなく水曜日"))
        assertEquals("水曜日", processor.resolveCorrections("火曜日ではなくて水曜日"))
    }

    @Test
    fun `resolveCorrections keeps final intent for janai`() {
        assertEquals("水曜日", processor.resolveCorrections("火曜日じゃない水曜日"))
    }

    @Test
    fun `resolveCorrections keeps final intent for chigau`() {
        assertEquals("3時に集合", processor.resolveCorrections("2時に違う3時に集合"))
    }

    @Test
    fun `resolveCorrections keeps final intent for chigau hiragana`() {
        assertEquals("3時です", processor.resolveCorrections("2時ちがう3時です"))
    }

    @Test
    fun `resolveCorrections keeps final intent for machigaeta`() {
        assertEquals("東京です", processor.resolveCorrections("大阪間違えた東京です"))
    }

    @Test
    fun `resolveCorrections keeps final intent for teisei`() {
        assertEquals("水曜日", processor.resolveCorrections("火曜日訂正水曜日"))
    }

    @Test
    fun `resolveCorrections keeps final intent for motoi`() {
        assertEquals("水曜日", processor.resolveCorrections("火曜日もとい水曜日"))
    }

    @Test
    fun `resolveCorrections keeps final intent for iya with comma`() {
        assertEquals("水曜日", processor.resolveCorrections("火曜日いや、水曜日"))
    }

    @Test
    fun `resolveCorrections handles multiple chained corrections`() {
        // "A じゃなくて B じゃなくて C" → C
        assertEquals("木曜日", processor.resolveCorrections("火曜日じゃなくて水曜日じゃなくて木曜日"))
    }

    // ══════════════════════════════════════════════════════
    //  5. Self-correction detection — English
    // ══════════════════════════════════════════════════════

    @Test
    fun `resolveCorrections handles English no wait`() {
        assertEquals("Wednesday", processor.resolveCorrections("Tuesday no wait Wednesday"))
        assertEquals("Wednesday", processor.resolveCorrections("Tuesday no wait, Wednesday"))
    }

    @Test
    fun `resolveCorrections handles English I mean`() {
        assertEquals("Wednesday", processor.resolveCorrections("Tuesday I mean Wednesday"))
        assertEquals("three", processor.resolveCorrections("two I mean, three"))
    }

    @Test
    fun `resolveCorrections handles English actually`() {
        assertEquals("Thursday", processor.resolveCorrections("Wednesday actually Thursday"))
    }

    @Test
    fun `resolveCorrections handles English sorry`() {
        assertEquals("five", processor.resolveCorrections("four sorry five"))
        assertEquals("five", processor.resolveCorrections("four sorry, five"))
    }

    @Test
    fun `resolveCorrections handles English correction`() {
        assertEquals("Wednesday", processor.resolveCorrections("Tuesday correction Wednesday"))
        assertEquals("Wednesday", processor.resolveCorrections("Tuesday correction: Wednesday"))
    }

    @Test
    fun `resolveCorrections handles English rather`() {
        assertEquals("blue", processor.resolveCorrections("red rather blue"))
    }

    @Test
    fun `resolveCorrections handles English let me rephrase`() {
        assertEquals("it's good", processor.resolveCorrections("it's bad let me rephrase it's good"))
    }

    @Test
    fun `resolveCorrections handles multiple English corrections`() {
        assertEquals("five", processor.resolveCorrections("three no wait four no wait five"))
    }

    @Test
    fun `resolveCorrections preserves text without corrections`() {
        assertEquals("普通の文章です", processor.resolveCorrections("普通の文章です"))
        assertEquals("just a normal sentence", processor.resolveCorrections("just a normal sentence"))
    }

    // ══════════════════════════════════════════════════════
    //  6. Auto punctuation — terminal
    // ══════════════════════════════════════════════════════

    @Test
    fun `insertPunctuation adds maru for Japanese sentence`() {
        assertEquals("今日は天気がいいです。", processor.insertPunctuation("今日は天気がいいです"))
        assertEquals("行きました。", processor.insertPunctuation("行きました"))
    }

    @Test
    fun `insertPunctuation adds question mark for Japanese questions`() {
        assertEquals("今日は何曜日ですか？", processor.insertPunctuation("今日は何曜日ですか"))
        assertEquals("行きますか？", processor.insertPunctuation("行きますか"))
        assertEquals("大丈夫かな？", processor.insertPunctuation("大丈夫かな"))
        assertEquals("どうでしょうか？", processor.insertPunctuation("どうでしょうか"))
    }

    @Test
    fun `insertPunctuation adds period for English sentence`() {
        assertEquals("I went home.", processor.insertPunctuation("I went home"))
        assertEquals("That sounds good.", processor.insertPunctuation("That sounds good"))
    }

    @Test
    fun `insertPunctuation adds question mark for English questions`() {
        assertEquals("What time is it?", processor.insertPunctuation("What time is it"))
        assertEquals("How are you?", processor.insertPunctuation("How are you"))
        assertEquals("Is that correct?", processor.insertPunctuation("Is that correct"))
        assertEquals("Can you help?", processor.insertPunctuation("Can you help"))
        assertEquals("Do you agree?", processor.insertPunctuation("Do you agree"))
        assertEquals("Would that work?", processor.insertPunctuation("Would that work"))
    }

    @Test
    fun `insertPunctuation preserves existing punctuation`() {
        assertEquals("Hello!", processor.insertPunctuation("Hello!"))
        assertEquals("Really?", processor.insertPunctuation("Really?"))
        assertEquals("行きます。", processor.insertPunctuation("行きます。"))
        assertEquals("本当？", processor.insertPunctuation("本当？"))
    }

    @Test
    fun `insertPunctuation returns blank text as-is`() {
        assertEquals("", processor.insertPunctuation(""))
        assertEquals("   ", processor.insertPunctuation("   "))
    }

    @Test
    fun `insertPunctuation handles single-char か with preceding hiragana`() {
        assertEquals("行くか？", processor.insertPunctuation("行くか"))
    }

    @Test
    fun `insertPunctuation does not treat か as question in non-Japanese context`() {
        assertEquals("か。", processor.insertPunctuation("か"))
    }

    // ══════════════════════════════════════════════════════
    //  7. Clause commas
    // ══════════════════════════════════════════════════════

    @Test
    fun `insertClauseCommas adds comma after te-form`() {
        val result = processor.insertClauseCommas("食べて寝る")
        assertEquals("食べて、寝る", result)
    }

    @Test
    fun `insertClauseCommas adds comma after de-form`() {
        val result = processor.insertClauseCommas("飲んで帰る")
        assertEquals("飲んで、帰る", result)
    }

    @Test
    fun `insertClauseCommas adds comma after kara`() {
        val result = processor.insertClauseCommas("帰るから待って")
        // Check it contains comma after から
        assertTrue(result.contains("から、"))
    }

    @Test
    fun `insertClauseCommas adds comma after kedo`() {
        val result = processor.insertClauseCommas("高いけど買う")
        assertEquals("高いけど、買う", result)
    }

    @Test
    fun `insertClauseCommas does not modify English text`() {
        val result = processor.insertClauseCommas("I went home and slept")
        assertEquals("I went home and slept", result)
    }

    @Test
    fun `insertClauseCommas does not modify text without boundaries`() {
        val result = processor.insertClauseCommas("今日は天気がいい")
        assertEquals("今日は天気がいい", result)
    }

    // ══════════════════════════════════════════════════════
    //  8. Voice command detection — exact
    // ══════════════════════════════════════════════════════

    @Test
    fun `detectVoiceCommand recognizes Japanese newline`() {
        assertEquals(VoiceCommand.NewLine, processor.detectVoiceCommand("改行"))
        assertEquals(VoiceCommand.NewLine, processor.detectVoiceCommand("かいぎょう"))
    }

    @Test
    fun `detectVoiceCommand recognizes English newline`() {
        assertEquals(VoiceCommand.NewLine, processor.detectVoiceCommand("new line"))
        assertEquals(VoiceCommand.NewLine, processor.detectVoiceCommand("New Line"))
        assertEquals(VoiceCommand.NewLine, processor.detectVoiceCommand("enter"))
        assertEquals(VoiceCommand.NewLine, processor.detectVoiceCommand("Enter"))
    }

    @Test
    fun `detectVoiceCommand recognizes period`() {
        assertEquals(VoiceCommand.Period, processor.detectVoiceCommand("句点"))
        assertEquals(VoiceCommand.Period, processor.detectVoiceCommand("まる"))
        assertEquals(VoiceCommand.Period, processor.detectVoiceCommand("period"))
        assertEquals(VoiceCommand.Period, processor.detectVoiceCommand("dot"))
        assertEquals(VoiceCommand.Period, processor.detectVoiceCommand("DOT"))
    }

    @Test
    fun `detectVoiceCommand recognizes comma`() {
        assertEquals(VoiceCommand.Comma, processor.detectVoiceCommand("読点"))
        assertEquals(VoiceCommand.Comma, processor.detectVoiceCommand("てん"))
        assertEquals(VoiceCommand.Comma, processor.detectVoiceCommand("comma"))
        assertEquals(VoiceCommand.Comma, processor.detectVoiceCommand("Comma"))
    }

    @Test
    fun `detectVoiceCommand recognizes undo`() {
        assertEquals(VoiceCommand.Undo, processor.detectVoiceCommand("消して"))
        assertEquals(VoiceCommand.Undo, processor.detectVoiceCommand("取り消し"))
        assertEquals(VoiceCommand.Undo, processor.detectVoiceCommand("undo"))
        assertEquals(VoiceCommand.Undo, processor.detectVoiceCommand("delete"))
        assertEquals(VoiceCommand.Undo, processor.detectVoiceCommand("UNDO"))
    }

    @Test
    fun `detectVoiceCommand recognizes commit`() {
        assertEquals(VoiceCommand.Commit, processor.detectVoiceCommand("確定"))
        assertEquals(VoiceCommand.Commit, processor.detectVoiceCommand("送信"))
        assertEquals(VoiceCommand.Commit, processor.detectVoiceCommand("done"))
        assertEquals(VoiceCommand.Commit, processor.detectVoiceCommand("commit"))
        assertEquals(VoiceCommand.Commit, processor.detectVoiceCommand("send"))
        assertEquals(VoiceCommand.Commit, processor.detectVoiceCommand("DONE"))
    }

    @Test
    fun `detectVoiceCommand recognizes clear all`() {
        assertEquals(VoiceCommand.ClearAll, processor.detectVoiceCommand("全部消して"))
        assertEquals(VoiceCommand.ClearAll, processor.detectVoiceCommand("ぜんぶけして"))
        assertEquals(VoiceCommand.ClearAll, processor.detectVoiceCommand("clear all"))
        assertEquals(VoiceCommand.ClearAll, processor.detectVoiceCommand("Clear All"))
    }

    @Test
    fun `detectVoiceCommand recognizes space`() {
        assertEquals(VoiceCommand.Space, processor.detectVoiceCommand("スペース"))
        assertEquals(VoiceCommand.Space, processor.detectVoiceCommand("space"))
        assertEquals(VoiceCommand.Space, processor.detectVoiceCommand("SPACE"))
    }

    @Test
    fun `detectVoiceCommand recognizes parentheses`() {
        assertEquals(VoiceCommand.OpenParen, processor.detectVoiceCommand("かっこ"))
        assertEquals(VoiceCommand.OpenParen, processor.detectVoiceCommand("括弧"))
        assertEquals(VoiceCommand.OpenParen, processor.detectVoiceCommand("parenthesis"))
        assertEquals(VoiceCommand.CloseParen, processor.detectVoiceCommand("かっことじ"))
        assertEquals(VoiceCommand.CloseParen, processor.detectVoiceCommand("括弧閉じ"))
        assertEquals(VoiceCommand.CloseParen, processor.detectVoiceCommand("close paren"))
        assertEquals(VoiceCommand.CloseParen, processor.detectVoiceCommand("close parenthesis"))
    }

    @Test
    fun `detectVoiceCommand returns null for normal text`() {
        assertNull(processor.detectVoiceCommand("今日は天気がいい"))
        assertNull(processor.detectVoiceCommand("Hello world"))
    }

    @Test
    fun `detectVoiceCommand handles surrounding whitespace`() {
        assertEquals(VoiceCommand.NewLine, processor.detectVoiceCommand("  改行  "))
        assertEquals(VoiceCommand.Undo, processor.detectVoiceCommand("  undo  "))
    }

    // ══════════════════════════════════════════════════════
    //  9. Trailing command detection
    // ══════════════════════════════════════════════════════

    @Test
    fun `detectTrailingCommand finds command at end of Japanese text`() {
        val result = processor.detectTrailingCommand("テスト文章改行")
        assertNotNull(result)
        assertEquals("テスト文章", result!!.first)
        assertEquals(VoiceCommand.NewLine, result.second)
    }

    @Test
    fun `detectTrailingCommand finds command at end with space`() {
        val result = processor.detectTrailingCommand("テスト文章 確定")
        assertNotNull(result)
        assertEquals("テスト文章", result!!.first)
        assertEquals(VoiceCommand.Commit, result.second)
    }

    @Test
    fun `detectTrailingCommand finds English command at end`() {
        val result = processor.detectTrailingCommand("Hello world done")
        assertNotNull(result)
        assertEquals("Hello world", result!!.first)
        assertEquals(VoiceCommand.Commit, result.second)
    }

    @Test
    fun `detectTrailingCommand finds send command`() {
        val result = processor.detectTrailingCommand("hello send")
        assertNotNull(result)
        assertEquals("hello", result!!.first)
        assertEquals(VoiceCommand.Commit, result.second)
    }

    @Test
    fun `detectTrailingCommand finds clear all`() {
        val result = processor.detectTrailingCommand("テスト 全部消して")
        assertNotNull(result)
        assertEquals(VoiceCommand.ClearAll, result!!.second)
    }

    @Test
    fun `detectTrailingCommand finds space command`() {
        val result = processor.detectTrailingCommand("テストスペース")
        assertNotNull(result)
        assertEquals(VoiceCommand.Space, result!!.second)
    }

    @Test
    fun `detectTrailingCommand finds close paren before open paren`() {
        // かっことじ should be matched before かっこ
        val result = processor.detectTrailingCommand("テストかっことじ")
        assertNotNull(result)
        assertEquals(VoiceCommand.CloseParen, result!!.second)
    }

    @Test
    fun `detectTrailingCommand finds open paren`() {
        val result = processor.detectTrailingCommand("テストかっこ")
        assertNotNull(result)
        assertEquals(VoiceCommand.OpenParen, result!!.second)
    }

    @Test
    fun `detectTrailingCommand returns null when no command at end`() {
        assertNull(processor.detectTrailingCommand("普通のテキスト"))
        assertNull(processor.detectTrailingCommand("Hello world"))
    }

    @Test
    fun `detectTrailingCommand returns null when only command (no remaining text)`() {
        assertNull(processor.detectTrailingCommand("改行"))
    }

    // ══════════════════════════════════════════════════════
    //  10. Hallucination detection
    // ══════════════════════════════════════════════════════

    @Test
    fun `isHallucination detects Japanese hallucinations`() {
        assertTrue(processor.isHallucination("ご視聴ありがとうございました"))
        assertTrue(processor.isHallucination("ご視聴ありがとうございます"))
        assertTrue(processor.isHallucination("ご清聴ありがとうございました"))
        assertTrue(processor.isHallucination("チャンネル登録お願いします"))
        assertTrue(processor.isHallucination("チャンネル登録よろしくお願いします"))
    }

    @Test
    fun `isHallucination detects English hallucinations`() {
        assertTrue(processor.isHallucination("Thanks for watching"))
        assertTrue(processor.isHallucination("thank you for watching"))
        assertTrue(processor.isHallucination("Subscribe to my channel"))
        assertTrue(processor.isHallucination("Please subscribe"))
        assertTrue(processor.isHallucination("Please like and subscribe"))
    }

    @Test
    fun `isHallucination detects hallucinations with trailing punctuation`() {
        assertTrue(processor.isHallucination("ご視聴ありがとうございました。"))
        assertTrue(processor.isHallucination("Thanks for watching!"))
        assertTrue(processor.isHallucination("チャンネル登録お願いします！"))
    }

    @Test
    fun `isHallucination detects repetition patterns`() {
        assertTrue(processor.isHallucination("あああああああああああああああああ"))  // single char repeated
        assertTrue(processor.isHallucination("テストテストテストテスト"))  // 3+ repeats of 4+ chars — actually this is 2-char テスト, let me use longer
    }

    @Test
    fun `isHallucination returns false for normal text`() {
        assertFalse(processor.isHallucination("今日は天気がいい"))
        assertFalse(processor.isHallucination("Hello world"))
        assertFalse(processor.isHallucination(""))
    }

    @Test
    fun `isHallucination returns false for short repeated content`() {
        // Short repeated content that's not a hallucination
        assertFalse(processor.isHallucination("はいはい"))
    }

    // ══════════════════════════════════════════════════════
    //  11. Number normalization — kanjiToNumber
    // ══════════════════════════════════════════════════════

    @Test
    fun `kanjiToNumber converts single digits`() {
        assertEquals(0L, processor.kanjiToNumber("零"))
        assertEquals(0L, processor.kanjiToNumber("〇"))
        assertEquals(1L, processor.kanjiToNumber("一"))
        assertEquals(5L, processor.kanjiToNumber("五"))
        assertEquals(9L, processor.kanjiToNumber("九"))
    }

    @Test
    fun `kanjiToNumber converts tens`() {
        assertEquals(10L, processor.kanjiToNumber("十"))
        assertEquals(11L, processor.kanjiToNumber("十一"))
        assertEquals(20L, processor.kanjiToNumber("二十"))
        assertEquals(25L, processor.kanjiToNumber("二十五"))
        assertEquals(99L, processor.kanjiToNumber("九十九"))
    }

    @Test
    fun `kanjiToNumber converts hundreds`() {
        assertEquals(100L, processor.kanjiToNumber("百"))
        assertEquals(300L, processor.kanjiToNumber("三百"))
        assertEquals(350L, processor.kanjiToNumber("三百五十"))
        assertEquals(105L, processor.kanjiToNumber("百五"))
    }

    @Test
    fun `kanjiToNumber converts thousands`() {
        assertEquals(1000L, processor.kanjiToNumber("千"))
        assertEquals(2000L, processor.kanjiToNumber("二千"))
        assertEquals(2026L, processor.kanjiToNumber("二千二十六"))
        assertEquals(3500L, processor.kanjiToNumber("三千五百"))
    }

    @Test
    fun `kanjiToNumber converts large numbers with 万`() {
        assertEquals(10000L, processor.kanjiToNumber("一万"))
        assertEquals(30000L, processor.kanjiToNumber("三万"))
        assertEquals(15000L, processor.kanjiToNumber("一万五千"))
        assertEquals(12345L, processor.kanjiToNumber("一万二千三百四十五"))
    }

    @Test
    fun `kanjiToNumber converts positional notation`() {
        assertEquals(2026L, processor.kanjiToNumber("二〇二六"))
        assertEquals(102L, processor.kanjiToNumber("一〇二"))
    }

    @Test
    fun `kanjiToNumber returns null for empty string`() {
        assertNull(processor.kanjiToNumber(""))
    }

    @Test
    fun `kanjiToNumber returns null for non-number characters`() {
        assertNull(processor.kanjiToNumber("あいう"))
    }

    // ══════════════════════════════════════════════════════
    //  12. Date normalization
    // ══════════════════════════════════════════════════════

    @Test
    fun `normalizeDates converts kanji dates`() {
        assertEquals("3月31日", processor.normalizeDates("三月三十一日"))
        assertEquals("1月1日", processor.normalizeDates("一月一日"))
        assertEquals("12月25日", processor.normalizeDates("十二月二十五日"))
    }

    @Test
    fun `normalizeDates preserves Arabic dates`() {
        assertEquals("3月31日", processor.normalizeDates("3月31日"))
    }

    @Test
    fun `normalizeDates handles dates in context`() {
        assertEquals("会議は3月31日です", processor.normalizeDates("会議は三月三十一日です"))
    }

    // ══════════════════════════════════════════════════════
    //  13. Counter normalization
    // ══════════════════════════════════════════════════════

    @Test
    fun `normalizeCounters converts kanji counters`() {
        assertEquals("3つ", processor.normalizeCounters("三つ"))
        assertEquals("5個", processor.normalizeCounters("五個"))
        assertEquals("10人", processor.normalizeCounters("十人"))
        assertEquals("100円", processor.normalizeCounters("百円"))
    }

    @Test
    fun `normalizeCounters handles counters in context`() {
        assertEquals("りんごを3個ください", processor.normalizeCounters("りんごを三個ください"))
    }

    @Test
    fun `normalizeCounters converts various counter words`() {
        assertEquals("2本", processor.normalizeCounters("二本"))
        assertEquals("3枚", processor.normalizeCounters("三枚"))
        assertEquals("1台", processor.normalizeCounters("一台"))
        assertEquals("5冊", processor.normalizeCounters("五冊"))
        assertEquals("3回", processor.normalizeCounters("三回"))
        assertEquals("25歳", processor.normalizeCounters("二十五歳"))
    }

    // ══════════════════════════════════════════════════════
    //  14. Standalone number normalization
    // ══════════════════════════════════════════════════════

    @Test
    fun `normalizeStandaloneNumbers converts compound kanji numbers`() {
        assertEquals("2026", processor.normalizeStandaloneNumbers("二千二十六"))
    }

    @Test
    fun `normalizeStandaloneNumbers does not convert single kanji digits`() {
        // Single chars are too ambiguous (一 could mean "one" or be part of a word)
        assertEquals("一", processor.normalizeStandaloneNumbers("一"))
    }

    @Test
    fun `normalizeStandaloneNumbers does not interfere with counters`() {
        // Numbers followed by counter words are handled by normalizeCounters
        // normalizeStandaloneNumbers should leave them alone
        assertEquals("三つ", processor.normalizeStandaloneNumbers("三つ"))
        assertEquals("五個", processor.normalizeStandaloneNumbers("五個"))
    }

    // ══════════════════════════════════════════════════════
    //  15. Text normalization
    // ══════════════════════════════════════════════════════

    @Test
    fun `normalizeText collapses multiple spaces`() {
        assertEquals("hello world", processor.normalizeText("hello   world"))
        assertEquals("a b c", processor.normalizeText("a  b  c"))
    }

    @Test
    fun `normalizeText trims whitespace`() {
        assertEquals("hello", processor.normalizeText("  hello  "))
    }

    @Test
    fun `normalizeText converts fullwidth digits`() {
        assertEquals("123", processor.normalizeText("\uFF11\uFF12\uFF13"))
    }

    @Test
    fun `normalizeText converts fullwidth letters`() {
        assertEquals("ABC", processor.normalizeText("\uFF21\uFF22\uFF23"))
        assertEquals("abc", processor.normalizeText("\uFF41\uFF42\uFF43"))
    }

    @Test
    fun `normalizeText preserves Japanese characters`() {
        assertEquals("こんにちは", processor.normalizeText("こんにちは"))
        assertEquals("テスト", processor.normalizeText("テスト"))
    }

    @Test
    fun `normalizeText handles mixed content`() {
        assertEquals("test123テスト", processor.normalizeText("test\uFF11\uFF12\uFF13テスト"))
    }

    // ══════════════════════════════════════════════════════
    //  16. Full pipeline
    // ══════════════════════════════════════════════════════

    @Test
    fun `process runs full pipeline on normal text`() {
        val result = processor.process("えーと今日は天気がいいです")
        assertEquals("今日は天気がいいです。", result.text)
        assertNull(result.command)
    }

    @Test
    fun `process handles exact voice command`() {
        val result = processor.process("改行")
        assertEquals("", result.text)
        assertEquals(VoiceCommand.NewLine, result.command)
    }

    @Test
    fun `process handles trailing voice command`() {
        val result = processor.process("テスト文章改行")
        assertTrue(result.text.isNotBlank())
        assertEquals(VoiceCommand.NewLine, result.command)
    }

    @Test
    fun `process handles filler + correction combined`() {
        val result = processor.process("えーと火曜日じゃなくて水曜日")
        assertEquals("水曜日。", result.text)
        assertNull(result.command)
    }

    @Test
    fun `process handles empty text`() {
        val result = processor.process("")
        assertEquals("", result.text)
        assertNull(result.command)
    }

    @Test
    fun `process handles blank text`() {
        val result = processor.process("   ")
        assertEquals("", result.text)
        assertNull(result.command)
    }

    @Test
    fun `process handles text that becomes empty after filler removal`() {
        val result = processor.process("えーと")
        assertEquals("", result.text)
        assertNull(result.command)
    }

    @Test
    fun `process handles English pipeline`() {
        val result = processor.process("um I think so")
        assertEquals("I think so.", result.text)
        assertNull(result.command)
    }

    @Test
    fun `process handles English question with filler`() {
        val result = processor.process("um What time is it")
        assertEquals("What time is it?", result.text)
        assertNull(result.command)
    }

    @Test
    fun `process handles correction then punctuation`() {
        val result = processor.process("Tuesday no wait Wednesday")
        assertEquals("Wednesday.", result.text)
        assertNull(result.command)
    }

    @Test
    fun `process filters hallucinations`() {
        val result = processor.process("ご視聴ありがとうございました")
        assertEquals("", result.text)
        assertNull(result.command)
    }

    @Test
    fun `process filters hallucinations with punctuation`() {
        val result = processor.process("Thanks for watching!")
        assertEquals("", result.text)
        assertNull(result.command)
    }

    @Test
    fun `process normalizes dates in pipeline`() {
        val result = processor.process("会議は三月三十一日です")
        assertEquals("会議は3月31日です。", result.text)
    }

    @Test
    fun `process normalizes counters in pipeline`() {
        val result = processor.process("りんごを三個ください")
        // The text goes through normalization
        assertTrue(result.text.contains("3個"))
    }

    @Test
    fun `process normalizes fullwidth in pipeline`() {
        val result = processor.process("\uFF21\uFF22\uFF23 test")
        assertEquals("ABC test.", result.text)
    }

    @Test
    fun `process handles new command types`() {
        assertEquals(VoiceCommand.Comma, processor.process("てん").command)
        assertEquals(VoiceCommand.ClearAll, processor.process("全部消して").command)
        assertEquals(VoiceCommand.Space, processor.process("スペース").command)
        assertEquals(VoiceCommand.OpenParen, processor.process("かっこ").command)
        assertEquals(VoiceCommand.CloseParen, processor.process("かっことじ").command)
    }

    @Test
    fun `process handles case-insensitive English commands`() {
        assertEquals(VoiceCommand.NewLine, processor.process("ENTER").command)
        assertEquals(VoiceCommand.ClearAll, processor.process("CLEAR ALL").command)
        assertEquals(VoiceCommand.Commit, processor.process("SEND").command)
    }

    @Test
    fun `process handles only fillers with trailing command`() {
        // "えーと 改行" — filler + command. After filler removal, text is empty.
        val result = processor.process("えーと改行")
        // The trailing command detection happens first, then filler removal on remainder
        // "えーと改行" → trailing: "えーと" + NewLine → removeFiller("えーと") → ""
        assertEquals("", result.text)
        assertEquals(VoiceCommand.NewLine, result.command)
    }

    @Test
    fun `process handles complex mixed scenario`() {
        // Filler + correction + number + punctuation
        val result = processor.process("えーと火曜日じゃなくて三月三十一日です")
        assertEquals("3月31日です。", result.text)
        assertNull(result.command)
    }

    @Test
    fun `process collapses multiple spaces in output`() {
        val result = processor.process("hello    world")
        assertEquals("hello world.", result.text)
    }

    // ══════════════════════════════════════════════════════
    //  17. Context-aware filler removal (Task 22)
    // ══════════════════════════════════════════════════════

    @Test
    fun `removeFiller removes ano as filler when not followed by noun`() {
        assertEquals("明日会議があります", processor.removeFiller("あの明日会議があります"))
        assertEquals("行きます", processor.removeFiller("あのー行きます"))
    }

    @Test
    fun `removeFiller keeps ano when followed by demonstrative noun hito`() {
        assertEquals("あの人が来た", processor.removeFiller("あの人が来た"))
    }

    @Test
    fun `removeFiller keeps ano when followed by demonstrative noun toki`() {
        assertEquals("あの時のことを話す", processor.removeFiller("あの時のことを話す"))
    }

    @Test
    fun `removeFiller keeps ano when followed by demonstrative noun hi`() {
        assertEquals("あの日のことです", processor.removeFiller("あの日のことです"))
    }

    @Test
    fun `removeFiller keeps ano when followed by kata`() {
        assertEquals("あの方はどなた", processor.removeFiller("あの方はどなた"))
    }

    @Test
    fun `removeFiller removes sono as filler when not followed by noun`() {
        assertEquals("質問です", processor.removeFiller("そのー質問です"))
    }

    @Test
    fun `removeFiller keeps sono when followed by demonstrative noun`() {
        assertEquals("その件について", processor.removeFiller("その件について"))
        assertEquals("その話は知ってる", processor.removeFiller("その話は知ってる"))
    }

    @Test
    fun `removeFiller keeps ano elongated when followed by noun`() {
        // あのー (elongated) + noun → demonstrative, keep
        assertEquals("あのー人はだれ", processor.removeFiller("あのー人はだれ"))
    }

    // ══════════════════════════════════════════════════════
    //  18. User-specific filler auto-learning (Task 22b)
    // ══════════════════════════════════════════════════════

    @Test
    fun `recordFillerDeletion increments count`() {
        val p = PostProcessor()
        assertEquals(0, p.getFillerDeletionCount("まじで"))
        p.recordFillerDeletion("まじで")
        p.recordFillerDeletion("まじで")
        assertEquals(2, p.getFillerDeletionCount("まじで"))
    }

    @Test
    fun `removeFiller does not remove word below threshold`() {
        val p = PostProcessor()
        repeat(4) { p.recordFillerDeletion("要するに") }
        assertEquals("要するに大事なのは", p.removeFiller("要するに大事なのは"))
    }

    @Test
    fun `removeFiller removes user-learned filler at threshold`() {
        val p = PostProcessor()
        repeat(5) { p.recordFillerDeletion("要するに") }
        assertEquals("大事なのは", p.removeFiller("要するに大事なのは"))
    }

    @Test
    fun `removeFiller removes user-learned filler above threshold`() {
        val p = PostProcessor()
        repeat(7) { p.recordFillerDeletion("なんていうか") }
        assertEquals("そういうことです", p.removeFiller("なんていうかそういうことです"))
    }

    @Test
    fun `recordFillerDeletion works for multiple different words`() {
        val p = PostProcessor()
        repeat(5) { p.recordFillerDeletion("word1") }
        repeat(3) { p.recordFillerDeletion("word2") }
        assertEquals("text", p.removeFiller("word1 text"))
        // word2 is below threshold — should remain
        assertEquals("word2 text", p.removeFiller("word2 text"))
    }

    // ══════════════════════════════════════════════════════
    //  19. Rephrase detection (Task 22c)
    // ══════════════════════════════════════════════════════

    @Test
    fun `resolveRephrases removes earlier phrase when suffix overlaps`() {
        // "明日行きます" and "明後日行きます" share suffix "行きます" (5 chars) with different prefix
        val result = processor.resolveRephrases("明日行きます明後日行きます")
        assertEquals("明後日行きます", result)
    }

    @Test
    fun `resolveRephrases removes earlier phrase for two-char suffix overlap`() {
        // "火曜です" and "水曜です" share suffix "曜です" — keep the later one
        val result = processor.resolveRephrases("火曜です水曜です")
        assertEquals("水曜です", result)
    }

    @Test
    fun `resolveRephrases preserves text with no rephrase`() {
        val result = processor.resolveRephrases("今日は天気がいい")
        assertEquals("今日は天気がいい", result)
    }

    @Test
    fun `resolveRephrases handles near-identical phrases edit distance 1`() {
        // "行きます" vs "行います" — one char different (き vs い), distance 1
        val result = processor.resolveRephrases("行きます行います")
        assertEquals("行います", result)
    }

    @Test
    fun `isRephrase returns true for suffix overlap`() {
        assertTrue(processor.isRephrase("明日行きます", "明後日行きます"))
    }

    @Test
    fun `isRephrase returns true for near-identical strings`() {
        // "行きます" (6 chars) vs "行います" (6 chars) — distance 1
        assertTrue(processor.isRephrase("行きます", "行います"))
    }

    @Test
    fun `isRephrase returns false for unrelated phrases`() {
        assertFalse(processor.isRephrase("今日は天気がいい", "明日は雨です"))
    }

    @Test
    fun `isRephrase returns false for short phrases`() {
        assertFalse(processor.isRephrase("は", "が"))
    }

    @Test
    fun `resolveCorrections applies rephrase detection after explicit corrections`() {
        // After explicit correction, rephrase detection should still work on remainder
        val result = processor.resolveCorrections("火曜日行きます水曜日行きます")
        // suffix "行きます" shared → keep second
        assertEquals("水曜日行きます", result)
    }

    // ══════════════════════════════════════════════════════
    //  Task 25 — Time normalization
    // ══════════════════════════════════════════════════════

    @Test
    fun `normalizeTimes converts standard time`() {
        assertEquals("14:30", processor.normalizeTimes("十四時三十分"))
    }

    @Test
    fun `normalizeTimes zero-pads minutes`() {
        assertEquals("9:05", processor.normalizeTimes("九時五分"))
    }

    @Test
    fun `normalizeTimes handles midnight and noon`() {
        assertEquals("0:00", processor.normalizeTimes("〇時〇〇分"))
        assertEquals("12:00", processor.normalizeTimes("十二時〇〇分"))
    }

    @Test
    fun `normalizeTimes normalizes within sentence`() {
        assertEquals("会議は14:30から始まります。", processor.normalizeTimes("会議は十四時三十分から始まります。"))
    }

    @Test
    fun `normalizeTimes leaves non-time kanji untouched`() {
        assertEquals("三時間かかる", processor.normalizeTimes("三時間かかる"))
    }

    // ══════════════════════════════════════════════════════
    //  Task 25 — Money comma formatting
    // ══════════════════════════════════════════════════════

    @Test
    fun `formatMoneyCommas adds commas to yen amounts`() {
        assertEquals("2,500円", processor.formatMoneyCommas("2500円"))
        assertEquals("1,000,000円", processor.formatMoneyCommas("1000000円"))
        assertEquals("10,000円", processor.formatMoneyCommas("10000円"))
    }

    @Test
    fun `formatMoneyCommas adds commas to dollar amounts`() {
        assertEquals("1,000,000ドル", processor.formatMoneyCommas("1000000ドル"))
        assertEquals("1,500ドル", processor.formatMoneyCommas("1500ドル"))
    }

    @Test
    fun `formatMoneyCommas leaves amounts under 1000 unchanged`() {
        assertEquals("500円", processor.formatMoneyCommas("500円"))
        assertEquals("999ドル", processor.formatMoneyCommas("999ドル"))
    }

    @Test
    fun `formatMoneyCommas handles amounts in sentence`() {
        assertEquals("合計2,500円です。", processor.formatMoneyCommas("合計2500円です。"))
    }

    // ══════════════════════════════════════════════════════
    //  Task 27 — Formality tracker
    // ══════════════════════════════════════════════════════

    @Test
    fun `formalityRatio defaults to 0_5 with no data`() {
        processor.resetFormalityTracker()
        assertEquals(0.5f, processor.formalityRatio, 0.001f)
    }

    @Test
    fun `formalityRatio increases toward 1 for formal sentences`() {
        processor.resetFormalityTracker()
        processor.process("今日は天気がいいです。")
        processor.process("明日も晴れます。")
        assertTrue(processor.formalityRatio > 0.5f)
    }

    @Test
    fun `formalityRatio decreases toward 0 for casual sentences`() {
        processor.resetFormalityTracker()
        processor.process("今日は天気がいいだ。")
        processor.process("それはそうだった。")
        assertTrue(processor.formalityRatio < 0.5f)
    }

    @Test
    fun `formalityRatio stays bounded between 0 and 1`() {
        processor.resetFormalityTracker()
        repeat(20) { processor.process("です。") }
        assertTrue(processor.formalityRatio in 0f..1f)
    }
}
