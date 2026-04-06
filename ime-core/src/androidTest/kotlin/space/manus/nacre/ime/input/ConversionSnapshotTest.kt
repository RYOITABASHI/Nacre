package space.manus.nacre.ime.input

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Snapshot regression test for ConversionPipeline.convert() and predict().
 *
 * TWO MODES:
 *
 * 1. GENERATE mode (default, BASELINE_MODE = true):
 *    Prints top-5 candidates for 50+ inputs to logcat and writes a TSV to
 *    /sdcard/nacre_snapshot_baseline.tsv (or app's external files dir).
 *    Run this on a real device ONCE to capture the baseline.
 *    Command:
 *      adb shell am instrument -w -e class space.manus.nacre.ime.input.ConversionSnapshotTest#generateBaseline \
 *        space.manus.nacre.ime.test/androidx.test.runner.AndroidJUnitRunner
 *
 * 2. ASSERT mode (BASELINE_MODE = false, or run assertAgainstBaseline):
 *    Reads the previously written TSV and asserts that top-1 candidate still
 *    matches. Any regression causes the test to fail with a diff.
 *    Command:
 *      adb shell am instrument -w -e class space.manus.nacre.ime.input.ConversionSnapshotTest#assertAgainstBaseline \
 *        space.manus.nacre.ime.test/androidx.test.runner.AndroidJUnitRunner
 *
 * TSV format:
 *   mode\tinput\trank\tsurface\treading\tcost
 *   convert\tにほん\t1\t日本\tにほん\t3210
 */
@RunWith(AndroidJUnit4::class)
class ConversionSnapshotTest {

    private lateinit var context: Context
    private lateinit var dictionary: ConversionPipeline

    // ── Baseline file location ──────────────────────────────────────────────
    // Written to the app's external files dir so no WRITE_EXTERNAL_STORAGE
    // permission is needed on API 29+.
    private val baselineFileName = "nacre_snapshot_baseline.tsv"
    private val baselineFile: File get() =
        context.getExternalFilesDir(null)?.resolve(baselineFileName)
            ?: context.filesDir.resolve(baselineFileName)

    // ── Representative inputs ───────────────────────────────────────────────
    // 55 entries covering: common nouns, verbs, particles, long phrases,
    // ambiguous readings, katakana loanwords, proper nouns, honorifics,
    // numbers, English-mixed, symbols, kaomoji triggers.
    private val convertInputs: List<String> = listOf(
        // Common nouns
        "にほん",           // 日本
        "とうきょう",       // 東京
        "でんわ",           // 電話
        "かいぎ",           // 会議
        "しごと",           // 仕事
        "せんせい",         // 先生
        "がっこう",         // 学校
        "いえ",             // 家
        "くるま",           // 車
        "みず",             // 水

        // Verbs
        "たべる",           // 食べる
        "みる",             // 見る
        "かく",             // 書く
        "はなす",           // 話す
        "おもう",           // 思う
        "いく",             // 行く
        "くる",             // 来る
        "する",             // する
        "なる",             // なる
        "おしえる",         // 教える

        // Adjectives
        "たのしい",         // 楽しい
        "むずかしい",       // 難しい
        "あたらしい",       // 新しい
        "おおきい",         // 大きい
        "ちいさい",         // 小さい

        // Particles / short function words
        "は",               // は (topic marker — might be hiragana passthrough)
        "に",               // に
        "が",               // が
        "で",               // で
        "を",               // を

        // Long phrases (Viterbi segmentation)
        "きょうはいいてんきですね",   // 今日はいい天気ですね
        "おはようございます",         // おはようございます
        "よろしくおねがいします",     // よろしくお願いします
        "ありがとうございました",     // ありがとうございました
        "すみませんでした",           // すみませんでした

        // Ambiguous readings
        "はし",             // 橋 / 箸 / 端
        "かわ",             // 川 / 皮 / 革
        "あめ",             // 雨 / 飴
        "き",               // 木 / 気 / 機
        "いし",             // 石 / 意志 / 医師

        // Katakana loanwords (hiragana reading)
        "こんぴゅーた",     // コンピュータ
        "すまーとふぉん",   // スマートフォン
        "いんたーねっと",   // インターネット
        "あぷりけーしょん", // アプリケーション
        "かめら",           // カメラ

        // Proper nouns / names
        "さとう",           // 佐藤
        "すずき",           // 鈴木
        "おおさか",         // 大阪
        "ふじさん",         // 富士山
        "やまと",           // 大和

        // Honorifics / formal
        "おせわになっております",     // お世話になっております
        "よろしくおねがいもうしあげます", // よろしくお願い申し上げます

        // Numbers / mixed
        "さんじゅうごえん",           // 三十五円
        "にせんにじゅうろくねん",     // 二〇二六年

        // English-mixed (romaji input expectation)
        "えんたー",         // エンター (Enter)
        "くりっく",         // クリック (click)
    )

    private val predictInputs: List<Pair<String, String>> = listOf(
        // (kana, romaji) pairs — romaji empty means pure kana predict
        "にほ" to "",
        "とうきょ" to "",
        "でんわ" to "",
        "たべ" to "",
        "おはよ" to "",
        "よろし" to "",
        "こんぴゅ" to "",
        "すまーと" to "",
        "さと" to "",
        "いんたー" to "",
    )

    // ── Setup ───────────────────────────────────────────────────────────────

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dictionary = ConversionPipeline(context)
        Log.i(TAG, "Loading ConversionPipeline…")
        dictionary.load()
        Log.i(TAG, "ConversionPipeline loaded (${dictionary.entryCount} entries)")
    }

    @After
    fun tearDown() {
        // Nothing — dictionary is GC'd with the test
    }

    // ── Mode 1: Generate baseline ───────────────────────────────────────────

    /**
     * Runs all inputs through convert() and predict(), prints top-5 to logcat,
     * and writes results to a TSV file for later assertion.
     *
     * This test always passes — it is purely a capture step.
     */
    @Test
    fun generateBaseline() {
        val rows = mutableListOf<BaselineRow>()

        // convert()
        for (kana in convertInputs) {
            val candidates = try {
                dictionary.convert(kana)
            } catch (e: Exception) {
                Log.e(TAG, "convert('$kana') threw: ${e.message}")
                emptyList()
            }
            val top5 = candidates.take(5)
            logCandidates("convert", kana, top5)
            top5.forEachIndexed { i, c ->
                rows += BaselineRow("convert", kana, i + 1, c.surface, c.reading, c.cost)
            }
            if (top5.isEmpty()) {
                Log.w(TAG, "  convert('$kana'): NO CANDIDATES")
            }
        }

        // predict()
        for ((kana, romaji) in predictInputs) {
            val candidates = try {
                dictionary.predict(kana, romaji)
            } catch (e: Exception) {
                Log.e(TAG, "predict('$kana', '$romaji') threw: ${e.message}")
                emptyList()
            }
            val top5 = candidates.take(5)
            logCandidates("predict", kana, top5)
            top5.forEachIndexed { i, c ->
                rows += BaselineRow("predict", kana, i + 1, c.surface, c.reading, c.cost)
            }
        }

        // Write TSV
        val tsv = buildString {
            appendLine("mode\tinput\trank\tsurface\treading\tcost")
            for (r in rows) {
                appendLine("${r.mode}\t${r.input}\t${r.rank}\t${r.surface}\t${r.reading}\t${r.cost}")
            }
        }
        baselineFile.parentFile?.mkdirs()
        baselineFile.writeText(tsv, Charsets.UTF_8)

        Log.i(TAG, "Baseline written to: ${baselineFile.absolutePath}")
        Log.i(TAG, "Total rows: ${rows.size}")

        // Always pass
        assertTrue("Baseline generated successfully", rows.isNotEmpty())
    }

    // ── Mode 2: Assert against baseline ────────────────────────────────────

    /**
     * Reads the previously generated TSV and asserts that the top-1 candidate
     * for each input still matches the baseline surface text.
     *
     * Run this after refactoring to detect regressions.
     */
    @Test
    fun assertAgainstBaseline() {
        val file = baselineFile
        if (!file.exists()) {
            Log.w(TAG, "Baseline file not found at ${file.absolutePath} — skipping assertion.")
            Log.w(TAG, "Run generateBaseline first on a real device.")
            // Skip gracefully rather than fail — baseline may not exist yet
            return
        }

        val baseline = parseBaseline(file)
        val failures = mutableListOf<String>()

        // Assert convert() top-1
        for (kana in convertInputs) {
            val expected = baseline["convert\t$kana\t1"] ?: continue
            val actual = try {
                dictionary.convert(kana).firstOrNull()
            } catch (e: Exception) {
                Log.e(TAG, "convert('$kana') threw: ${e.message}")
                null
            }
            if (actual == null) {
                failures += "convert('$kana'): expected '${expected.surface}' but got NO RESULT"
            } else if (actual.surface != expected.surface) {
                failures += "convert('$kana'): expected top-1='${expected.surface}' but got '${actual.surface}' (cost=${actual.cost})"
            }
        }

        // Assert predict() top-1
        for ((kana, romaji) in predictInputs) {
            val expected = baseline["predict\t$kana\t1"] ?: continue
            val actual = try {
                dictionary.predict(kana, romaji).firstOrNull()
            } catch (e: Exception) {
                Log.e(TAG, "predict('$kana', '$romaji') threw: ${e.message}")
                null
            }
            if (actual == null) {
                failures += "predict('$kana'): expected '${expected.surface}' but got NO RESULT"
            } else if (actual.surface != expected.surface) {
                failures += "predict('$kana'): expected top-1='${expected.surface}' but got '${actual.surface}' (cost=${actual.cost})"
            }
        }

        if (failures.isNotEmpty()) {
            val msg = buildString {
                appendLine("=== SNAPSHOT REGRESSIONS DETECTED (${failures.size}) ===")
                for (f in failures) appendLine("  FAIL: $f")
            }
            Log.e(TAG, msg)
            assertTrue(msg, false)
        } else {
            Log.i(TAG, "All snapshot assertions passed.")
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun logCandidates(mode: String, input: String, candidates: List<ConversionCandidate>) {
        val sb = StringBuilder("$mode('$input'): ")
        if (candidates.isEmpty()) {
            sb.append("(none)")
        } else {
            candidates.forEachIndexed { i, c ->
                if (i > 0) sb.append(" | ")
                sb.append("[${i + 1}] ${c.surface} (cost=${c.cost})")
            }
        }
        Log.i(TAG, sb.toString())
    }

    private fun parseBaseline(file: File): Map<String, BaselineRow> {
        val map = mutableMapOf<String, BaselineRow>()
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.drop(1).forEach { line ->
                val parts = line.split("\t")
                if (parts.size >= 6) {
                    val row = BaselineRow(
                        mode = parts[0],
                        input = parts[1],
                        rank = parts[2].toIntOrNull() ?: 0,
                        surface = parts[3],
                        reading = parts[4],
                        cost = parts[5].toIntOrNull() ?: 0,
                    )
                    // Key: "mode\tinput\trank" — used for O(1) lookup
                    map["${row.mode}\t${row.input}\t${row.rank}"] = row
                }
            }
        }
        Log.i(TAG, "Parsed baseline: ${map.size} rows from ${file.absolutePath}")
        return map
    }

    // ── Data classes ────────────────────────────────────────────────────────

    private data class BaselineRow(
        val mode: String,
        val input: String,
        val rank: Int,
        val surface: String,
        val reading: String,
        val cost: Int,
    )

    companion object {
        private const val TAG = "ConversionSnapshotTest"
    }
}
