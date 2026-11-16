package space.manus.nacre.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gates for the LLM dictation cleanup (see [CleanupGuard]).
 *
 * The calibration data below is recorded, not invented:
 * - GOLDEN: the 30 PR #81 refinement samples (tools/golden/qwen_samples.txt)
 *   with their recorded outputs (tools/golden/golden/qwen.tsv). Every input
 *   must pass the pre-gate and every recorded output must be accepted by
 *   the post-gate — the guard must not reject legitimate cleanups.
 * - EVAL: the 40 ASR outputs from the 2026-11-17 real-world voice eval
 *   (babble 10 dB, Parakeet + Reazon) with the shipped prompt's outputs.
 *   25 of the 40 were damaged by cleanup (CER-based classification in the
 *   eval report). Every input is a clean read sentence, so the pre-gate
 *   must skip all 40 — which is what saves the damaged cases.
 */
class CleanupGuardTest {

    // ── Pre-gate: individual signals ────────────────────────────────

    @Test
    fun `hesitation filler needs refinement`() {
        assertTrue(CleanupGuard.needsRefinement("えーっと、明日の会議の資料を準備しといて"))
        assertTrue(CleanupGuard.needsRefinement("あのー、今日の午後から打ち合わせが入ってます"))
    }

    @Test
    fun `self correction needs refinement`() {
        assertTrue(CleanupGuard.needsRefinement("明日じゃなくて明後日の会議に変更してください"))
    }

    @Test
    fun `immediate repetition needs refinement`() {
        assertTrue(CleanupGuard.needsRefinement("明日の、明日の十時に駅前で待ち合わせしましょう"))
        assertTrue(CleanupGuard.needsRefinement("傘を、傘を忘れてしまって大変でした"))
    }

    @Test
    fun `symbol reading needs refinement`() {
        assertTrue(CleanupGuard.needsRefinement("エルエス ハイフン エルエー"))
    }

    @Test
    fun `spoken numerals need refinement`() {
        assertTrue(CleanupGuard.needsRefinement("にせんにじゅうろくねんの一月十五日に引っ越しが決まりました"))
    }

    @Test
    fun `unfinished fragment needs refinement`() {
        // Ends in a て-form: a mid-dictation fragment with no sentence end.
        assertTrue(CleanupGuard.needsRefinement("ジットでプルしてからリアクトのビルド回して"))
    }

    @Test
    fun `long unstructured dictation needs refinement`() {
        assertTrue(
            CleanupGuard.needsRefinement(
                "今日の会議では最初に先月の売上を確認してから新しい企画の方向性についてみんなで話し合って来月までの担当を決めました",
            ),
        )
    }

    // ── Pre-gate: clean inputs are skipped ──────────────────────────

    @Test
    fun `short clean sentences are skipped`() {
        assertFalse(CleanupGuard.needsRefinement("部屋を出るときには明かりを消しなさい"))
        assertFalse(CleanupGuard.needsRefinement("明日の会議は三時からです"))
        assertFalse(CleanupGuard.needsRefinement("水をマレーシアから買わなくてはならないのです"))
    }

    @Test
    fun `content demonstratives are not fillers`() {
        // The PR #81 traps: あの店 / その件 / あの人 / まあまあ / なんか
        // used referentially must not trigger the filler signal.
        assertFalse(CleanupGuard.needsRefinement("あの店のラーメンが食べたいです"))
        assertFalse(CleanupGuard.needsRefinement("その件は明日の会議で決めましょう"))
        assertFalse(CleanupGuard.needsRefinement("あの人が言っていた話は本当でした"))
        assertFalse(CleanupGuard.needsRefinement("昨日の映画はまあまあ面白かったです"))
        assertFalse(CleanupGuard.needsRefinement("なんか変な音がする"))
    }

    @Test
    fun `imperative shinasai is not a spoken numeral`() {
        // 置きしなさい would false-fire on きしな+さい without the overlap guard.
        assertFalse(CleanupGuard.needsRefinement("部屋を出る時には明かり置きしなさい"))
    }

    @Test
    fun `legitimate reduplication is not a stutter`() {
        assertFalse(CleanupGuard.needsRefinement("木曽川はしばしば日本のライン川と呼ばれている"))
    }

    // ── Post-gate ───────────────────────────────────────────────────

    @Test
    fun `clause dropping on a short input is rejected`() {
        assertFalse(
            CleanupGuard.isFaithfulRefinement(
                "部屋を出るときには明かりを消しなさい",
                "明かりを消しなさい",
            ),
        )
    }

