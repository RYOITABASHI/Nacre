package space.manus.nacre.ime.input

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * Dictionary entry with POS (Part-of-Speech) information.
 * Used by DictionaryManager, ViterbiEngine, CandidateRanker, etc.
 */
data class DictEntry(
    val surface: String,
    val cost: Int,
    val leftGroup: Int = 1,   // POS group for left context (default: noun)
    val rightGroup: Int = 1,  // POS group for right context
)

/**
 * User-registered dictionary entry.
 */
data class UserDictEntry(
    val reading: String,
    val surface: String,
    val comment: String = "",  // Optional user memo (e.g. "work address")
)

/**
 * Phrase memory entry for multi-word phrase completion.
 */
data class PhraseEntry(
    val reading: String,       // Full hiragana reading
    val surface: String,       // Full committed surface text
    var count: Int = 1,        // Times this phrase was committed
    var lastEpoch: Int = 0,    // Last epoch when this phrase was used
)

/**
 * Manages dictionary loading, entry lookup, prefix search, and connection cost.
 *
 * Extracted from ConversionPipeline to separate pure dictionary I/O and lookup
 * from conversion logic (Viterbi, user learning, candidate ranking).
 *
 * Dictionary format: reading\tsurface\tleft_id\tright_id\tcost
 * Connection matrix: binary 2670x2670 int16 (connection.bin)
 *
 * Mozc POS ID ranges:
 *   0=BOS/EOS, 2..11=filler, 12..28=adverb, 29..267=aux verb,
 *   268..433=particle, 434..1840=verb, 1841..2193=noun,
 *   2194..2588=adjective, 2589..2590=interjection, 2591..2593=conjunction,
 *   2594..2640=prefix, 2641..2656=symbol, 2657..2669=adnominal
 */
class DictionaryManager(private val context: Context) {

    // reading -> list of entries with POS info (initial capacity smaller to avoid OOM on allocation)
    val dict = HashMap<String, MutableList<DictEntry>>(400000)

    // Sorted readings for prefix search
    var sortedReadings: Array<String> = emptyArray()
        private set

    /** Total number of dictionary entries loaded (for debug display) */
    var entryCount = 0
        private set

    // Full Mozc connection cost matrix as flat ShortArray [right_id * numIds + left_id]
    var connectionCostFlat: ShortArray = ShortArray(0)
        private set
    var numIds: Int = 0
        private set

    // Static bigram data: "prevSurface->nextReading:nextSurface" -> boost value
    val staticBigrams = HashMap<String, Int>(2000)

    companion object {
        const val DEFAULT_CONNECTION_COST = 2000

        // Mozc POS ID range checks (from id.def)
        fun isNoun(id: Int) = id in 1841..2193
        fun isVerb(id: Int) = id in 434..1840
        fun isAdjective(id: Int) = id in 2194..2588
        fun isAuxVerb(id: Int) = id in 29..267
        fun isParticle(id: Int) = id in 268..433
        fun isContentWord(id: Int) = id in 434..2588  // verb+noun+adjective
        fun isFunctionWord(id: Int) = id in 29..433   // aux verb+particle
        fun isAdverb(id: Int) = id in 12..28
        fun isConjunction(id: Int) = id in 2591..2593
        fun isInterjection(id: Int) = id in 2589..2590
        fun isSymbol(id: Int) = id in 2641..2656

        // Full-width katakana -> half-width katakana mapping
        val HALF_WIDTH_KATAKANA_MAP = mapOf(
            'ァ' to "ｧ", 'ア' to "ｱ", 'ィ' to "ｨ", 'イ' to "ｲ", 'ゥ' to "ｩ",
            'ウ' to "ｳ", 'ェ' to "ｪ", 'エ' to "ｴ", 'ォ' to "ｫ", 'オ' to "ｵ",
            'カ' to "ｶ", 'キ' to "ｷ", 'ク' to "ｸ", 'ケ' to "ｹ", 'コ' to "ｺ",
            'サ' to "ｻ", 'シ' to "ｼ", 'ス' to "ｽ", 'セ' to "ｾ", 'ソ' to "ｿ",
            'タ' to "ﾀ", 'チ' to "ﾁ", 'ツ' to "ﾂ", 'テ' to "ﾃ", 'ト' to "ﾄ",
            'ナ' to "ﾅ", 'ニ' to "ﾆ", 'ヌ' to "ﾇ", 'ネ' to "ﾈ", 'ノ' to "ﾉ",
            'ハ' to "ﾊ", 'ヒ' to "ﾋ", 'フ' to "ﾌ", 'ヘ' to "ﾍ", 'ホ' to "ﾎ",
            'マ' to "ﾏ", 'ミ' to "ﾐ", 'ム' to "ﾑ", 'メ' to "ﾒ", 'モ' to "ﾓ",
            'ヤ' to "ﾔ", 'ュ' to "ｭ", 'ユ' to "ﾕ", 'ョ' to "ｮ", 'ヨ' to "ﾖ",
            'ラ' to "ﾗ", 'リ' to "ﾘ", 'ル' to "ﾙ", 'レ' to "ﾚ", 'ロ' to "ﾛ",
            'ワ' to "ﾜ", 'ヲ' to "ｦ", 'ン' to "ﾝ",
            'ガ' to "ｶﾞ", 'ギ' to "ｷﾞ", 'グ' to "ｸﾞ", 'ゲ' to "ｹﾞ", 'ゴ' to "ｺﾞ",
            'ザ' to "ｻﾞ", 'ジ' to "ｼﾞ", 'ズ' to "ｽﾞ", 'ゼ' to "ｾﾞ", 'ゾ' to "ｿﾞ",
            'ダ' to "ﾀﾞ", 'ヂ' to "ﾁﾞ", 'ヅ' to "ﾂﾞ", 'デ' to "ﾃﾞ", 'ド' to "ﾄﾞ",
            'バ' to "ﾊﾞ", 'ビ' to "ﾋﾞ", 'ブ' to "ﾌﾞ", 'ベ' to "ﾍﾞ", 'ボ' to "ﾎﾞ",
            'パ' to "ﾊﾟ", 'ピ' to "ﾋﾟ", 'プ' to "ﾌﾟ", 'ペ' to "ﾍﾟ", 'ポ' to "ﾎﾟ",
            'ッ' to "ｯ", 'ャ' to "ｬ", 'ー' to "ｰ", 'ヴ' to "ｳﾞ",
        )
    }

