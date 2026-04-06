package space.manus.nacre.ai

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class KenLmScorerTest {

    @Test
    fun selectModel_prefers5gramOverBundled() {
        val filesDir = createTempDir("kenlm-test-files")
        val extDir = createTempDir("kenlm-test-ext")

        File(filesDir, "models").mkdirs()
        File(extDir, "models").mkdirs()

        File(filesDir, "models/japanese-3gram.klm").writeText("dummy")
        File(extDir, "models/japanese-5gram.klm").writeText("dummy")

        val result = KenLmScorer.selectModel(filesDir, listOf(extDir), quickPaths = emptyList())
        assertNotNull(result)
        assertTrue(result!!.contains("5gram"))

        filesDir.deleteRecursively()
        extDir.deleteRecursively()
    }

    @Test
    fun selectModel_fallsBackTo3gram() {
        val filesDir = createTempDir("kenlm-test-files")

        File(filesDir, "models").mkdirs()
        File(filesDir, "models/japanese-3gram.klm").writeText("dummy")

        val result = KenLmScorer.selectModel(filesDir, emptyList(), quickPaths = emptyList())
        assertNotNull(result)
        assertTrue(result!!.contains("3gram"))

        filesDir.deleteRecursively()
    }

    @Test
    fun selectModel_prefersInternal5gramOver3gram() {
        val filesDir = createTempDir("kenlm-test-files")

        File(filesDir, "models").mkdirs()
        File(filesDir, "models/japanese-5gram.klm").writeText("dummy")
        File(filesDir, "models/japanese-3gram.klm").writeText("dummy")

        val result = KenLmScorer.selectModel(filesDir, emptyList(), quickPaths = emptyList())
        assertNotNull(result)
        assertTrue(result!!.contains("5gram"))

        filesDir.deleteRecursively()
    }

    @Test
    fun selectModel_prefersCompactOver3gram() {
        val filesDir = createTempDir("kenlm-test-files")

        File(filesDir, "models").mkdirs()
        File(filesDir, "models/japanese-compact.klm").writeText("dummy")
        File(filesDir, "models/japanese-3gram.klm").writeText("dummy")

        val result = KenLmScorer.selectModel(filesDir, emptyList(), quickPaths = emptyList())
        assertNotNull(result)
        assertTrue(result!!.contains("compact"))

        filesDir.deleteRecursively()
    }

    @Test
    fun selectModel_returnsNullWhenNoModel() {
        val filesDir = createTempDir("kenlm-test-files")
        val result = KenLmScorer.selectModel(filesDir, emptyList(), quickPaths = emptyList())
        assertNull(result)
        filesDir.deleteRecursively()
    }
}