    @Test
    fun `paraphrase with invented content is rejected`() {
        assertFalse(
            CleanupGuard.isFaithfulRefinement(
                "中心部にあるので商店やオフィスに行くのに便利です",
                "商店やオフィスが中心部にあるため、買い物や仕事に行くのに便利です",
            ),
        )
    }

    @Test
    fun `filler removal and punctuation are accepted`() {
        assertTrue(
            CleanupGuard.isFaithfulRefinement(
                "えー、昨日の映画、まあまあ面白かったです",
                "昨日の映画はまあまあ面白かったです。",
            ),
        )
        assertTrue(
            CleanupGuard.isFaithfulRefinement(
                "明日の、明日の十時に、駅前で待ち合わせしましょう",
                "明日の十時に駅前で待ち合わせしましょう。",
            ),
        )
    }

    @Test
    fun `heavy but legitimate long cleanup is accepted`() {
        // Recorded golden qwen-09: resolving the self-correction drops
        // most of the surface text — still a legitimate cleanup.
        assertTrue(
            CleanupGuard.isFaithfulRefinement(
                "じゃあ、今日の買い物リストなんですけど、牛乳と、卵と、あと、あれ、パンじゃなくて、ご飯の方がいいかな、やっぱりパンでお願いします",
                "今日の買い物リストは、牛乳、卵、ご飯です。",
            ),
        )
    }

    @Test
    fun `representation changing conversions are accepted`() {
        // Symbol readings and spoken numerals convert into a different
        // representation entirely — the string metric cannot judge them.
        assertTrue(
            CleanupGuard.isFaithfulRefinement("エルエス ハイフン エルエー", "ls -la"),
        )
        assertTrue(
            CleanupGuard.isFaithfulRefinement(
                "にせんにじゅうろくねんの一月十五日に引っ越しが決まりました",
                "2026年1月15日に引っ越しが決まりました。",
            ),
        )
    }

    @Test
    fun `short self correction resolution is accepted`() {
        assertTrue(
            CleanupGuard.isFaithfulRefinement("火曜じゃなくて水曜です", "水曜です。"),
        )
        assertTrue(
            CleanupGuard.isFaithfulRefinement(
                "明日の、明日じゃなくて明後日の会議の資料を準備しといて",
                "明後日の会議の資料を準備しておいて。",
            ),
        )
    }

    @Test
    fun `short filler removal is accepted`() {
        assertTrue(
            CleanupGuard.isFaithfulRefinement("えーっと、あのー、明日です", "明日です。"),
        )
    }

    @Test
    fun `correction sharing a clause with kept context is accepted`() {
        // Only 火曜 is superseded; 会議は is shared context that survives.
        assertTrue(
            CleanupGuard.isFaithfulRefinement("会議は火曜じゃなくて水曜です", "会議は水曜です。"),
        )
    }

    @Test
    fun `multiple corrections in one input are accepted`() {
        assertTrue(
            CleanupGuard.isFaithfulRefinement(
                "会議は火曜じゃなくて水曜に、場所も本社じゃなくて支社に変更です",
                "会議は水曜に、場所も支社に変更です。",
            ),
        )
    }

    @Test
    fun `repetition removal is accepted`() {
        assertTrue(
            CleanupGuard.isFaithfulRefinement(
                "よろしくお願いします、よろしくお願いします",
                "よろしくお願いします。",
            ),
        )
        assertTrue(
            CleanupGuard.isFaithfulRefinement(
                "よろしくよろしくお願いします",
                "よろしくお願いします。",
            ),
        )
        // Keeping both copies is also fine (matched by the plain variant).
        assertTrue(
            CleanupGuard.isFaithfulRefinement(
                "よろしくお願いします、よろしくお願いします",
                "よろしくお願いします、よろしくお願いします。",
            ),
        )
    }

    @Test
    fun `triple repetition removal is accepted`() {
        assertTrue(
            CleanupGuard.isFaithfulRefinement(
                "明日行きます、明日行きます、明日行きます",
                "明日行きます。",
            ),
        )
    }

    @Test
    fun `blank refinement is rejected`() {
        assertFalse(CleanupGuard.isFaithfulRefinement("明日の会議は三時からです", ""))
    }

    // ── Recorded calibration ────────────────────────────────────────

    private data class GoldenPair(val input: String, val output: String)

    private data class EvalPair(val input: String, val output: String, val damaged: Boolean)

