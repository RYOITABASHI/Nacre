package space.manus.nacre.ime.input

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Conversion accuracy benchmark for ConversionPipeline.
 *
 * Loads benchmark_sentences.tsv (input_kana<TAB>expected_surface pairs),
 * runs ConversionPipeline.convert() on each, and checks whether the expected
 * surface appears in the top-5 results.
 *
 * Target: top-5 accuracy >= 85%
 *
 * Run on a real device with:
 *   adb shell am instrument -w \
 *     -e class space.manus.nacre.ime.input.ConversionBenchmarkTest#conversionAccuracy \
 *     space.manus.nacre.ime.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class ConversionBenchmarkTest {

    @Test
    fun conversionAccuracy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pipeline = ConversionPipeline(context)
        pipeline.load()

        val sentences = loadBenchmark(context)
        assertTrue("benchmark_sentences.tsv must have at least 10 entries", sentences.size >= 10)

        var top1Correct = 0
        var top5Correct = 0
        val failures = mutableListOf<String>()

        for ((kana, expected) in sentences) {
            val result = pipeline.convert(kana)
            val top5Surfaces = result.take(5).map { it.surface }
            when {
                top5Surfaces.isNotEmpty() && top5Surfaces[0] == expected -> {
                    top1Correct++
                    top5Correct++
                }
                expected in top5Surfaces -> {
                    top5Correct++
                }
                else -> {
                    failures.add("$kana → expected: $expected, got: ${top5Surfaces.take(3)}")
                }
            }
        }

        val top1Accuracy = top1Correct.toFloat() / sentences.size
        val top5Accuracy = top5Correct.toFloat() / sentences.size

        println("=== Conversion Benchmark ===")
        println("Total sentences: ${sentences.size}")
        println("Top-1 accuracy: ${(top1Accuracy * 100).toInt()}% ($top1Correct/${sentences.size})")
        println("Top-5 accuracy: ${(top5Accuracy * 100).toInt()}% ($top5Correct/${sentences.size})")
        println("Failures (${failures.size}):")
        for (f in failures.take(20)) println("  $f")
        if (failures.size > 20) println("  … and ${failures.size - 20} more")

        assertTrue(
            "Top-5 accuracy ${(top5Accuracy * 100).toInt()}% is below 85% target " +
                "($top5Correct/${sentences.size})\n" +
                "First failures:\n" + failures.take(10).joinToString("\n") { "  $it" },
            top5Accuracy >= 0.85f,
        )
    }

    private fun loadBenchmark(context: Context): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        context.assets.open("benchmark_sentences.tsv").bufferedReader().forEachLine { line ->
            if (line.isBlank() || line.startsWith('#')) return@forEachLine
            val parts = line.split('\t')
            if (parts.size >= 2) results.add(parts[0].trim() to parts[1].trim())
        }
        return results
    }
}
