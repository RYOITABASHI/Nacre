package space.manus.nacre.ime.input

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollocationsTest {

    private fun gz(text: String): InputStream {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    @Test
    fun loadsLooksUpMergesAndSaturates() {
        val table = Collocations.load(gz(
            "# comment\n" +
                "K\t猫\tを\t飼:か\t205\n" +
                "K\t猫\tを\t買:か\t9\n" +
                "R\t風邪\tを\tひ\t248\n" +
                "R\t風邪\tを\tひ\t2\n" +          // duplicate key: counts merge
                "K\t方\tが\t良:よ\t99999\n" +     // saturates at Short.MAX_VALUE
                "broken line\n",
        ))
        assertEquals(205, table.kanjiCount("猫", "を", "飼", "か"))
        assertEquals(9, table.kanjiCount("猫", "を", "買", "か"))
        assertEquals(0, table.kanjiCount("猫", "を", "飼", "き"))   // reading must match
        assertEquals(250, table.readingCount("風邪", "を", "ひ"))
        assertEquals(Short.MAX_VALUE.toInt(), table.kanjiCount("方", "が", "良", "よ"))
        assertEquals(0, table.readingCount("神", "を", "き"))
        assertEquals(4, table.size)
    }

    @Test
    fun bonusIsLogarithmicAndZeroForUnseen() {
        assertEquals(0, Collocations.bonus(0, 300))
        assertTrue(Collocations.bonus(205, 300) > Collocations.bonus(9, 300))
        assertTrue(Collocations.bonus(30000, 300) < 300 * 11)
    }

    // --- Against the shipped table and dictionary ---

    private class FileAssetOpener(private val root: File) : AssetOpener {
        override fun open(name: String): InputStream = FileInputStream(File(root, name))
    }

    private class InMemoryStore : DictionaryKeyValueStore {
        private val strings = HashMap<String, String>()
        private val ints = HashMap<String, Int>()
        override fun getString(key: String): String? = strings[key]
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun getInt(key: String, defaultValue: Int): Int = ints[key] ?: defaultValue
        override fun putInt(key: String, value: Int) { ints[key] = value }
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue
    }

    private fun locateUpwards(relPath: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, relPath)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        throw IllegalStateException("Could not locate $relPath")
    }

    @Test
    fun collocationsPickTheHomophoneTheSentenceNeeds() {
        val assets = try { locateUpwards("ime-core/src/main/assets") } catch (e: IllegalStateException) { locateUpwards("src/main/assets") }
        val stores = HashMap<String, InMemoryStore>()
        val dict = NacreDictionary(FileAssetOpener(assets), { stores.getOrPut(it) { InMemoryStore() } }, null)
        dict.load()
        for ((reading, expected) in listOf(
            "ねこをかっています" to "猫を飼っています",
            "かみをきりました" to "髪を切りました",
            "ぴあのをひきます" to "ピアノを弾きます",
            "いしをひろいました" to "石を拾いました",
        )) {
            assertEquals(reading, expected, dict.convert(reading).first().surface)
        }
    }
}