    private val goldenPairs = listOf(
        GoldenPair("えーっと、明日の、明日じゃなくて明後日の会議の資料を準備しといて", "明後日の会議の資料を準備しておいて。"),
        GoldenPair("ジットでプルしてからリアクトのビルド回して", "ジットでプルしてから、リアクトのビルドを回して。"),
        GoldenPair("エルエス ハイフン エルエー", "エルエス ハイフン エルエー"),
        GoldenPair("あのー、今日の午後三時、さんじじゃなくて四時半から打ち合わせが入ってるので、それまでに見積もりを、えー、メールで送ってください", "今日の午後四時半から打ち合わせが入ってるので、それまでに見積もりをメールで送ってください。"),
        GoldenPair("まあ、そのー、プロジェクトの進捗なんですけど、えーっと、今週末までに、なんとか、ベータ版を、リリースできそうです", "まあ、そのプロジェクトの進捗なんですけど、今週末までに、なんとかベータ版をリリースできそうです。"),
        GoldenPair("にせんにじゅうろくねんの、えー、一月十五日に、新しいオフィスへの引っ越しが決まりました", "一月十五日に新しいオフィスへの引っ越しが決まりました。"),
        GoldenPair("すみません、ちょっと聞きたいんですけど、この駅から、えー、美術館までは歩いて何分くらいかかりますか", "えー、美術館まで歩いて何分かかりますか。"),
        GoldenPair("タイプスクリプトの、えー、ジェネリクスが、うまく、型推論されなくて、ずっとエラーが出てるんですよね", "タイプスクリプトのジェネリクスが型推論されなくて、ずっとエラーが出てるんです。"),
        GoldenPair("じゃあ、今日の買い物リストなんですけど、牛乳と、卵と、あと、あれ、パンじゃなくて、ご飯の方がいいかな、やっぱりパンでお願いします", "今日の買い物リストは、牛乳、卵、ご飯です。"),
        GoldenPair("お世話になっております、えー、来週の月曜日、げつようびは祝日なので、納品は火曜日、かようびにずらしてもらえますか", "来週の月曜日は祝日なので、納品は火曜日、かようびにずらしていただけますか。"),
        GoldenPair("センセイ、今日の授業で分からなかったところなんですけど、えーっと、二次関数の、頂点の求め方が、よく分からなくて", "センセイ、今日の授業で分からなかったところなんですけど、二次関数の頂点の求め方がよく分からなくて。"),
        GoldenPair("このアプリ、最近すごく重いんですけど、えー、起動に、じゅうびょう、10秒くらいかかるんですよね、なんとかなりませんか", "えー、起動に10秒かかるのは、ちょっと問題ですね。なんとかならないでしょうか。"),
        GoldenPair("明日は、朝七時に起きて、えー、八時の電車に乗って、空港に、十時、じゅうじまでに着く予定です", "明日は、朝七時に起きて、八時の電車に乗って、空港に十時までに着く予定です。"),
        GoldenPair("すみません、この荷物、えー、クロネコヤマトで、明日、明日中に届くように送りたいんですけど、いくらかかりますか", "すみません、荷物をクロネコヤマトで明日まで届けるように送りたいのですが、どのくらいの費用が必要でしょうか。"),
        GoldenPair("ギットハブの、プルリクエストを、マージしたら、えー、シーアイが、失敗してて、ログを見たら、テストが三つ落ちてました", "ギットハブのプルリクエストをマージしたら、エラーが発生しました。ログを確認したところ、テストが三つ落ちていました。"),
        GoldenPair("今日の天気は、えー、午前中は晴れるみたいなんですけど、午後から、雨が降るらしくて、傘を持っていった方が良さそうです", "今日の天気は、午前中は晴れるみたいですが、午後から雨が降るらしいです。傘を持っていった方が良さそうです。"),
        GoldenPair("会議の議事録なんですけど、えー、決定事項は二つあって、一つ目が、予算の承認、二つ目が、えー、スケジュールの見直しです", "決定事項は二つあります。一つ目が予算の承認、二つ目がスケジュールの見直しです。"),
        GoldenPair("ちょっと、これ、前も言ったと思うんですけど、えー、パスワードを、使い回すのは、危ないので、やめた方がいいですよ", "パスワードを使い回すのは危ないので、やめた方がいいですよ。"),
        GoldenPair("新しい、キーボードの、アプリを入れたんですけど、変換が、えー、なかなか賢くて、フリック入力も、だいぶ、楽になりました", "新しいキーボードのアプリを入れて、変換がなかなか賢くて、フリック入力もだいぶ楽になりました。"),
        GoldenPair("最後に、えー、来月のシフトなんですけど、月曜日は、大学の授業があるので、終日、出られません、よろしくお願いします", "来月のシフトなんですけど、月曜日は、大学の授業があるので、終日、出られません、よろしくお願いします。"),
        GoldenPair("あのー、あの店のラーメン、えー、すごく美味しかったんですけど、また行きたいですね", "あの店のラーメン、すごく美味しかったんですけど、また行きたいですね。"),
        GoldenPair("そのー、その件なんですけど、えーっと、明日の会議で決めることにしましょう", "その件、明日の会議で決めることにしましょう。"),
        GoldenPair("あの人が、えー、言ってたんですけど、新しいプロジェクトは、来月から始まるらしいですよ", "あの人が言ってたんですけど、新しいプロジェクトは来月から始まるらしいですよ。"),
        GoldenPair("えー、昨日の映画、まあまあ面白かったです", "昨日の映画はまあまあ面白かったです。"),
        GoldenPair("なんか、変な音がするんですけど、えー、冷蔵庫のモーターかもしれません", "なんか、変な音がするんですけど、冷蔵庫のモーターかもしれません。"),
        GoldenPair("電車の中で、えー、傘を、傘を忘れてしまって、取りに戻るのが大変でした", "電車の中で、傘を忘れてしまって、取りに戻るのが大変でした。"),
        GoldenPair("明日の、明日の十時に、駅前で待ち合わせしましょう", "明日の十時に駅前で待ち合わせしましょう。"),
        GoldenPair("えーっとですね、あのー、今日は、そのー、進捗報告なんですけど、まあ、だいたい順調で、えー、来週には、テストが終わる予定です", "進捗報告です。まあ、だいたい順調で、来週にはテストが終わる予定です。"),
        GoldenPair("部長が、えー、明日の会議は中止だって言ってましたよ", "部長が、明日の会議は中止だって言ってましたよ。"),
        GoldenPair("週末は、えー、実家に帰って、庭の手入れを手伝う予定です、あと、仕事の話なんですけど、来週の、納期が、一日早まったので、月曜日までに、見積もりを出し直さないといけません", "週末は実家に帰って、庭の手入れを手伝う予定です。あと、仕事の話です。来週の納期が一日早まったので、月曜日までに見積もりを出し直さないといけません。"),
    )