    /**
     * Get the POS-based connection cost between two words.
     * Uses the full Mozc 2670x2670 connection cost matrix.
     *
     * @param inputLength total length of the input being converted; controls division scale.
     *   Shorter input → stronger division (less connection influence for precision),
     *   longer input → weaker division (more connection influence for accuracy).
     *   Default 8 produces divisor 3.0 (backward compatible).
     */
    fun getConnectionCost(prevRightId: Int, currLeftId: Int, inputLength: Int = 8): Int {
        if (connectionCostFlat.isEmpty() || numIds == 0) return DEFAULT_CONNECTION_COST
        val r = prevRightId.coerceIn(0, numIds - 1)
        val l = currLeftId.coerceIn(0, numIds - 1)
        val idx = r * numIds + l
        if (idx >= connectionCostFlat.size) return DEFAULT_CONNECTION_COST
        // Dynamic scaling: shorter input = stronger division (less connection influence)
        // Longer input = weaker division (more connection influence for accuracy)
        val divisor = when {
            inputLength <= 5 -> 4.0f
            inputLength <= 10 -> 3.0f  // current default
            else -> 2.5f
        }
        return (connectionCostFlat[idx].toInt() / divisor).toInt()
    }

    /**
     * Look up dictionary entries for an exact reading.
     */
    fun lookup(reading: String): List<DictEntry> = dict[reading] ?: emptyList()

    /**
     * Prefix search: return all readings that start with [prefix] and their entries.
     * Pure lookup -- no boost or POS context cost applied.
     *
     * @param prefix the hiragana prefix to search for
     * @param limit max number of reading groups to return
     * @return list of (reading, entries) pairs sorted by reading
     */
    fun prefixSearch(prefix: String, limit: Int = 100): List<Pair<String, List<DictEntry>>> {
        if (limit <= 0 || sortedReadings.isEmpty()) return emptyList()

        val results = mutableListOf<Pair<String, List<DictEntry>>>()

        var idx = sortedReadings.binarySearch(prefix).let {
            if (it >= 0) it else -(it + 1)
        }

        while (idx < sortedReadings.size && sortedReadings[idx].startsWith(prefix)) {
            val reading = sortedReadings[idx]
            if (reading != prefix) {
                val entries = dict[reading]
                if (entries != null && entries.isNotEmpty()) {
                    results.add(reading to entries)
                    if (results.size >= limit) break
                }
            }
            idx++
        }

        return results
    }