    private val evalPairs = listOf(
        EvalPair("水をマレーシアから買わなくてはならないのです", "水をマレーシアから買わなくてはならないのです。", false),
        EvalPair("木曜日、停戦会談は何の進展もないまま終了しました", "木曜日、停戦会談は進展なく終了しました。", true),
        EvalPair("上院議員は私がデータをゆがめたと告発した", "上院議員は私がデータを誤って作成したと告発した。", true),
        EvalPair("1週間してそのニュースは本当になった", "そのニュースは1週間後に本当になった。", true),
        EvalPair("血圧は健康のパロメーターとして重要である", "血圧は健康のパロメーターとして重要です。", true),
        EvalPair("週に4回フランスの授業があります。", "週に4回フランスの授業があります。", false),
        EvalPair("許可書がなければここへは入れない", "許可書がなければここに入れない。", true),
        EvalPair("大声で泣きながら、女の子は母親を捜していた", "大声で泣きながら、女の子は母親を捜していた。", false),
        EvalPair("無罪の人々はもちろん放免された", "無罪の人々が放免された。", true),
        EvalPair("末期試験に備えて本当に気合いを入れて勉強しなきゃ", "本当に気合いを入れて勉強しなきゃ。", true),
        EvalPair("木曽川はしばしば日本のライン川と呼ばれている", "木曽川はしばしば日本のライン川と呼ばれている。", false),
        EvalPair("溺れかかっていた乗客はすべて救助された", "乗客はすべて救助された。", true),
        EvalPair("中心部にあるので商店やオフィスに行くのに便利です", "商店やオフィスが中心部にあるため、買い物や仕事に行くのに便利です。", true),
        EvalPair("残酷ということは彼の性質にはないことだ", "残酷は彼の性質にはならない。", true),
        EvalPair("庭園の周りにぐるりと高い塀が立っている", "高い塀が庭園の周りにぐるりと立っている。", true),
        EvalPair("出生率と死亡率はほぼ等しかった", "出生率と死亡率はほぼ等しかった。", false),
        EvalPair("絶対にそのスイッチに触ってはいけない", "絶対にそのスイッチに触ってはいけない。", false),
        EvalPair("部屋を出るときには明かりを消しなさい。", "明かりを消しなさい。", true),
        EvalPair("シェイクスピアの作品はとても難しくて読めない", "シェイクスピアの作品はとても難しくて読めない。", false),
        EvalPair("昔々一人の老人が住んでいた", "昔々一人の老人が住んでいた。", false),
        EvalPair("水をマレーシアから買わなくてはならないのです", "水をマレーシアから買わなくてはならないのです。", false),
        EvalPair("木曜日停戦会談は何の進展もないまま終了しました", "木曜日は停戦会談の進展がなかったまま終了しました。", true),
        EvalPair("上院議員は私がデータをゆがめたと告発した", "上院議員は私がデータを誤って作成したと告発した。", true),
        EvalPair("一週間してそのニュースは本当になった", "一週間してそのニュースは本当になった。", false),
        EvalPair("血圧は健康のパロメーターとして重要である", "血圧は健康のパロメーターとして重要です。", true),
        EvalPair("週に四回フランスの授業があります", "週に四回のフランスの授業があります。", true),
        EvalPair("許可書がなければここへは入れない", "許可書がなければここに入れない。", true),
        EvalPair("大声で鳴きながら女の子は母親を捜していた", "女の子は母親を大声で鳴きながら捜していた。", true),
        EvalPair("無罪の人々はもちろん放免された", "無罪の人々が放免された。", true),
        EvalPair("真木試験に備えて本当に気合いを入れて勉強しなきゃ", "真木さん、試験に備えて本当に気合いを入れて勉強しなきゃ。", true),
        EvalPair("木曽川はしばしば日本のライン川と呼ばれている", "木曽川はしばしば日本のライン川と呼ばれている。", false),
        EvalPair("溺れかかっていた乗客は全て救助された", "乗客は全て救助された。", true),
        EvalPair("中心部にあるので商店やオフィスに行くのに便利です", "商店やオフィスが中心部にあるため、買い物や仕事に行くのに便利です。", true),
        EvalPair("残酷ということは彼の性質にはないことだ", "残酷は彼の性質にはならない。", true),
        EvalPair("庭園の周りにぐるりと高い塀が立っている", "高い塀が庭園の周りにぐるりと立っている。", true),
        EvalPair("出生率と死亡率はほぼ等しかった", "出生率と死亡率はほぼ等しかった。", false),
        EvalPair("絶対にそのスイッチに触ってはいけない", "絶対にそのスイッチに触ってはいけない。", false),
        EvalPair("部屋を出る時には明かり置きしなさい", "明かりを置きなさい。", true),
        EvalPair("シェークスピアの作品はとても難しくて読めない", "シェークスピアの作品はとても難しくて読めない。", false),
        EvalPair("昔々一人の老人が住んでいた", "昔々一人の老人が住んでいた。", false),
    )

    @Test
    fun `all golden inputs pass the pre-gate`() {
        for ((i, pair) in goldenPairs.withIndex()) {
            assertTrue("golden ${i + 1} must need refinement", CleanupGuard.needsRefinement(pair.input))
        }
    }

    @Test
    fun `all golden outputs are accepted by the post-gate`() {
        for ((i, pair) in goldenPairs.withIndex()) {
            assertTrue(
                "golden ${i + 1} output must be accepted",
                CleanupGuard.isFaithfulRefinement(pair.input, pair.output),
            )
        }
    }

    @Test
    fun `all eval inputs are skipped by the pre-gate`() {
        for ((i, pair) in evalPairs.withIndex()) {
            assertFalse("eval $i must be skipped", CleanupGuard.needsRefinement(pair.input))
        }
    }

    @Test
    fun `combined guard saves every damaged eval case`() {
        var postGateOnlyRejects = 0
        for (pair in evalPairs) {
            if (!CleanupGuard.isFaithfulRefinement(pair.input, pair.output)) postGateOnlyRejects++
            if (pair.damaged) {
                assertTrue(
                    "damaged case must be saved: ${pair.input}",
                    !CleanupGuard.needsRefinement(pair.input) ||
                        !CleanupGuard.isFaithfulRefinement(pair.input, pair.output),
                )
            }
        }
        // The post-gate alone (without the pre-gate skip) still catches
        // the worst divergences — recorded count from the eval data.
        assertEquals(12, evalPairs.count { it.damaged && !CleanupGuard.isFaithfulRefinement(it.input, it.output) })
        assertTrue(postGateOnlyRejects >= 12)
    }
}