    /**
     * Load all dictionaries and the connection cost matrix.
     * Does NOT load user learning data (user boost, user dict, phrase memory).
     */
    fun load() {
        loadConnectionMatrix()
        loadMozcDictionary()
        loadSlangDictionary()
        loadSupplementaryDict("dict/common_phrases.tsv", "common phrases")
        loadSupplementaryDict("dict/person_names.tsv", "person names")
        loadSupplementaryDict("dict/emoji_kaomoji.tsv", "emoji/kaomoji")
        loadSupplementaryDict("dict/symbols.tsv", "symbols")

        // Boost person name entries: Mozc defaults are too high (median ~6500)
        // Reduce by 1500 to make names more competitive with common nouns
        var namesBoosted = 0
        for (entries in dict.values) {
            for (i in entries.indices) {
                val e = entries[i]
                if (e.leftGroup in 1921..1923 && e.cost > 3500) {
                    entries[i] = e.copy(cost = e.cost - 1500)
                    namesBoosted++
                }
            }
        }
        if (namesBoosted > 0) {
            Log.i("DictionaryManager", "Boosted $namesBoosted person name entries (cost -1500)")
        }

        // Sort all entries and build index ONCE after all dicts loaded (avoids OOM from repeated sorts)
        for (entries in dict.values) {
            entries.sortBy { it.cost }
        }
        sortedReadings = dict.keys.toTypedArray().also { it.sort() }
        Log.i("DictionaryManager", "Index built: ${dict.size} readings, ${sortedReadings.size} sorted")

        loadStaticBigrams()
    }

    private fun loadConnectionMatrix() {
        try {
            context.assets.open("dict/connection.bin").use { stream ->
                val dis = java.io.DataInputStream(java.io.BufferedInputStream(stream, 65536))
                // First 4 bytes: uint32 num_ids (little-endian)
                val b = ByteArray(4)
                dis.readFully(b)
                numIds = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
                val total = numIds * numIds
                connectionCostFlat = ShortArray(total)
                // Read int16 values in 8KB chunks to avoid 14MB byte[] allocation
                val chunkBytes = ByteArray(8192)
                var idx = 0
                while (idx < total) {
                    val remaining = (total - idx) * 2
                    val toRead = minOf(chunkBytes.size, remaining)
                    dis.readFully(chunkBytes, 0, toRead)
                    val chunk = ByteBuffer.wrap(chunkBytes, 0, toRead).order(ByteOrder.LITTLE_ENDIAN)
                    val count = toRead / 2
                    for (j in 0 until count) {
                        connectionCostFlat[idx++] = chunk.short
                    }
                }
                Log.i("DictionaryManager", "Connection matrix loaded: ${numIds}x${numIds} (${total * 2 / 1024}KB)")
            }
        } catch (e: Exception) {
            Log.e("DictionaryManager", "Failed to load binary connection matrix, trying TSV fallback", e)
            loadConnectionMatrixTsvFallback()
        }
    }

    private fun loadConnectionMatrixTsvFallback() {
        try {
            val rows = mutableListOf<IntArray>()
            var n = 14
            context.assets.open("dict/connection_group.tsv").use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    reader.forEachLine { line ->
                        if (line.isBlank() || line.startsWith('#')) return@forEachLine
                        val trimmed = line.trim()
                        if (rows.isEmpty() && trimmed.toIntOrNull() != null) {
                            n = trimmed.toInt()
                            return@forEachLine
                        }
                        val values = trimmed.split('\t').map { it.toIntOrNull() ?: 5000 }
                        rows.add(values.toIntArray())
                    }
                }
            }
            numIds = n
            connectionCostFlat = ShortArray(n * n)
            for (r in 0 until minOf(n, rows.size)) {
                for (c in 0 until minOf(n, rows[r].size)) {
                    connectionCostFlat[r * n + c] = rows[r][c].coerceIn(-32768, 32767).toShort()
                }
            }
            Log.i("DictionaryManager", "Connection matrix (TSV fallback): ${n}x${n}")
        } catch (e2: Exception) {
            Log.e("DictionaryManager", "TSV fallback also failed", e2)
            numIds = 0
            connectionCostFlat = ShortArray(0)
        }
    }

    fun loadMozcDictionary() {
        try {
            // Try gzip binary first, fall back to plain TSV
            val stream = try {
                GZIPInputStream(context.assets.open("dict/mozc_dict.bin"))
            } catch (_: Exception) {
                context.assets.open("dict/mozc_dict.tsv")
            }

            stream.use { rawStream ->
                BufferedReader(InputStreamReader(rawStream, Charsets.UTF_8), 65536).use { reader ->
                    var count = 0
                    reader.forEachLine { line ->
                        if (line.isBlank() || line.startsWith('#')) return@forEachLine
                        val parts = line.split('\t')
                        if (parts.size >= 5) {
                            val reading = parts[0]
                            val surface = parts[1]
                            val leftGroup = parts[2].toIntOrNull() ?: 1
                            val rightGroup = parts[3].toIntOrNull() ?: 1
                            val cost = parts[4].toIntOrNull() ?: 10000
                            dict.getOrPut(reading) { mutableListOf() }
                                .add(DictEntry(surface, cost, leftGroup, rightGroup))
                            count++
                        } else if (parts.size >= 3) {
                            val reading = parts[0]
                            val surface = parts[1]
                            val cost = parts[2].toIntOrNull() ?: 10000
                            if (cost <= 15000) {
                                dict.getOrPut(reading) { mutableListOf() }
                                    .add(DictEntry(surface, cost))
                            }
                            count++
                        }
                    }
                    entryCount = count
                    Log.i("DictionaryManager", "Dictionary loaded: $count entries, ${dict.size} unique readings")
                }
            }
        } catch (e: Exception) {
            Log.e("DictionaryManager", "Failed to load dictionary", e)
        }

        // Sorting deferred to load() after all dicts are loaded
    }

    fun loadSlangDictionary() {
        try {
            context.assets.open("dict/slang_words.tsv").use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    var count = 0
                    reader.forEachLine { line ->
                        if (line.isBlank() || line.startsWith('#')) return@forEachLine
                        val parts = line.split('\t')
                        if (parts.size >= 5) {
                            val reading = parts[0]
                            val surface = parts[1]
                            val leftGroup = parts[2].toIntOrNull() ?: 1
                            val rightGroup = parts[3].toIntOrNull() ?: 1
                            val cost = parts[4].toIntOrNull() ?: 5000
                            val entries = dict.getOrPut(reading) { mutableListOf() }
                            // Only add if not already present (Mozc takes priority for same surface)
                            if (entries.none { it.surface == surface }) {
                                entries.add(DictEntry(surface, cost, leftGroup, rightGroup))
                                count++
                            }
                        }
                    }
                    Log.i("DictionaryManager", "Slang dict loaded: $count new entries")
                }
            }
            // Sorting deferred to load()
        } catch (e: Exception) {
            Log.i("DictionaryManager", "No slang dictionary found (optional)")
        }
    }

    /**
     * Load a supplementary TSV dictionary (emoji, symbols, etc).
     * Format: reading\tsurface\tleft_id\tright_id\tcost
     */
    fun loadSupplementaryDict(assetPath: String, label: String) {
        try {
            context.assets.open(assetPath).use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    var count = 0
                    reader.forEachLine { line ->
                        if (line.isBlank() || line.startsWith('#')) return@forEachLine
                        val parts = line.split('\t')
                        if (parts.size >= 5) {
                            val reading = parts[0]
                            val surface = parts[1]
                            val leftGroup = parts[2].toIntOrNull() ?: 2641
                            val rightGroup = parts[3].toIntOrNull() ?: 2641
                            val cost = parts[4].toIntOrNull() ?: 5500
                            val entries = dict.getOrPut(reading) { mutableListOf() }
                            if (entries.none { it.surface == surface }) {
                                entries.add(DictEntry(surface, cost, leftGroup, rightGroup))
                                count++
                            }
                        }
                    }
                    Log.i("DictionaryManager", "$label dict loaded: $count entries")
                }
            }
        } catch (e: Exception) {
            Log.i("DictionaryManager", "No $label dictionary found (optional)")
        }
    }

    /**
     * Load static bigram data from bigrams.tsv.
     * Format: prev_surface\tnext_reading\tnext_surface\tboost
     */
    fun loadStaticBigrams() {
        try {
            context.assets.open("dict/bigrams.tsv").use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    var count = 0
                    reader.forEachLine { line ->
                        if (line.isBlank() || line.startsWith('#')) return@forEachLine
                        val parts = line.split('\t')
                        if (parts.size >= 4) {
                            val prevSurface = parts[0]
                            val nextReading = parts[1]
                            val nextSurface = parts[2]
                            val boost = parts[3].toIntOrNull() ?: 1000
                            val key = "$prevSurface\u2192$nextReading:$nextSurface"
                            staticBigrams[key] = boost
                            count++
                        }
                    }
                    Log.i("DictionaryManager", "Static bigrams loaded: $count entries")
                }
            }
        } catch (e: Exception) {
            Log.i("DictionaryManager", "No bigrams.tsv found (optional)")
        }
    }
}
