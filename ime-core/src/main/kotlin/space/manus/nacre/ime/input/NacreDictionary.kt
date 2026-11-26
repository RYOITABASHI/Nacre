package space.manus.nacre.ime.input

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import space.manus.nacre.ai.KenLmScorer

/**
 * Nacre Japanese Dictionary with POS-aware Viterbi conversion.
 *
 * Uses Mozc OSS dictionary with full 2670 POS IDs and the original
 * 2670×2670 connection cost matrix for maximum conversion accuracy.
 *
 * Dictionary format: reading\tsurface\tleft_id\tright_id\tcost
 * Connection matrix: binary 2670×2670 int16 (connection.bin)
 *
 * Mozc POS ID ranges:
 *   0=BOS/EOS, 2..11=フィラー, 12..28=副詞, 29..267=助動詞,
 *   268..433=助詞, 434..1840=動詞, 1841..2193=名詞,
 *   2194..2588=形容詞, 2589..2590=感動詞, 2591..2593=接続詞,
 *   2594..2640=接頭詞, 2641..2656=記号, 2657..2669=連体詞
 */
class NacreDictionary internal constructor(
    private val assetOpener: AssetOpener,
    private val storeProvider: (String) -> DictionaryKeyValueStore,
    private val androidContext: Context?,
    private val snapshotDir: File? = null,
) : DictionaryProvider {

    /**
     * Production constructor: assets and learning data come from the Android
     * application context — the same AssetManager / SharedPreferences access
     * the engine always used, now routed through the data-source seam
     * (see DictionaryDataSource.kt). Behavior is unchanged.
     */
    constructor(context: Context) : this(
        AssetOpener { name -> context.assets.open(name) },
        { name -> SharedPreferencesStore(context, name) },
        context,
        File(context.filesDir, "dict_snapshot"),
    )

    // reading → list of entries with POS info (initial capacity smaller to avoid OOM on allocation)
    private val dict = HashMap<String, MutableList<DictEntry>>(400000)


    // Sorted readings for prefix search
    private var sortedReadings: Array<String> = emptyArray()

    // Full Mozc connection cost matrix as flat ShortArray [right_id * numIds + left_id]
    private var connectionCostFlat: ShortArray = ShortArray(0)
    private var numIds = 0

    // User learning: boost for selected candidates (thread-safe)
    private val userBoost = ConcurrentHashMap<String, Int>(1000)

    // Selection learning with per-entry recency (see ConversionLearning):
    // (reading, surface) → selection count + sequence of the last pick.
    // userBoost above is the legacy counter; both are updated by
    // recordSelection() and the effective count is the max of the two, so
    // pre-existing learned counts survive the migration.
    private val conversionLearning = ConcurrentHashMap<String, ConversionLearning.Entry>(1000)
    private val learningSeq = java.util.concurrent.atomic.AtomicLong(0)

    // Bigram learning: "prevSurface→reading:surface" → count
    private val bigramBoost = ConcurrentHashMap<String, Int>(500)

    // Trigram learning: "prev2→prev1→reading:surface" → count
    private val trigramBoost = ConcurrentHashMap<String, Int>(300)

    // Recent history: ordered list of recently committed candidates (newest first)
    private val recentHistory = java.util.LinkedList<ConversionCandidate>()
    private val maxHistory = 200

    // User dictionary: reading → surface, registered by user (custom words, names, etc.)
    private val userDictionary = ConcurrentHashMap<String, MutableList<UserDictEntry>>(100)

    // Phrase memory: committed multi-word phrases for phrase completion
    // Key: first 2 chars of reading, Value: list of phrase entries
    private val phraseMemory = ConcurrentHashMap<String, MutableList<PhraseEntry>>(200)
    private val maxPhrases = 500

    // Learning epoch: increments each session, used for frequency decay
    private var learningEpoch: Int = 0

    // Static bigram data: "prevSurface→nextReading:nextSurface" → boost value
    private val staticBigrams = HashMap<String, Int>(2000)

    // English word dictionary (hiragana reading → English words)
    private val englishDict = HashMap<String, MutableList<DictEntry>>(5000)

    // Full English dictionary: lowercase key → list of DictEntry (surface may have mixed case)
    private val englishFullDict = HashMap<String, MutableList<DictEntry>>(25000)
    // Sorted keys for binary-search prefix matching
    private var englishSortedKeys: Array<String> = emptyArray()
    // English word learning: "prevWord→word" → count (bigram boost)
    private val englishBigramBoost = ConcurrentHashMap<String, Int>(200)
    private var lastCommittedEnglish: String = ""

    // English commit history: recently committed alphabet words (newest
    // first, committed case preserved), surfaced by predictEnglish() the
    // way recentHistory is surfaced by predict(). Kept separate from
    // recentHistory: that list is keyed by kana readings and also feeds
    // Japanese next-word prediction, which alphabet words must not leak
    // into. Same cap (maxHistory) and same persistence store.
    // Access is guarded by englishHistoryLock: predictEnglish() runs on a
    // background dispatcher while commits mutate the list on the main
    // thread, and an unsynchronized LinkedList iteration racing a mutation
    // can throw ConcurrentModificationException. (The older recentHistory
    // predates this discipline and is left as-is.)
    private val recentEnglishHistory = java.util.LinkedList<String>()
    private val englishHistoryLock = Any()

    // N-gram context: last 4 committed surfaces (for KenLM 5-gram)
    private val committedContext = ArrayDeque<String>(4)

    // Last committed right POS group (for connection cost to next word)
    private var lastRightGroup: Int = 0  // BOS/EOS

    // Convenience accessors for backward compat
    private val lastCommittedSurface: String get() = committedContext.firstOrNull() ?: ""
    private val secondLastCommittedSurface: String get() = committedContext.getOrNull(1) ?: ""

    // KenLM 5-gram language model scorer (optional, loaded from ime-ai)
    @Volatile
    var kenLmScorer: KenLmScorer? = null
        set(value) {
            field = value
            // Viterbi scoring reads the scorer — memoized predict() results
            // computed without it (or with a previous one) are stale.
            bumpPredictStateVersion()
        }

    private var loaded = false

    /** True when [load] restored static content from the on-disk snapshot instead of parsing the bundled assets. */
    var loadedFromSnapshot = false
        private set

    // --- predict() memoization ---
    // predict() output depends on mutable learning state (user boosts,
    // bigram/trigram boosts, recent history, committed context, phrase
    // memory, the injected user dictionary) in addition to its arguments.
    // Every mutation of that state funnels through bumpPredictStateVersion(),
    // which clears the cache — so a cached result is only ever reused while
    // everything it depended on is unchanged. See predict() for the key.
    private val predictCacheLock = Any()
    private val predictCache = object : LinkedHashMap<PredictCacheKey, List<ConversionCandidate>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<PredictCacheKey, List<ConversionCandidate>>?,
        ): Boolean = size > PREDICT_CACHE_MAX_ENTRIES
    }

    private data class PredictCacheKey(
        val kana: String,
        val romaji: String,
        val mozcNative: Boolean,
    )

    // Generation of the learning state predict() results were computed
    // from. Bumped (under predictCacheLock) by every invalidation, so a
    // predict() that started before an invalidation can detect it and
    // refrain from caching its now-stale result afterward.
    private var predictStateGeneration = 0L

    private fun bumpPredictStateVersion() {
        synchronized(predictCacheLock) {
            predictCache.clear()
            predictStateGeneration++
        }
    }
    private var savePending = false
    private var lastSaveTime = 0L

    /** Total number of dictionary entries loaded (for debug display) */
    var entryCount = 0
        private set

    data class DictEntry(
        val surface: String,
        val cost: Int,
        val leftGroup: Int = 1,   // POS group for left context (default: noun)
        val rightGroup: Int = 1,  // POS group for right context
        /**
         * Other part-of-speech readings of the same (reading, surface) —
         * e.g. て as 接続助詞 besides 助動詞 — each with its own Mozc ids
         * and cost. Only the conversion lattice reads them (its connection
         * costs are meaningless without the right POS for each context);
         * every other consumer keeps seeing one entry per surface. Held on
         * the entry itself rather than in a side map: the ~0.94M variants
         * then cost no extra keys or lists (the reference fits in the
         * object's alignment padding).
         */
        val variants: Array<DictEntry>? = null,
    )

    data class UserDictEntry(
        val reading: String,
        val surface: String,
        val comment: String = "",  // Optional user memo (e.g. "work address")
    )

    data class PhraseEntry(
        val reading: String,       // Full hiragana reading
        val surface: String,       // Full committed surface text
        var count: Int = 1,        // Times this phrase was committed
        var lastEpoch: Int = 0,    // Last epoch when this phrase was used
    )

    fun load() {
        if (loaded) return

        // Static (asset-derived) content: restore from the on-disk snapshot
        // when a valid one exists — this skips the gzip TSV parse of the
        // 1.05M-entry Mozc dictionary, the per-entry sorts and the index
        // builds on every IME process start. Any doubt about the snapshot
        // (missing, stale key, corrupt) falls back to the full parse, which
        // then rewrites the snapshot for the next start. User/learning data
        // further below is NOT snapshotted — it is re-read from the store
        // on every load exactly as before.
        loadedFromSnapshot = tryLoadSnapshot()
        if (!loadedFromSnapshot) {
            loadStaticContentFromAssets()
            writeSnapshotQuietly()
        }

        loadUserBoost()
        loadUserDictionary()
        loadPhraseMemory()

        // Inject user dictionary entries into the main dict for conversion
        injectUserDictionary()

        // Increment learning epoch and apply frequency decay
        learningEpoch++
        applyFrequencyDecay()
        saveEpoch()

        loaded = true
    }

    /** Full asset parse of the static dictionary content (see [load]). */
    private fun loadStaticContentFromAssets() {
        loadConnectionMatrix()
        loadMozcDictionary()
        loadSlangDictionary()
        loadSupplementaryDict("dict/common_phrases.tsv", "common phrases")
        loadSupplementaryDict("dict/person_names.tsv", "person names")
        loadSupplementaryDict("dict/emoji_kaomoji.tsv", "emoji/kaomoji")
        loadSupplementaryDict("dict/symbols.tsv", "symbols")
        loadSupplementaryDict("dict/mobile_tech.tsv", "mobile tech")

        // (No person-name cost boost: the old -1500 on POS ids 1921..1923
        // predates the lattice engine and skewed Mozc's own name costs —
        // 齋藤 above 斎藤. Benchmark: names top-1 72.7% → 77.3% without it.)

        // Sort all entries and build index ONCE after all dicts loaded (avoids OOM from repeated sorts)
        for (entries in dict.values) {
            entries.sortBy { it.cost }
        }
        sortedReadings = dict.keys.toTypedArray().also { it.sort() }
        Log.i("NacreDictionary", "Index built: ${dict.size} readings, ${sortedReadings.size} sorted")

        loadEnglishDict()
        loadLoanwords()
        buildRomajiEnglishIndex()
        loadEnglishFullDict()
        loadStaticBigrams()
    }

    // --- Dictionary snapshot: fast restore of the static content ---
    //
    // The snapshot serializes ONLY the asset-derived structures (main dict,
    // connection matrix, sorted indexes, English dicts, static bigrams) in
    // their post-parse state (person-name boost applied, per-reading lists
    // sorted). Maps are written in live iteration order and read back into
    // maps with the same initial capacities in the same insertion order, so
    // even iteration-order-sensitive consumers (romajiEnglishMatch scans
    // romajiEnglishIndex) observe identical behavior. Learning/user state is
    // never snapshotted.

    /**
     * Identity of the content a snapshot was built from. The bundled
     * dictionary assets only ever change with the app itself, so the app
     * version code (monotonic across releases: 100 + CI run number) plus the
     * snapshot format version is a complete invalidation key.
     */
    private fun snapshotKey(): String {
        val appVersion = try {
            @Suppress("DEPRECATION")
            androidContext?.packageManager?.getPackageInfo(androidContext.packageName, 0)?.versionCode?.toString()
        } catch (e: Exception) {
            null
        } ?: "dev"
        return "fmt$SNAPSHOT_FORMAT_VERSION-app$appVersion"
    }

    private fun writeSnapshotQuietly() {
        val dir = snapshotDir ?: return
        try {
            dir.mkdirs()
            val tmp = File(dir, "$SNAPSHOT_FILE_NAME.tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { out ->
                out.writeInt(SNAPSHOT_MAGIC)
                out.writeInt(SNAPSHOT_FORMAT_VERSION)
                writeSnapshotString(out, snapshotKey())
                out.writeInt(numIds)
                val matrixBytes = ByteBuffer.allocate(connectionCostFlat.size * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                matrixBytes.asShortBuffer().put(connectionCostFlat)
                out.writeInt(connectionCostFlat.size)
                out.write(matrixBytes.array())
                out.writeInt(entryCount)
                writeEntryMap(out, dict)
                out.writeInt(sortedReadings.size)
                for (r in sortedReadings) writeSnapshotString(out, r)
                writeEntryMap(out, englishDict)
                writeEntryMap(out, romajiEnglishIndex)
                writeEntryMap(out, englishFullDict)
                out.writeInt(englishSortedKeys.size)
                for (k in englishSortedKeys) writeSnapshotString(out, k)
                out.writeInt(staticBigrams.size)
                for ((k, v) in staticBigrams) {
                    writeSnapshotString(out, k)
                    out.writeInt(v)
                }
                // Trailer: total dict entry count, validated on restore.
                out.writeInt(dict.values.sumOf { l -> l.sumOf { 1 + (it.variants?.size ?: 0) } })
            }
            val target = File(dir, SNAPSHOT_FILE_NAME)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            Log.i("NacreDictionary", "Dictionary snapshot written (${target.length() / 1024}KB)")
        } catch (e: Exception) {
            // A failed snapshot write must never fail the load itself.
            Log.w("NacreDictionary", "Dictionary snapshot write failed (ignored)", e)
        }
    }

    private fun tryLoadSnapshot(): Boolean {
        val dir = snapshotDir ?: return false
        val file = File(dir, SNAPSHOT_FILE_NAME)
        if (!file.isFile) return false
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { inp ->
                if (inp.readInt() != SNAPSHOT_MAGIC) return false
                if (inp.readInt() != SNAPSHOT_FORMAT_VERSION) return false
                if (readSnapshotString(inp) != snapshotKey()) return false
                val ids = inp.readInt()
                if (ids <= 0 || ids > 100_000) return false
                val flatLen = inp.readInt()
                if (flatLen != ids * ids) return false
                val flatBytes = ByteArray(flatLen * 2)
                inp.readFully(flatBytes)
                val flat = ShortArray(flatLen)
                ByteBuffer.wrap(flatBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(flat)
                val entries = inp.readInt()
                val newDict = readEntryMap(inp, 400000)
                val newSorted = Array(inp.readInt()) { readSnapshotString(inp) }
                if (newSorted.size != newDict.size) return false
                val newEnglish = readEntryMap(inp, 5000)
                val newRomajiIndex = readEntryMap(inp, 500)
                val newEnglishFull = readEntryMap(inp, 25000)
                val newEnglishKeys = Array(inp.readInt()) { readSnapshotString(inp) }
                if (newEnglishKeys.size != newEnglishFull.size) return false
                val bigramCount = inp.readInt()
                if (bigramCount < 0 || bigramCount > 1_000_000) return false
                val newBigrams = HashMap<String, Int>(2000)
                repeat(bigramCount) { newBigrams[readSnapshotString(inp)] = inp.readInt() }
                if (inp.readInt() != newDict.values.sumOf { l -> l.sumOf { 1 + (it.variants?.size ?: 0) } }) return false

                // All validation passed — publish into the live fields.
                numIds = ids
                connectionCostFlat = flat
                entryCount = entries
                dict.clear()
                dict.putAll(newDict)
                sortedReadings = newSorted
                englishDict.clear()
                englishDict.putAll(newEnglish)
                romajiEnglishIndex.clear()
                romajiEnglishIndex.putAll(newRomajiIndex)
                englishFullDict.clear()
                englishFullDict.putAll(newEnglishFull)
                englishSortedKeys = newEnglishKeys
                staticBigrams.clear()
                staticBigrams.putAll(newBigrams)
                Log.i(
                    "NacreDictionary",
                    "Dictionary restored from snapshot: ${newDict.size} readings, $entries entries",
                )
                true
            }
        } catch (e: Exception) {
            Log.w("NacreDictionary", "Dictionary snapshot unreadable — falling back to full parse", e)
            false
        }
    }

    private fun writeEntryMap(out: DataOutputStream, map: Map<String, List<DictEntry>>) {
        out.writeInt(map.size)
        for ((reading, entries) in map) {
            writeSnapshotString(out, reading)
            out.writeInt(entries.size)
            for (e in entries) {
                writeSnapshotString(out, e.surface)
                out.writeInt(e.cost)
                out.writeInt(e.leftGroup)
                out.writeInt(e.rightGroup)
                val variants = e.variants
                out.writeInt(variants?.size ?: 0)
                if (variants != null) for (v in variants) {
                    out.writeInt(v.cost)
                    out.writeInt(v.leftGroup)
                    out.writeInt(v.rightGroup)
                }
            }
        }
    }

    private fun readEntryMap(inp: DataInputStream, initialCapacity: Int): HashMap<String, MutableList<DictEntry>> {
        val readingCount = inp.readInt()
        if (readingCount < 0 || readingCount > 10_000_000) throw IOException("bad reading count $readingCount")
        val map = HashMap<String, MutableList<DictEntry>>(initialCapacity)
        repeat(readingCount) {
            val reading = readSnapshotString(inp)
            val n = inp.readInt()
            if (n < 0 || n > 100_000) throw IOException("bad entry count $n")
            val list = ArrayList<DictEntry>(n)
            repeat(n) {
                val surface = readSnapshotString(inp)
                val cost = inp.readInt()
                val left = inp.readInt()
                val right = inp.readInt()
                val vn = inp.readInt()
                if (vn < 0 || vn > 10_000) throw IOException("bad variant count $vn")
                val variants = if (vn == 0) null else Array(vn) {
                    DictEntry(surface, inp.readInt(), inp.readInt(), inp.readInt())
                }
                list.add(DictEntry(surface, cost, left, right, variants))
            }
            map[reading] = list
        }
        return map
    }

    private fun writeSnapshotString(out: DataOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readSnapshotString(inp: DataInputStream): String {
        val len = inp.readInt()
        if (len < 0 || len > MAX_SNAPSHOT_STRING_BYTES) throw IOException("bad string length $len")
        val bytes = ByteArray(len)
        inp.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun loadConnectionMatrix() {
        try {
            assetOpener.open("dict/connection.bin").use { stream ->
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
                Log.i("NacreDictionary", "Connection matrix loaded: ${numIds}x${numIds} (${total * 2 / 1024}KB)")
            }
        } catch (e: Exception) {
            Log.e("NacreDictionary", "Failed to load binary connection matrix, trying TSV fallback", e)
            loadConnectionMatrixTsvFallback()
        }
    }

    private fun loadConnectionMatrixTsvFallback() {
        try {
            val rows = mutableListOf<IntArray>()
            var n = 14
            assetOpener.open("dict/connection_group.tsv").use { stream ->
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
            Log.i("NacreDictionary", "Connection matrix (TSV fallback): ${n}x${n}")
        } catch (e2: Exception) {
            Log.e("NacreDictionary", "TSV fallback also failed", e2)
            numIds = 0
            connectionCostFlat = ShortArray(0)
        }
    }

    private fun loadMozcDictionary() {
        try {
            // Try gzip binary first, fall back to plain TSV
            val stream = try {
                GZIPInputStream(assetOpener.open("dict/mozc_dict.bin"))
            } catch (_: Exception) {
                assetOpener.open("dict/mozc_dict.tsv")
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
                            val entries = dict.getOrPut(reading) { mutableListOf() }
                            val same = entries.firstOrNull { it.surface == surface }
                            if (same == null) {
                                entries.add(DictEntry(surface, cost, leftGroup, rightGroup))
                            } else {
                                // POS variant of a surface already listed (see
                                // DictEntry.variants). The cheapest reading stays the
                                // primary entry — what per-surface consumers always saw.
                                // The surface string instance is shared (heap).
                                val variant = DictEntry(same.surface, cost, leftGroup, rightGroup)
                                val others = same.variants ?: emptyArray()
                                entries[entries.indexOf(same)] = if (cost < same.cost) {
                                    variant.copy(variants = others + same.copy(variants = null))
                                } else {
                                    same.copy(variants = others + variant)
                                }
                            }
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
                    Log.i("NacreDictionary", "Dictionary loaded: $count entries, ${dict.size} unique readings")
                }
            }
        } catch (e: Exception) {
            Log.e("NacreDictionary", "Failed to load dictionary", e)
        }

        // Sorting deferred to load() after all dicts are loaded
    }

    private fun loadSlangDictionary() {
        try {
            assetOpener.open("dict/slang_words.tsv").use { stream ->
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
                    Log.i("NacreDictionary", "Slang dict loaded: $count new entries")
                }
            }
            // Sorting deferred to load()
        } catch (e: Exception) {
            Log.i("NacreDictionary", "No slang dictionary found (optional)")
        }
    }

    /**
     * Load a supplementary TSV dictionary (emoji, symbols, etc).
     * Format: reading\tsurface\tleft_id\tright_id\tcost
     */
    private fun loadSupplementaryDict(assetPath: String, label: String) {
        try {
            assetOpener.open(assetPath).use { stream ->
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
                    Log.i("NacreDictionary", "$label dict loaded: $count entries")
                }
            }
        } catch (e: Exception) {
            Log.i("NacreDictionary", "No $label dictionary found (optional)")
        }
    }

    // --- DictionaryProvider implementation ---

    // #13 native Mozc engine — lazily created on first use, gated by a setting.
    private val mozcEngine by lazy { NacreMozcEngine(androidContext!!) }

    // Shelly Bridge — ephemeral terminal context (cwd/repo/branch/technical terms) shared by
    // the Shelly Android terminal IDE while it's the foreground app. Lazily created so it costs
    // nothing (no file stat) until predictEnglish() actually consults it. Independently
    // re-sanitizes everything it reads — see ShellyBridgeContext.kt for the full contract.
    private val shellyBridge: ShellyBridgeReader? by lazy {
        androidContext?.let { ShellyBridgeReader(it) }
    }

    // #13/M5: single background thread for NacreMozcEngine.learn() — a commit-time
    // SUBMIT_CANDIDATE round-trip through the native session, same per-char JNI cost as
    // convert()/predict(), so it must never run on the caller's thread (recordSelection() is
    // invoked directly from the IME's key-handling path). Lazily created so it costs nothing
    // when Mozc-native is off. Daemon thread: never blocks process shutdown.
    private val mozcLearnExecutor by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "NacreMozcLearn").apply { isDaemon = true }
        }
    }

    // Public so the input engine + service can skip the now-redundant Kotlin-engine
    // helpers (KenLM 5-gram rescoring, the local-LLM reranker) when native Mozc is the
    // active conversion path — Mozc never consults them, so loading/running them just
    // wastes ~560MB (KenLM) + per-keystroke LLM calls.
    fun useMozcNative(): Boolean =
        storeProvider("nacre_mozc").getBoolean("enabled", false)

    override fun convert(kana: String): List<ConversionCandidate> {
        if (!loaded || kana.isEmpty()) return emptyList()

        // #13: Mozc is intentionally NOT called here — convert() runs on the main thread
        // (explicit 変換), and Mozc's per-char JNI batch would risk an ANR. Mozc drives the
        // live candidate bar via predict() (background thread) instead. (Agent review B1.)

        val sorted = rankedConversions(kana).toMutableList()
        val seen = sorted.mapTo(HashSet()) { it.surface }
        fun append(c: ConversionCandidate) { if (seen.add(c.surface)) sorted.add(c) }

        // Exact single-word entries the N-best did not reach (rare readings).
        for (c in exactMatch(kana)) append(c)
        // English loanword candidates (こんぴゅーたー → "computer").
        for (c in englishMatch(kana, limit = 5)) append(c)
        // Katakana / half-width katakana / hiragana as-is.
        val katakana = hiraganaToKatakana(kana)
        if (katakana != kana) append(ConversionCandidate(surface = katakana, reading = kana))
        if (kana.length >= 2) {
            val half = toHalfWidthKatakana(kana)
            if (half != kana && half != katakana) append(ConversionCandidate(surface = half, reading = kana))
        }
        append(ConversionCandidate(surface = kana, reading = kana))

        rankLearnedFirst(sorted, kana)
        promoteLoanwordCandidate(sorted, kana)
        return sorted.take(if (kana.length <= 3) 40 else 30)
    }

    /**
     * Completion candidates for [kana]: dictionary words whose reading
     * extends it, priced as one-word lattice paths plus a per-extra-kana
     * penalty, so they compete with the conversions of the typed reading
     * on the same scale (ありが → ありがとう above 有賀). The scan is bounded
     * for one- and two-kana prefixes, whose reading ranges are huge.
     */
    private fun completions(kana: String): List<ConversionCandidate> {
        if (kana.length < COMPLETION_MIN_KANA) return emptyList()
        var idx = sortedReadings.binarySearch(kana).let { if (it >= 0) it + 1 else -(it + 1) }
        val best = java.util.PriorityQueue<ConversionCandidate>(compareByDescending { it.cost })
        var scanned = 0
        while (idx < sortedReadings.size && scanned < COMPLETION_MAX_READINGS) {
            val reading = sortedReadings[idx++]
            if (!reading.startsWith(kana)) break
            scanned++
            val extra = reading.length - kana.length
            if (extra > COMPLETION_MAX_EXTRA_KANA) continue
            val entries = dict[reading] ?: continue
            for (e in entries.take(2)) {
                if (isLatticeSymbolSurface(e.surface)) continue
                val path = rawConnectionCost(lastRightGroup, e.leftGroup) + e.cost + rawConnectionCost(e.rightGroup, 0) + eosPenalty(e.rightGroup)
                val cost = applyBoost(reading, e.surface, path) + COMPLETION_BASE_PENALTY + extra * COMPLETION_PER_KANA_PENALTY
                best.add(ConversionCandidate(surface = e.surface, reading = reading, cost = cost))
                if (best.size > COMPLETION_LIMIT) best.poll()
            }
        }
        return best.sortedBy { it.cost }
    }

    /**
     * Selection learning as a stable re-rank (the same rule the Mozc-native
     * path uses): surfaces the user picked for this reading move up by their
     * learning boost, most-boosted first; everything unlearned keeps its
     * order. The cost pool already carries the boost, but the candidates
     * appended after it (English, katakana, the reading as-is) do not, and
     * a learned pick must win there too.
     */
    private fun rankLearnedFirst(list: MutableList<ConversionCandidate>, kana: String) {
        if (list.size < 2) return
        val boosts = list.map { learningBoostFor(kana, it.surface) }
        if (boosts.all { it == 0 }) return
        val order = list.indices.sortedByDescending { boosts[it] }
        val reordered = order.map { list[it] }
        list.clear(); list.addAll(reordered)
    }

    /**
     * The conversion ranking shared by [convert] and [predict]: N-best
     * lattice paths for the reading (Mozc word + connection costs), the
     * same for the long-vowel-normalized reading (romaji-typed loanwords,
     * こんぴゅうたあ → コンピューター) and for kana variants (を→お …),
     * each re-scored with the whole-reading learning boost and KenLM, in
     * ascending cost order. Candidates keep the TYPED reading so learning
     * and history stay keyed by what the user entered.
     */
    private fun rankedConversions(kana: String): List<ConversionCandidate> {
        val pool = LinkedHashMap<String, ConversionCandidate>()
        fun offer(c: ConversionCandidate) {
            val prev = pool[c.surface]
            if (prev == null || c.cost < prev.cost) pool[c.surface] = c
        }
        for (c in latticeConvert(kana)) offer(c)
        // Every whole-reading dictionary word, also those the N-best did
        // not reach, priced as a one-word path so it is comparable (and
        // learnable) on the same scale.
        var typedWordBest = Int.MAX_VALUE
        dict[kana].orEmpty().flatMap { listOf(it) + it.variants.orEmpty() }.forEach { e ->
            val cost = rawConnectionCost(lastRightGroup, e.leftGroup) + e.cost + rawConnectionCost(e.rightGroup, 0) + eosPenalty(e.rightGroup)
            if (cost < typedWordBest) typedWordBest = cost
            offer(ConversionCandidate(surface = e.surface, reading = kana, cost = cost, segments = listOf(e.surface), rightId = e.rightGroup))
        }
        // Readings derived from the typed one (long-vowel ー spelling, kana
        // variants) never outrank the typed reading's own best dictionary
        // word: くせい stays 区政 although くせー is cheaper. When the typed
        // reading has no word of its own (こんぴゅうたあ) there is no floor.
        fun derived(c: ConversionCandidate, penalty: Int): ConversionCandidate {
            var cost = c.cost + penalty
            if (typedWordBest != Int.MAX_VALUE && cost <= typedWordBest) cost = typedWordBest + 1
            return c.copy(reading = kana, cost = cost)
        }
        val normalized = normalizeLongVowelReading(kana)
        if (normalized != kana) {
            for (c in latticeConvert(normalized).take(LATTICE_VARIANT_TAKE)) {
                offer(derived(c, LONG_VOWEL_NORMALIZED_PENALTY))
            }
        }
        if (kana.any { it == 'を' || it == 'ぢ' || it == 'づ' || it == 'ヂ' || it == 'ヅ' || it == 'ヲ' }) {
            for (variant in generateKanaVariants(kana)) {
                for (c in latticeConvert(variant).take(LATTICE_VARIANT_TAKE)) {
                    offer(derived(c, KANA_VARIANT_PENALTY))
                }
            }
        }
        // Surfaces the user committed for exactly this reading stay
        // candidates even when the lattice no longer reaches them (a
        // multi-word pick, or a new left context after the commit); the
        // learning re-rank then decides where they go.
        val floor = pool.values.minOfOrNull { it.cost } ?: 0
        for (h in recentHistory) {
            if (h.reading == kana && h.surface !in pool) offer(h.copy(cost = floor))
        }
        val boosted = pool.values.map { c -> c.copy(cost = applyBoost(kana, c.surface, c.cost)) }
            .sortedBy { it.cost }.toMutableList()
        kenLmRescore(boosted)
        return boosted.sortedBy { it.cost }
    }

    /**
     * Guarantee the English loanword candidate for an exact reading match
     * (e.g. "computer" for こんぴゅーたー) a top-5 slot. Cost alone cannot do
     * this: loanword readings attract long tails of emoji/compound entries
     * that sort above any sane English cost, which left the English
     * candidate at rank 10+ (effectively buried). Only the single best
     * exact-match English candidate is promoted, and never to rank 1, so
     * the Japanese candidates keep their relative order and the primary
     * Japanese surface stays on top.
     */
    private fun promoteLoanwordCandidate(sorted: MutableList<ConversionCandidate>, kana: String) {
        if (kana.length < 2) return
        // Same key set as englishMatch: a candidate that arrived via the
        // long-vowel-normalized key (romaji-typed loanword) must be
        // promotable too, not just the raw reading's own entries.
        val surfaces = englishLookupKeys(kana)
            .flatMap { englishDict[it].orEmpty() }
            .map { it.surface }
            .toSet()
        if (surfaces.isEmpty()) return
        val idx = sorted.indexOfFirst { it.surface in surfaces }
        // When the top candidate is itself a katakana word, the reading is
        // a loanword and the English spelling is its closest alternative:
        // slot it right after the katakana (rank 2). The long tail of
        // emoji that loanword readings attract (⌨️ 💻 💽 … for
        // こんぴゅーたー) used to sit in front of it, and at rank 5 the
        // English candidate was past the visible width of the candidate
        // bar — on device it simply looked absent (build-480, A-2).
        // Otherwise (a native word that happens to have a loanword entry)
        // keep the conservative slot so Japanese candidates stay first.
        val target = if (sorted.firstOrNull()?.surface?.let(::isKatakanaWord) == true) {
            LOANWORD_PROMOTE_INDEX_KATAKANA_TOP
        } else {
            LOANWORD_PROMOTE_INDEX
        }
        if (idx > target) {
            val c = sorted.removeAt(idx)
            sorted.add(target, c)
        }
    }

    /**
     * Recent conversion-history hits for [kana] (exact or prefix), with a length-proportional
     * cost penalty on prefix matches so history doesn't dominate over an actually-typed longer
     * reading. Shared by both the legacy-engine and Mozc-native branches of predict() so a
     * recently-committed string surfaces again regardless of which conversion engine is active.
     */
    private fun historyMatchesFor(kana: String): List<ConversionCandidate> {
        return recentHistory.filter {
            it.reading == kana || it.reading.startsWith(kana)
        }.map { h ->
            if (h.reading == kana) {
                h // Exact match — no penalty
            } else {
                // Prefix match: penalize proportionally to how much is unmatched.
                // Short input (1-2 chars): high penalty to avoid history domination
                // Longer input (3+ chars): lower penalty since it's more likely intentional
                val unmatched = h.reading.length - kana.length
                val perCharPenalty = if (kana.length <= 2) 2000 else 1200
                h.copy(cost = h.cost + unmatched * perCharPenalty)
            }
        }.take(3)
    }

    override fun predict(kana: String, romaji: String): List<ConversionCandidate> {
        if (!loaded || kana.isEmpty()) return emptyList()

        // Memoized: within a composing session the same (kana, romaji) recurs
        // constantly (backspace-and-retype, flick toggle cycling, prediction
        // refreshes after layer switches), and the full pipeline below
        // (Viterbi + KenLM + prefix/phrase/English passes) is the IME's
        // per-keystroke hot path. A cached result is reused only while every
        // input it depended on is unchanged: all learning-state mutations
        // clear the cache via bumpPredictStateVersion(), and the Mozc toggle
        // (read live from the store) is part of the key. Callers never mutate
        // the returned list (InputEngine copies it into its candidate state;
        // LlmReranker copies before reordering), so sharing the instance is
        // safe.
        val key = PredictCacheKey(kana, romaji, useMozcNative())
        val generation: Long
        synchronized(predictCacheLock) {
            predictCache[key]?.let { return it }
            generation = predictStateGeneration
        }
        val result = predictInternal(kana, romaji)
        // Cache only if no invalidation landed while computing: a result
        // derived from pre-invalidation learning state (e.g. a Mozc
        // ranking captured just before a learning clear completed) must
        // never be memoized after the fact, or the stale ranking would
        // keep resurfacing on later cache hits.
        synchronized(predictCacheLock) {
            if (generation == predictStateGeneration) predictCache[key] = result
        }
        return result
    }

    private fun predictInternal(kana: String, romaji: String): List<ConversionCandidate> {
        // #13: native Mozc drives the live candidate bar (off the main thread — predict()
        // is dispatched on Dispatchers.Default). Prefer the raw romaji buffer (ASCII → the
        // romaji table composes kana); fall back to the kana reading via AS_IS.
        if (useMozcNative()) {
            // NacreMozcEngine.convert() stamps reading = the exact string it composed with
            // (the raw romaji buffer when typing QWERTY, since that's what's passed below) —
            // NOT the kana reading. recordSelection() persists candidate.reading verbatim into
            // recentHistory, so leaving it as romaji would silently break every future
            // kana-keyed history lookup for anything typed via romaji (the common case).
            // Normalize back to kana immediately so history storage/lookup stay consistent
            // regardless of which raw buffer Mozc was actually driven with.
            val mozc = mozcEngine.convert(romaji.ifBlank { kana }).map { it.copy(reading = kana) }
            if (mozc.isNotEmpty()) {
                // Mozc has its own (session-local) history via learn()/SUBMIT_CANDIDATE, but
                // that never surfaces Nacre's own longer-lived recentHistory. Prepend Nacre's
                // history hits the same way the legacy engine's own path (section 0 below) does,
                // so recently-converted text still resurfaces regardless of which engine is active.
                val historyMatches = historyMatchesFor(kana)
                val seen = mutableSetOf<String>()
                val merged = ArrayList<ConversionCandidate>(historyMatches.size + mozc.size + 5)
                for (h in historyMatches) if (seen.add(h.surface)) merged.add(h)
                // Mozc candidates carry no cost (their list order IS
                // Mozc's ranking), so the legacy path's cost-based boost
                // never reached them — on the Mozc path the only Nacre
                // learning signal was the history prepend above, which is
                // why learned surfaces felt weak there. Apply the same
                // selection-learning signal as a stable re-rank: learned
                // surfaces bubble up by their boost, unlearned ones keep
                // Mozc's relative order exactly (sortedByDescending is
                // stable, and equal boosts compare equal).
                val rankedMozc = mozc.sortedByDescending { learningBoostFor(kana, it.surface) }
                for (c in rankedMozc) if (seen.add(c.surface)) merged.add(c)
                // English loanword candidates are Nacre-side data Mozc knows
                // nothing about; merge them here too so the live candidate
                // bar offers e.g. "computer" for こんぴゅーたー on this path
                // as well, with the same top-5 promotion as the legacy path.
                for (c in englishMatch(kana, limit = 5)) {
                    if (seen.add(c.surface)) merged.add(c)
                }
                promoteLoanwordCandidate(merged, kana)
                return merged
            }
        }

        // One cost scale for everything below: lattice path costs (Mozc
        // word + connection costs) with the learning boost applied.
        val pool = LinkedHashMap<String, ConversionCandidate>()
        fun offer(c: ConversionCandidate) {
            val prev = pool[c.surface]
            if (prev == null || c.cost < prev.cost) pool[c.surface] = c
        }
        // Conversions of what was typed.
        for (c in rankedConversions(kana).take(PREDICT_CONVERSIONS)) offer(c)
        // Completions: words whose reading extends what was typed.
        for (c in completions(kana)) offer(c)
        // Recent history (exact or prefix): what the user committed lately.
        for (h in historyMatchesFor(kana)) offer(h)

        val sorted = pool.values.sortedBy { it.cost }.toMutableList()
        val seen = sorted.mapTo(HashSet()) { it.surface }
        fun append(c: ConversionCandidate) { if (seen.add(c.surface)) sorted.add(c) }
        for (c in englishMatch(kana, limit = 5)) append(c)
        if (romaji.length >= 2) for (c in romajiEnglishMatch(romaji, limit = 5)) append(c)
        if (sorted.size < 4 && kana.length >= 3) for (c in typoCorrection(kana, limit = 3)) append(c)
        if (kana.length >= 2) {
            val katakana = hiraganaToKatakana(kana)
            if (katakana != kana) append(ConversionCandidate(surface = katakana, reading = kana))
            val half = toHalfWidthKatakana(kana)
            if (half != kana && half != katakana) append(ConversionCandidate(surface = half, reading = kana))
            append(ConversionCandidate(surface = kana, reading = kana))
        }
        rankLearnedFirst(sorted, kana)
        promoteLoanwordCandidate(sorted, kana)
        return sorted.take(if (kana.length <= 3) 40 else 25)
    }

    override fun recordSelection(candidate: ConversionCandidate) {
        bumpPredictStateVersion()
        val key = "${candidate.reading}:${candidate.surface}"
        userBoost[key] = (userBoost[key] ?: 0) + 1

        // Selection learning with recency: same selection, recorded with
        // a global sequence number so applyBoost() can weigh how recent
        // this pick was against competing surfaces for the same reading.
        ConversionLearning.record(
            conversionLearning, candidate.reading, candidate.surface,
            learningSeq.incrementAndGet(),
        )

        // Bigram
        if (lastCommittedSurface.isNotEmpty()) {
            val bigramKey = "$lastCommittedSurface→$key"
            bigramBoost[bigramKey] = (bigramBoost[bigramKey] ?: 0) + 1
        }
        // Trigram
        if (secondLastCommittedSurface.isNotEmpty() && lastCommittedSurface.isNotEmpty()) {
            val trigramKey = "$secondLastCommittedSurface→$lastCommittedSurface→$key"
            trigramBoost[trigramKey] = (trigramBoost[trigramKey] ?: 0) + 1
        }
        committedContext.addFirst(candidate.surface)
        while (committedContext.size > 4) committedContext.removeLast()

        // Update POS context from the selected candidate's dictionary entry
        lastRightGroup = if (candidate.rightId >= 0) {
            candidate.rightId
        } else {
            val entries = dict[candidate.reading]
            entries?.firstOrNull { it.surface == candidate.surface }?.rightGroup ?: 1
        }

        // Recent history
        recentHistory.removeAll { it.surface == candidate.surface && it.reading == candidate.reading }
        // Store with a low cost so history candidates rank near the top.
        // The user explicitly selected this, so it should strongly influence future predictions.
        val historyCost = minOf(candidate.cost, 2000).coerceAtLeast(300)
        recentHistory.addFirst(candidate.copy(cost = historyCost))
        while (recentHistory.size > maxHistory) recentHistory.removeLast()

        // An English word committed from the Japanese candidate bar (e.g. a
        // loanword spelling like "computer" for こんぴゅーたー) is also an
        // alphabet commit: feed it to the English history so it resurfaces
        // for alphabet prefixes too, not only for its kana reading above.
        // No-op for non-alphabet surfaces (EnglishHistory.record filters).
        synchronized(englishHistoryLock) {
            EnglishHistory.record(recentEnglishHistory, candidate.surface, maxHistory)
        }

        debouncedSave()

        // #13/M5: feed the choice back into Mozc's own history/learning so its ranking
        // improves for this user too — otherwise native-Mozc conversion history never leaves
        // "cold start" no matter how long the app is used. Async: this reading is composed
        // through Mozc char-by-char, same per-key JNI cost as convert()/predict(), which must
        // not run on this (caller's) thread. Reading/surface only — no candidate object or
        // mutable state crosses the thread boundary.
        //
        // Known residual risk (Codex review): `candidate` here isn't guaranteed to have come
        // from Mozc — it could be a recentHistory hit, a legacy-engine candidate, or a user
        // dictionary entry. learn()'s suffix-stripped match against Mozc's own candidate list
        // is normally a safe no-op for those (see learn()'s doc comment), but a coincidental
        // full-string match could in principle submit the wrong candidate id. Not mitigated
        // here — would need per-candidate engine provenance tracking to fix properly.
        if (useMozcNative()) {
            val reading = candidate.reading
            val surface = candidate.surface
            mozcLearnExecutor.execute {
                runCatching { mozcEngine.learn(reading, surface) }
                    .onFailure { Log.e("NacreDictionary", "mozcEngine.learn failed", it) }
                // Mozc's session learning just changed what the Mozc path of
                // predict() would return — invalidate memoized results.
                bumpPredictStateVersion()
            }
        }
    }

    fun updateContext(surface: String) {
        committedContext.addFirst(surface)
        while (committedContext.size > 4) committedContext.removeLast()
        lastRightGroup = 0 // BOS/EOS for raw text
        bumpPredictStateVersion()
    }

    /**
     * Predict next word based on committed context.
     * Uses trigram/bigram history, static bigrams, POS-based common followers,
     * and KenLM scoring for ranking.
     */
    fun predictNextWord(limit: Int = 8): List<ConversionCandidate> {
        if (lastCommittedSurface.isEmpty()) return emptyList()

        val results = mutableListOf<ConversionCandidate>()
        val seen = mutableSetOf<String>()

        // 1. Trigram matches (strongest signal): prev2→prev1→X
        if (secondLastCommittedSurface.isNotEmpty()) {
            val triPrefix = "$secondLastCommittedSurface→$lastCommittedSurface→"
            for ((key, count) in trigramBoost) {
                if (key.startsWith(triPrefix) && count > 0) {
                    val target = key.removePrefix(triPrefix)
                    val parts = target.split(':', limit = 2)
                    if (parts.size == 2) {
                        val surface = parts[1]
                        if (seen.add(surface)) {
                            results.add(ConversionCandidate(
                                surface = surface,
                                reading = parts[0],
                                cost = maxOf(0, 3000 - count * 1000),
                            ))
                        }
                    }
                }
            }
        }

        // 2. Bigram matches (user learned): prev1→X
        val biPrefix = "$lastCommittedSurface→"
        for ((key, count) in bigramBoost) {
            if (key.startsWith(biPrefix) && count > 0) {
                val target = key.removePrefix(biPrefix)
                val parts = target.split(':', limit = 2)
                if (parts.size == 2) {
                    val surface = parts[1]
                    if (seen.add(surface)) {
                        results.add(ConversionCandidate(
                            surface = surface,
                            reading = parts[0],
                            cost = maxOf(0, 4000 - count * 800),
                        ))
                    }
                }
            }
        }

        // 3. Static bigram matches (common collocations from bigrams.tsv)
        if (results.size < limit) {
            for ((key, boost) in staticBigrams) {
                if (key.startsWith(biPrefix)) {
                    val target = key.removePrefix(biPrefix)
                    val parts = target.split(':', limit = 2)
                    if (parts.size == 2) {
                        val surface = parts[1]
                        if (seen.add(surface)) {
                            results.add(ConversionCandidate(
                                surface = surface,
                                reading = parts[0],
                                cost = maxOf(1000, 5000 - boost),
                            ))
                        }
                    }
                    if (results.size >= limit * 2) break
                }
            }
        }

        // 4. POS-based common followers: if last word was a verb, suggest particles etc.
        if (results.size < limit) {
            val commonFollowers = getCommonFollowersForContext()
            for (follower in commonFollowers) {
                if (seen.add(follower.surface)) {
                    results.add(follower)
                    if (results.size >= limit * 2) break
                }
            }
        }

        // 5. Recent history (fallback)
        if (results.size < limit) {
            for ((index, h) in recentHistory.withIndex()) {
                if (seen.add(h.surface) && h.surface != lastCommittedSurface) {
                    results.add(h.copy(cost = 2200 + index * 140))
                    if (results.size >= limit) break
                }
            }
        }

        // 6. KenLM rescoring for all next-word predictions
        if (results.size > 1) {
            kenLmRescore(results)
        }

        return results.sortedBy { it.cost }.take(limit)
    }

    /**
     * Generate common follow-up word candidates based on POS context.
     * E.g., after a noun suggest particles (は、が、を、の、に、で、と、も、から、まで).
     * After a verb suggest auxiliary verbs and conjunctions.
     */
    private fun getCommonFollowersForContext(): List<ConversionCandidate> {
        val results = mutableListOf<ConversionCandidate>()

        // Common particles/auxiliary patterns based on last word POS
        val isLastNoun = isNoun(lastRightGroup) || lastRightGroup == 0
        val isLastVerb = isVerb(lastRightGroup)
        val isLastAdj = isAdjective(lastRightGroup)
        val isLastParticle = isParticle(lastRightGroup)

        if (isLastNoun) {
            // After noun: particles
            val particles = listOf(
                "は" to "は", "が" to "が", "を" to "を", "の" to "の",
                "に" to "に", "で" to "で", "と" to "と", "も" to "も",
                "から" to "から", "まで" to "まで", "って" to "って",
                "です" to "です", "だ" to "だ",
            )
            for ((reading, surface) in particles) {
                results.add(ConversionCandidate(surface = surface, reading = reading, cost = 4500))
            }
        } else if (isLastVerb || isLastAdj) {
            // After verb/adjective: auxiliaries, conjunctions, nominalizers
            val followers = listOf(
                "こと" to "こと", "の" to "の", "ため" to "ため",
                "ので" to "ので", "から" to "から", "けど" to "けど",
                "が" to "が", "と" to "と", "し" to "し",
                "です" to "です", "ます" to "ます",
                "た" to "た", "ない" to "ない",
                "ている" to "ている", "ていた" to "ていた",
                "てから" to "てから", "ても" to "ても",
                "ように" to "ように", "ようと" to "ようと",
                "ほう" to "方", "とき" to "時",
                "ところ" to "ところ", "ばかり" to "ばかり",
            )
            for ((reading, surface) in followers) {
                results.add(ConversionCandidate(surface = surface, reading = reading, cost = 4800))
            }
        } else if (isLastParticle) {
            // After particle: common verbs/adjectives as fallback
            val commonVerbs = listOf(
                "する" to "する", "なる" to "なる", "ある" to "ある",
                "いる" to "いる", "できる" to "できる", "思う" to "思う",
                "言う" to "言う", "見る" to "見る", "行く" to "行く",
                "来る" to "来る", "使う" to "使う", "知る" to "知る",
                "分かる" to "分かる", "考える" to "考える", "作る" to "作る",
                "いい" to "いい", "ない" to "ない", "多い" to "多い",
                "必要" to "必要", "大丈夫" to "大丈夫",
            )
            for ((reading, surface) in commonVerbs) {
                results.add(ConversionCandidate(surface = surface, reading = reading, cost = 5000))
            }
            // Also use recent history for personalized predictions
            for (h in recentHistory) {
                val entries = dict[h.reading]
                val entry = entries?.firstOrNull { it.surface == h.surface }
                if (entry != null && isContentWord(entry.leftGroup)) {
                    results.add(h.copy(cost = 4800))
                    if (results.size >= 25) break
                }
            }
        } else if (isAuxVerb(lastRightGroup)) {
            // After auxiliary verb (ます/です/た/etc.): sentence connectors
            val followers = listOf(
                "が" to "が", "けど" to "けど", "ので" to "ので",
                "から" to "から", "し" to "し", "ね" to "ね",
                "よ" to "よ", "よね" to "よね",
            )
            for ((reading, surface) in followers) {
                results.add(ConversionCandidate(surface = surface, reading = reading, cost = 4500))
            }
        }

        return results
    }

    private fun debouncedSave() {
        val now = System.currentTimeMillis()
        if (now - lastSaveTime > 5000) {
            lastSaveTime = now
            saveUserBoost()
        } else {
            savePending = true
        }
    }

    fun flushPendingSave() {
        if (savePending) {
            saveUserBoost()
            savePending = false
        }
    }

    /**
     * How many times the user has selected [surface] for [reading]
     * (effective count across the legacy counter and the recency store).
     * O(1); safe to call from candidate-insertion paths.
     */
    fun selectionCountFor(reading: String, surface: String): Int =
        ConversionLearning.effectiveCount(
            userBoost["$reading:$surface"] ?: 0,
            conversionLearning[ConversionLearning.key(reading, surface)],
        )

    /**
     * The selection-learning boost for one candidate, without any base
     * cost: frequency + recency (ConversionLearning) plus the recent-
     * history position boost. Used where candidates arrive pre-ranked
     * with no meaningful cost of their own (the Mozc-native branch of
     * predict()). O(1) map lookups plus a bounded scan of the ≤200-entry
     * recent history — the same scan applyBoost() already performs per
     * candidate on the legacy path.
     */
    fun learningBoostFor(reading: String, surface: String): Int =
        ConversionLearning.boost(
            userBoost["$reading:$surface"] ?: 0,
            conversionLearning[ConversionLearning.key(reading, surface)],
            learningSeq.get(),
        ) + recentHistoryBoost(reading, surface)

    /**
     * Erase ALL learned ranking data (selection counts/recency, bigram /
     * trigram boosts, recent history, phrase memory, English history) and
     * persist the cleared state immediately. The registered user
     * dictionary is NOT learning data and is left untouched. Called from
     * the Settings "学習データを消去" action via the service.
     */
    fun clearSelectionLearning() {
        userBoost.clear()
        bigramBoost.clear()
        trigramBoost.clear()
        conversionLearning.clear()
        recentHistory.clear()
        synchronized(englishHistoryLock) { recentEnglishHistory.clear() }
        englishBigramBoost.clear()
        lastCommittedEnglish = ""
        phraseMemory.clear()
        committedContext.clear()
        lastRightGroup = 0
        bumpPredictStateVersion()
        savePhraseMemory()
        saveUserBoost()
        savePending = false

        // Native Mozc keeps its own learned history (fed by learn());
        // erase it too, queued on the learn executor so a pending
        // learn() submission cannot resurrect a just-cleared selection.
        // NOT gated on the Mozc toggle: history learned while Mozc was
        // enabled must not reappear if the user clears while it is
        // disabled and later re-enables it. (androidContext is null only
        // in JVM tests, where no native engine can exist.)
        if (androidContext != null) {
            mozcLearnExecutor.execute {
                runCatching { mozcEngine.clearLearning() }
                    .onFailure { Log.e("NacreDictionary", "mozcEngine.clearLearning failed", it) }
                // The native ranking just changed: predictions memoized
                // while this clear was queued may hold the pre-clear
                // Mozc order — invalidate again now that it has landed
                // (same pattern as the learn() task in recordSelection).
                bumpPredictStateVersion()
            }
        }
    }

    // --- Connection cost ---

    /**
     * Get the POS-based connection cost between two words.
     * Uses the full Mozc 2670×2670 connection cost matrix.
     */
    private fun getConnectionCost(prevRightId: Int, currLeftId: Int): Int {
        if (connectionCostFlat.isEmpty() || numIds == 0) return DEFAULT_CONNECTION_COST
        val r = prevRightId.coerceIn(0, numIds - 1)
        val l = currLeftId.coerceIn(0, numIds - 1)
        val idx = r * numIds + l
        if (idx >= connectionCostFlat.size) return DEFAULT_CONNECTION_COST
        // Connection-cost scale: Mozc's matrix is used at /2, not the original
        // /3 and not full /1. Benchmark-driven (see PR): /3 made boundaries too
        // cheap (over-segmentation); /1 let POS-smooth nonsense paths dominate
        // long inputs in this engine's beam search and cratered top-3. /2 was
        // the measured optimum of the three for overall top-1/top-3.
        return connectionCostFlat[idx].toInt() / 2
    }

    // --- Boost calculation ---

    private fun applyBoost(reading: String, surface: String, baseCost: Int): Int {
        val key = "$reading:$surface"
        val unigramCount = userBoost[key] ?: 0
        val bigramCount = if (lastCommittedSurface.isNotEmpty()) {
            bigramBoost["$lastCommittedSurface→$key"] ?: 0
        } else 0
        val trigramCount = if (secondLastCommittedSurface.isNotEmpty() && lastCommittedSurface.isNotEmpty()) {
            trigramBoost["$secondLastCommittedSurface→$lastCommittedSurface→$key"] ?: 0
        } else 0

        // Static bigram boost (from bigrams.tsv — common Japanese collocations)
        val staticBoostVal = if (lastCommittedSurface.isNotEmpty()) {
            staticBigrams["$lastCommittedSurface→$key"] ?: 0
        } else 0

        // Frequency + recency from selection learning (see
        // ConversionLearning): the frequency table is the one this code
        // always used (1st use 2000 … 5th+ 5500, saturating), and the
        // recency component lets a recently-picked surface outrank an
        // equally-frequent stale one. Bounded at ConversionLearning.MAX_BOOST.
        val unigramBoostVal = ConversionLearning.boost(
            unigramCount,
            conversionLearning[ConversionLearning.key(reading, surface)],
            learningSeq.get(),
        )
        val bigramBoostVal = minOf(bigramCount, 4) * 2500
        val trigramBoostVal = minOf(trigramCount, 3) * 3500
        val recentBoost = recentHistoryBoost(reading, surface)
        val phraseBoost = phraseMemoryBoost(reading, surface)
        val totalBoost = minOf(
            unigramBoostVal + bigramBoostVal + trigramBoostVal + staticBoostVal + recentBoost + phraseBoost,
            22000,
        )
        return maxOf(100, baseCost - totalBoost)
    }

    /**
     * Recent history boost: emphasize the most recently selected candidates.
     * Newest items are at the front of recentHistory.
     */
    private fun recentHistoryBoost(reading: String, surface: String): Int {
        if (recentHistory.isEmpty()) return 0
        for ((index, candidate) in recentHistory.withIndex()) {
            if (candidate.reading == reading && candidate.surface == surface) {
                return when (index) {
                    0 -> 3200
                    1 -> 2600
                    2 -> 2200
                    3, 4 -> 1800
                    5, 6, 7 -> 1300
                    else -> 800
                }
            }
        }
        return 0
    }

    /**
     * Phrase memory boost: reward repeated longer phrases with a recency bias.
     */
    private fun phraseMemoryBoost(reading: String, surface: String): Int {
        val entries = phraseMemory[reading.take(2)] ?: return 0
        val entry = entries.firstOrNull { it.reading == reading && it.surface == surface } ?: return 0
        val age = (learningEpoch - entry.lastEpoch).coerceAtLeast(0)
        val frequency = minOf(entry.count, 10) * 280
        val recency = maxOf(0, 70 - age) * 25
        return minOf(4500, frequency + recency)
    }

    /**
     * POS-aware cost adjustment for a candidate given the current committed context.
     * Rewards natural POS transitions, penalizes unnatural ones.
     *
     * The connection cost matrix (Mozc /3 scaled) has these typical ranges:
     * - Natural transitions (noun→particle, verb→auxiliary): 200-800
     * - Neutral transitions: 800-1200
     * - Unnatural transitions (particle→particle, adj→noun directly): 2000-6000
     *
     * We use a neutral point of 800 and asymmetric scaling:
     * - Good transitions get a bonus (÷2 to avoid over-reliance)
     * - Bad transitions get a stronger penalty (÷1.5) to push them down
     */
    private fun posContextCost(reading: String, surface: String, baseCost: Int): Int {
        if (lastRightGroup == 0) return baseCost
        val entries = dict[reading]
        val entry = entries?.firstOrNull { it.surface == surface } ?: return baseCost
        // This heuristic's neutral point (800) and ranges in the doc comment
        // above were calibrated on the old /3 connection scale. Keep consuming
        // that scale ((x*2)/3 of the new /2 value) so this context adjustment —
        // which the sentence benchmark cannot measure (it runs context-free) —
        // behaves exactly as before this change.
        val connCost = getConnectionCost(lastRightGroup, entry.leftGroup) * 2 / 3
        val delta = connCost - 800
        return if (delta <= 0) {
            // Good transition: mild bonus
            baseCost + delta / 2
        } else {
            // Bad transition: stronger penalty
            baseCost + (delta * 2) / 3
        }
    }

    /**
     * Rescore candidates using KenLM 5-gram language model.
     * Candidates with segments get LM score blended into their cost.
     * Candidates without segments are scored using surface as a single word.
     */
    private fun kenLmRescore(candidates: MutableList<ConversionCandidate>) {
        val scorer = kenLmScorer ?: return
        if (!scorer.isReady() || candidates.isEmpty()) return
        if (candidates.size <= 2) return
        val maxScore = 40.coerceAtMost(candidates.size)

        // Use up to 4 words of context for KenLM 5-gram
        val precedingContext = committedContext.reversed().joinToString(" ")

        val segmentLists = candidates.take(maxScore).map { c ->
            c.segments.ifEmpty { listOf(c.surface) }
        }

        val scores = scorer.scoreBatchNormalized(segmentLists, precedingContext)

        // Dynamic KenLM weight: longer input = more context = higher trust in LM
        // Short (1-3 chars): weight 1800 — dict cost is more reliable
        // Medium (4-7 chars): weight 2500 — balanced
        // Long (8+ chars): weight 3200 — LM context is crucial
        val totalChars = candidates.firstOrNull()?.reading?.length ?: 4
        val hasContext = committedContext.isNotEmpty()
        val dynamicWeight = when {
            // With committed context, even short inputs benefit from LM
            totalChars <= 3 -> if (hasContext) 2200f else 1800f
            totalChars <= 7 -> 2500f
            else -> 3200f
        }

        // Increase weight when we have committed context (cross-sentence scoring)
        // More context words = higher confidence in LM
        val contextMultiplier = when {
            committedContext.size >= 3 -> 1.25f
            committedContext.size >= 2 -> 1.20f
            hasContext -> 1.15f
            else -> 1.0f
        }
        val contextWeight = dynamicWeight * contextMultiplier

        for (i in 0 until maxScore) {
            if (i >= scores.size) break
            // scores[i] is log10 prob (negative; higher = better)
            // Use sqrt normalization to mildly favor longer sequences without over-penalizing
            val wordCount = segmentLists[i].size.coerceAtLeast(1)
            val lmBonus = (scores[i] * -contextWeight / kotlin.math.sqrt(wordCount.toFloat())).toInt()
            candidates[i] = candidates[i].copy(cost = candidates[i].cost + lmBonus)
        }
    }

    // --- Kana variant generation (Google日本語入力方式の読み替え) ---

    /**
     * Generate alternative kana readings for conversion.
     * Handles cases where standard romaji mapping produces one kana but
     * the user may intend another pronunciation.
     *
     * Examples:
     * - ちぇをん → ちぇうぉん (を→うぉ for loanwords like チェウォン)
     * - を → お (を as vowel 'o' in compound words)
     * - ぢ → じ, づ → ず (四つ仮名の読み替え)
     * - ゐ → い, ゑ → え (historical kana)
     */
    private fun generateKanaVariants(kana: String): List<String> {
        val variants = mutableSetOf<String>()

        // を → うぉ replacement (loanword pronunciation)
        if ('を' in kana) {
            variants.add(kana.replace("を", "うぉ"))
            // Also try を → お (common in compound words)
            variants.add(kana.replace("を", "お"))
        }

        // ぢ ↔ じ (四つ仮名)
        if ('ぢ' in kana) {
            variants.add(kana.replace('ぢ', 'じ'))
        }
        if ('じ' in kana && kana.length <= 8) {
            variants.add(kana.replace('じ', 'ぢ'))
        }

        // づ ↔ ず
        if ('づ' in kana) {
            variants.add(kana.replace('づ', 'ず'))
        }
        if ('ず' in kana && kana.length <= 8) {
            variants.add(kana.replace('ず', 'づ'))
        }

        // ゐ → い, ゑ → え (historical)
        if ('ゐ' in kana) variants.add(kana.replace('ゐ', 'い'))
        if ('ゑ' in kana) variants.add(kana.replace('ゑ', 'え'))

        // Remove the original kana from variants
        variants.remove(kana)
        return variants.toList().take(4)  // Limit to avoid explosion
    }

    // --- Internal ---

    private fun exactMatch(kana: String): List<ConversionCandidate> =
        exactMatch(kana, candidateReading = kana, extraCost = 0)

    /**
     * Exact-match candidates looked up under [lookupKey], but recorded
     * against [candidateReading] (selection learning, history and boost
     * lookups all key on it) with [extraCost] added. Used by
     * [longVowelNormalizedMatch], where the dictionary entry lives under
     * the ー spelling while the user typed — and learns — the
     * vowel-doubled spelling. [lookupKey] and [candidateReading] always
     * have the same length there (normalization substitutes one kana
     * for one ー), so the length-based bonus/take counts are identical
     * under either.
     */
    private fun exactMatch(
        lookupKey: String,
        candidateReading: String,
        extraCost: Int,
    ): List<ConversionCandidate> {
        val entries = dict[lookupKey] ?: return emptyList()
        // Exact match bonus: single-word results should beat multi-word Viterbi splits
        // Longer exact matches get bigger bonus (e.g. こんにちは should beat 今日+葉)
        val exactBonus = when {
            lookupKey.length >= 8 -> -7000
            lookupKey.length >= 7 -> -6000
            lookupKey.length >= 5 -> -5000
            lookupKey.length >= 4 -> -3500
            lookupKey.length >= 3 -> -2000
            else -> -1000
        }
        return entries
            .map { entry ->
                var cost = applyBoost(candidateReading, entry.surface, entry.cost) + exactBonus + extraCost
                cost = posContextCost(candidateReading, entry.surface, cost)
                // Suppress function words (助詞・助動詞) appearing as sole candidates for 2+ char input
                if (candidateReading.length >= 2 && isFunctionWord(entry.leftGroup) && entry.surface.length <= 1) {
                    cost += 2000
                }
                ConversionCandidate(
                    surface = entry.surface,
                    reading = candidateReading,
                    cost = cost,
                    segments = listOf(entry.surface),
                )
            }
            .sortedBy { it.cost }
            .take(if (lookupKey.length <= 3) 40 else 20)  // Short readings: show all kanji candidates
    }

    // --- English word matching ---

    private fun loadEnglishDict() {
        try {
            assetOpener.open("dict/english_words.tsv").use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    reader.forEachLine { line ->
                        if (line.isBlank() || line.startsWith('#')) return@forEachLine
                        val parts = line.split('\t')
                        if (parts.size >= 2) {
                            val romaji = parts[0]
                            val english = parts[1]
                            val cost = if (parts.size >= 3) parts[2].toIntOrNull() ?: 8000 else 8000
                            englishDict.getOrPut(romaji) { mutableListOf() }
                                .add(DictEntry(english, cost))
                        }
                    }
                }
            }
            Log.i("NacreDictionary", "English dict loaded: ${englishDict.size} entries")
        } catch (e: Exception) {
            Log.i("NacreDictionary", "No English dictionary found (optional)")
        }
    }

    /**
     * Loanword mapping (JMdict-derived, see dict/loanwords_en.tsv header):
     * thousands of common katakana loanwords merged into [englishDict] so
     * englishMatch() surfaces the English spelling (e.g. かめら → camera).
     * The hand-curated english_words.tsv wins on duplicates — generated
     * entries whose surface already exists for the reading are skipped.
     */
    private fun loadLoanwords() {
        try {
            assetOpener.open("dict/loanwords_en.tsv").use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    var added = 0
                    reader.forEachLine { line ->
                        if (line.isBlank() || line.startsWith('#')) return@forEachLine
                        val parts = line.split('\t')
                        if (parts.size >= 2) {
                            val reading = parts[0]
                            val english = parts[1]
                            val cost = if (parts.size >= 3) parts[2].toIntOrNull() ?: 2300 else 2300
                            val list = englishDict.getOrPut(reading) { mutableListOf() }
                            if (list.none { it.surface.equals(english, ignoreCase = true) }) {
                                list.add(DictEntry(english, cost))
                                added++
                            }
                        }
                    }
                    Log.i("NacreDictionary", "Loanwords loaded: $added entries")
                }
            }
        } catch (e: Exception) {
            Log.i("NacreDictionary", "No loanword dictionary found (optional)")
        }
    }

    private fun loadEnglishFullDict() {
        try {
            assetOpener.open("dict/english_full.tsv").use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    reader.forEachLine { line ->
                        if (line.isBlank() || line.startsWith('#')) return@forEachLine
                        val parts = line.split('\t')
                        if (parts.size >= 3) {
                            val key = parts[0]       // lowercase key
                            val surface = parts[1]    // display form (may have mixed case)
                            val cost = parts[2].toIntOrNull() ?: 8000
                            englishFullDict.getOrPut(key) { mutableListOf() }
                                .add(DictEntry(surface, cost))
                        }
                    }
                }
            }
            englishSortedKeys = englishFullDict.keys.toTypedArray().also { it.sort() }
            Log.i("NacreDictionary", "English full dict loaded: ${englishFullDict.size} keys, ${englishSortedKeys.size} sorted")
        } catch (e: Exception) {
            Log.i("NacreDictionary", "No english_full.tsv found (optional)")
        }
    }

    /**
     * Load static bigram data from bigrams.tsv.
     * Format: prev_surface\tnext_reading\tnext_surface\tboost
     */
    private fun loadStaticBigrams() {
        try {
            assetOpener.open("dict/bigrams.tsv").use { stream ->
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
                            val key = "$prevSurface→$nextReading:$nextSurface"
                            staticBigrams[key] = boost
                            count++
                        }
                    }
                    Log.i("NacreDictionary", "Static bigrams loaded: $count entries")
                }
            }
        } catch (e: Exception) {
            Log.i("NacreDictionary", "No bigrams.tsv found (optional)")
        }
    }

    /**
     * Predict English words from prefix input.
     * Returns autocomplete candidates sorted by cost (frequency).
     */
    override fun predictEnglish(prefix: String, limit: Int): List<ConversionCandidate> {
        if (prefix.length < 1) return emptyList()
        val prefixLower = prefix.lowercase()
        val results = mutableListOf<ConversionCandidate>()

        // 1. Exact match
        val exact = englishFullDict[prefixLower]
        if (exact != null) {
            for (entry in exact) {
                results.add(ConversionCandidate(
                    surface = entry.surface,
                    reading = prefix,
                    cost = entry.cost - 2000,  // Strong bonus for exact match
                ))
            }
        }

        // 2. Prefix match via binary search on sorted keys.
        // The scan window must be much wider than the display limit: keys
        // are alphabetical, so a narrow window (the old limit*2) fills up
        // with rare long words (headache, hector, ...) before reaching
        // short frequent ones (her, here, help) that sort later. Ranking
        // is by cost afterwards, so collecting a few hundred keys and
        // sorting beats truncating the scan early.
        val startIdx = englishSortedKeys.binarySearchInsertionPoint(prefixLower)
        var scanned = 0
        for (i in startIdx until englishSortedKeys.size) {
            val key = englishSortedKeys[i]
            if (!key.startsWith(prefixLower)) break
            if (key == prefixLower) continue  // Already handled as exact
            val entries = englishFullDict[key] ?: continue
            for (entry in entries.take(2)) {
                val lengthPenalty = (key.length - prefixLower.length) * 100
                results.add(ConversionCandidate(
                    surface = entry.surface,
                    reading = prefix,
                    cost = entry.cost + lengthPenalty,
                ))
            }
            scanned++
            if (scanned >= ENGLISH_PREFIX_SCAN_LIMIT) break
        }

        // 3. Spell correction (edit distance 1) for inputs >= 3 chars
        if (results.size < 5 && prefixLower.length >= 3) {
            val corrections = spellCorrect(prefixLower, limit = 5)
            for (c in corrections) {
                if (results.none { it.surface.equals(c.surface, ignoreCase = true) }) {
                    results.add(c)
                }
            }
        }

        // 4. Apply English bigram boost
        if (lastCommittedEnglish.isNotEmpty()) {
            for (i in results.indices) {
                val bigramKey = "${lastCommittedEnglish.lowercase()}→${results[i].surface.lowercase()}"
                val boost = englishBigramBoost[bigramKey] ?: 0
                if (boost > 0) {
                    results[i] = results[i].copy(cost = results[i].cost - minOf(boost * 800, 3000))
                }
            }
        }

        // 5. Shelly Bridge terms: repo name / git branch / cwd path segments / safe technical
        // terms shared by the Shelly terminal IDE while it's in the foreground (see
        // ShellyBridgeContext.kt). Purely additive — only prefix matches of what the user is
        // already typing are added, and only if the dictionary pipeline above hasn't already
        // surfaced the same word. Costed like an ordinary dictionary hit (base cost + a
        // length-proportional penalty for the untyped remainder, mirroring historyMatchesFor())
        // so it nudges the ranking without dominating exactMatch()/prefix-match results.
        for (term in shellyBridge?.matchingTerms(prefix).orEmpty()) {
            if (results.any { it.surface.equals(term, ignoreCase = true) }) continue
            val lengthPenalty = maxOf(0, term.length - prefixLower.length) * SHELLY_BRIDGE_TERM_PER_CHAR_PENALTY
            results.add(ConversionCandidate(
                surface = term,
                reading = prefix,
                cost = SHELLY_BRIDGE_TERM_BASE_COST + lengthPenalty,
            ))
        }

        // 6. Case adaptation: shape each surface to the case pattern the
        // user is typing ("th" -> the/that, "Th" -> The/That, "TH" -> THE),
        // demoting words whose case is intrinsic (iPhone, GitHub, AI) when
        // it clashes with the typed pattern instead of silently returning
        // the stored form. Duplicates that adaptation creates (e.g. stored
        // "the"/"The" pairs) collapse to the cheaper one.
        val pattern = englishCasePattern(prefix)
        val adapted = LinkedHashMap<String, ConversionCandidate>()
        for (c in results) {
            val (surface, penalty) = adaptEnglishCase(c.surface, pattern)
            val candidate = if (surface == c.surface && penalty == 0) c
                else c.copy(surface = surface, cost = c.cost + penalty)
            val prev = adapted[surface]
            if (prev == null || candidate.cost < prev.cost) adapted[surface] = candidate
        }

        val sorted = adapted.values.sortedBy { it.cost }

        // 7. Commit history: words the user has committed before resurface
        // for alphabet prefixes too (the Japanese path has long had this
        // via historyMatchesFor in predict()). History surfaces keep their
        // committed case — they bypass the case adaptation above — and
        // merge by word: a history word that is also a dictionary hit
        // appears once, in its committed form, at the better of the two
        // costs. EnglishHistory.matchesFor applies a penalty proportional
        // to the untyped remainder, so history cannot bury dictionary
        // completions on short prefixes.
        val historySnapshot = synchronized(englishHistoryLock) { recentEnglishHistory.toList() }
        val historyMatches = EnglishHistory.matchesFor(historySnapshot, prefix)
        if (historyMatches.isEmpty()) return sorted.take(limit)
        val merged = sorted.toMutableList()
        for (h in historyMatches) {
            var bestCost = h.cost
            val iter = merged.iterator()
            while (iter.hasNext()) {
                val c = iter.next()
                if (c.surface.equals(h.surface, ignoreCase = true)) {
                    if (c.cost < bestCost) bestCost = c.cost
                    iter.remove()
                }
            }
            merged.add(h.copy(cost = bestCost))
        }
        return merged.sortedBy { it.cost }.take(limit)
    }

    // --- Swipe (glide) typing lexicons ---

    override fun swipeLexiconEnglish(): List<SwipeLexiconWord> {
        if (englishFullDict.isEmpty()) return emptyList()
        return englishFullDict.map { (key, entries) ->
            val best = entries.minByOrNull { it.cost } ?: entries.first()
            SwipeLexiconWord(word = key, cost = best.cost, surface = best.surface)
        }
    }

    @Volatile
    private var swipeRomajiLexiconCache: List<SwipeLexiconWord>? = null

    override fun swipeLexiconRomaji(): List<SwipeLexiconWord> {
        swipeRomajiLexiconCache?.let { return it }
        if (dict.isEmpty()) return emptyList()
        // Keep the ROMAJI_SWIPE_LEXICON_LIMIT most frequent readings (by
        // their cheapest entry cost) in a bounded max-heap: the main
        // dictionary holds hundreds of thousands of readings, and a full
        // sort just to take a top-N would stall the first glide.
        val heap = java.util.PriorityQueue<Pair<String, Int>>(
            ROMAJI_SWIPE_LEXICON_LIMIT,
            compareByDescending { it.second },
        )
        for ((reading, entries) in dict) {
            val cost = entries.minOf { it.cost }
            if (heap.size < ROMAJI_SWIPE_LEXICON_LIMIT) {
                heap.add(reading to cost)
            } else if (cost < (heap.peek()?.second ?: Int.MAX_VALUE)) {
                heap.poll()
                heap.add(reading to cost)
            }
        }
        val engine = JapaneseEngine()
        val bestByRomaji = HashMap<String, Int>()
        for ((reading, cost) in heap) {
            val romaji = KanaRomaji.hiraganaToRomaji(reading) ?: continue
            if (romaji.length < SwipeLexicon.MIN_WORD_LENGTH) continue
            // KanaRomaji is a generation aid, not a source of truth: the
            // romaji enters the lexicon only if the real engine converts
            // it back to exactly this reading.
            if (engine.romajiToHiragana(romaji, finalize = true) != reading) continue
            val prev = bestByRomaji[romaji]
            if (prev == null || cost < prev) bestByRomaji[romaji] = cost
        }
        if (bestByRomaji.isEmpty()) return emptyList()
        val result = bestByRomaji.map { (romaji, cost) -> SwipeLexiconWord(romaji, cost) }
        swipeRomajiLexiconCache = result
        return result
    }

    private enum class EnglishCasePattern { LOWER, CAPITALIZED, UPPER, MIXED }

    private fun englishCasePattern(s: String): EnglishCasePattern {
        val letters = s.filter { it.isLetter() }
        if (letters.isEmpty()) return EnglishCasePattern.LOWER
        return when {
            letters.all { it.isLowerCase() } -> EnglishCasePattern.LOWER
            letters.all { it.isUpperCase() } ->
                if (letters.length == 1) EnglishCasePattern.CAPITALIZED else EnglishCasePattern.UPPER
            letters.first().isUpperCase() && letters.drop(1).all { it.isLowerCase() } ->
                EnglishCasePattern.CAPITALIZED
            else -> EnglishCasePattern.MIXED
        }
    }

    /** True when the case is part of the word itself: iPhone, GitHub, AI, I. */
    private fun hasIntrinsicCase(surface: String): Boolean {
        val letters = surface.filter { it.isLetter() }
        if (letters.length < 2) return surface.any { it.isUpperCase() }
        return letters.drop(1).any { it.isUpperCase() }
    }

    /**
     * Returns the surface to show for [pattern] plus a cost penalty.
     * Plain words are re-cased freely; intrinsic-case words keep their
     * stored form and take a penalty when it clashes with the pattern.
     */
    private fun adaptEnglishCase(surface: String, pattern: EnglishCasePattern): Pair<String, Int> {
        if (hasIntrinsicCase(surface)) {
            val own = englishCasePattern(surface)
            val clash = when (pattern) {
                EnglishCasePattern.LOWER ->
                    own == EnglishCasePattern.CAPITALIZED || own == EnglishCasePattern.UPPER
                EnglishCasePattern.CAPITALIZED ->
                    own == EnglishCasePattern.LOWER || own == EnglishCasePattern.UPPER
                EnglishCasePattern.UPPER ->
                    own == EnglishCasePattern.LOWER || own == EnglishCasePattern.CAPITALIZED
                EnglishCasePattern.MIXED -> false
            }
            return surface to if (clash) ENGLISH_CASE_MISMATCH_PENALTY else 0
        }
        val adapted = when (pattern) {
            EnglishCasePattern.LOWER -> surface.lowercase()
            EnglishCasePattern.CAPITALIZED ->
                surface.lowercase().replaceFirstChar { it.uppercase() }
            EnglishCasePattern.UPPER -> surface.uppercase()
            EnglishCasePattern.MIXED -> surface
        }
        return adapted to 0
    }

    /**
     * Record English word selection for bigram learning and commit history.
     */
    override fun recordEnglishSelection(word: String) {
        if (lastCommittedEnglish.isNotEmpty()) {
            val key = "${lastCommittedEnglish.lowercase()}→${word.lowercase()}"
            val count = englishBigramBoost.merge(key, 1) { old, _ -> minOf(old + 1, 5) } ?: 1
        }
        lastCommittedEnglish = word
        // Commit history (surfaced by predictEnglish step 7): same
        // dedupe/cap discipline as the Japanese recentHistory, persisted
        // through the same debounced save. Callers (InputEngine) never
        // invoke this in password fields.
        val recorded = synchronized(englishHistoryLock) {
            EnglishHistory.record(recentEnglishHistory, word, maxHistory)
        }
        if (recorded) {
            debouncedSave()
        }
    }

    /**
     * Spell correction via edit distance 1 (deletion, substitution, insertion, transposition).
     */
    private fun spellCorrect(input: String, limit: Int): List<ConversionCandidate> {
        val results = mutableListOf<ConversionCandidate>()
        val seen = mutableSetOf<String>()

        // Deletions: remove one char
        for (i in input.indices) {
            val candidate = input.removeRange(i, i + 1)
            if (candidate.length >= 2 && seen.add(candidate)) {
                val entries = englishFullDict[candidate]
                if (entries != null) {
                    for (e in entries.take(1)) {
                        results.add(ConversionCandidate(e.surface, input, e.cost + 3000))
                    }
                }
            }
        }

        // Substitutions: replace one char
        for (i in input.indices) {
            for (c in 'a'..'z') {
                if (c == input[i]) continue
                val candidate = input.replaceRange(i, i + 1, c.toString())
                if (seen.add(candidate)) {
                    val entries = englishFullDict[candidate]
                    if (entries != null) {
                        for (e in entries.take(1)) {
                            results.add(ConversionCandidate(e.surface, input, e.cost + 3000))
                        }
                    }
                }
            }
            if (results.size >= limit) break
        }

        // Transpositions: swap adjacent chars
        for (i in 0 until input.length - 1) {
            val candidate = buildString {
                append(input, 0, i)
                append(input[i + 1])
                append(input[i])
                if (i + 2 < input.length) append(input, i + 2, input.length)
            }
            if (seen.add(candidate)) {
                val entries = englishFullDict[candidate]
                if (entries != null) {
                    for (e in entries.take(1)) {
                        results.add(ConversionCandidate(e.surface, input, e.cost + 2500))
                    }
                }
            }
        }

        // Edit distance 2: double deletion (for 5+ char inputs, limited scope)
        if (results.size < limit && input.length >= 5) {
            outer@ for (i in input.indices) {
                val del1 = input.removeRange(i, i + 1)
                for (j in del1.indices) {
                    val del2 = del1.removeRange(j, j + 1)
                    if (del2.length >= 2 && seen.add(del2)) {
                        val entries = englishFullDict[del2]
                        if (entries != null) {
                            for (e in entries.take(1)) {
                                results.add(ConversionCandidate(e.surface, input, e.cost + 4500))
                            }
                            if (results.size >= limit) break@outer
                        }
                    }
                }
            }
        }

        return results.sortedBy { it.cost }.take(limit)
    }

    /** Binary search for insertion point in sorted array. */
    private fun Array<String>.binarySearchInsertionPoint(prefix: String): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (this[mid] < prefix) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Keys under which [englishDict] may hold [kana]'s loanwords: the
     * reading as typed, plus its long-vowel (ー) spelling when that
     * differs (see [englishMatch] step 1).
     */
    private fun englishLookupKeys(kana: String): List<String> {
        val normalized = normalizeLongVowelReading(kana)
        return if (normalized == kana) listOf(kana) else listOf(kana, normalized)
    }

    /**
     * The ー spelling of a reading whose long vowels were entered the
     * romaji way. Doubling a vowel LETTER in romaji surfaces in kana as
     * the previous syllable followed by its own vowel kana —
     * "konpyuutaa" composes to こんぴゅうたあ (ぴゅ+う, た+あ), NOT to a
     * string containing ああ — so the normalization is syllable-based:
     * a vowel kana that merely lengthens the preceding syllable's vowel
     * (including the おう/えい spellings) becomes ー. Used ONLY as an
     * additional lookup key — for the English dictionary
     * ([englishLookupKeys]) and for the main dictionary
     * ([longVowelNormalizedMatch]) — the user's reading itself is
     * never rewritten, and merged candidates keep the typed reading.
     */
    private fun normalizeLongVowelReading(kana: String): String {
        if (kana.length < 2) return kana
        val out = StringBuilder(kana.length)
        var prevVowel: Char? = null
        for (c in kana) {
            val lengtheners = prevVowel?.let { LONG_VOWEL_LENGTHENERS[it] }
            if (lengtheners != null && c in lengtheners) {
                out.append('ー')
                // The long vowel closes the syllable: a vowel kana
                // after ー starts a new syllable, it does not lengthen
                // this one further.
                prevVowel = null
            } else {
                out.append(c)
                prevVowel = KANA_VOWEL[c]
            }
        }
        return out.toString()
    }

    private fun englishMatch(kana: String, limit: Int): List<ConversionCandidate> {
        if (limit <= 0 || kana.length < 2) return emptyList()
        val results = mutableListOf<ConversionCandidate>()

        // 1. Exact match — no penalty. Looked up under the reading as
        // typed AND under its long-vowel (ー) spelling: romaji typists
        // spell long vowels by doubling (konpyuutaa → こんぴゅうたあ) or
        // with おう, while the loanword table is keyed by the ー spelling
        // (こんぴゅーたー), so the raw key alone never matched and the
        // English candidate silently never appeared for romaji input.
        // The candidate keeps the TYPED reading, so selection learning
        // and history stay keyed by what the user actually entered.
        val seenSurfaces = mutableSetOf<String>()
        for (key in englishLookupKeys(kana)) {
            val exact = englishDict[key] ?: continue
            for (entry in exact) {
                if (results.size >= limit) break
                if (seenSurfaces.add(entry.surface)) {
                    results.add(ConversionCandidate(
                        surface = entry.surface,
                        reading = kana,
                        cost = entry.cost,
                    ))
                }
            }
            if (results.size >= limit) break
        }

        // 2. Prefix match — penalty proportional to remaining characters
        // e.g. "あっぷ"(3) matching "あっぷる"(4): penalty = (4-3)/4 * 800 = 200
        // e.g. "あ"(1) matching "あっぷる"(4): penalty = (4-1)/4 * 800 = 600
        if (results.size < limit) {
            for ((key, entries) in englishDict) {
                if (key.startsWith(kana) && key != kana) {
                    val matchRatio = kana.length.toFloat() / key.length
                    val penalty = ((1f - matchRatio) * 800).toInt()
                    for (entry in entries.take(1)) {
                        results.add(ConversionCandidate(
                            surface = entry.surface,
                            reading = key,
                            cost = entry.cost + penalty,
                        ))
                        if (results.size >= limit) return results.sortedBy { it.cost }
                    }
                }
            }
        }

        return results.sortedBy { it.cost }
    }

    // Romaji→English reverse index: maps lowercase English word prefix to entries
    // Built from englishDict: e.g. "google" → DictEntry("Google", 4000)
    private val romajiEnglishIndex = HashMap<String, MutableList<DictEntry>>(500)

    private fun buildRomajiEnglishIndex() {
        for ((_, entries) in englishDict) {
            for (entry in entries) {
                val key = entry.surface.lowercase()
                romajiEnglishIndex.getOrPut(key) { mutableListOf() }.add(entry)
            }
        }
        Log.i("NacreDictionary", "Romaji English index: ${romajiEnglishIndex.size} entries")
    }

    /**
     * Match raw romaji input against English words.
     * e.g. "goo" matches "Google", "good"; "lin" matches "LINE", "Linux"
     */
    private fun romajiEnglishMatch(romaji: String, limit: Int): List<ConversionCandidate> {
        if (limit <= 0 || romaji.length < 2) return emptyList()
        val romajiLower = romaji.lowercase()
        val results = mutableListOf<ConversionCandidate>()

        for ((key, entries) in romajiEnglishIndex) {
            if (key.startsWith(romajiLower)) {
                for (entry in entries.take(1)) {
                    results.add(ConversionCandidate(
                        surface = entry.surface,
                        reading = romaji,
                        cost = entry.cost + if (key == romajiLower) 0 else 500,
                    ))
                    if (results.size >= limit) return results.sortedBy { it.cost }
                }
            }
        }

        return results.sortedBy { it.cost }
    }

    // --- Typo correction ---

    /**
     * Generate typo-corrected candidates by:
     * 1. Swapping adjacent kana characters (transposition)
     * 2. Common misreading patterns (ふいんき→ふんいき etc.)
     */
    private fun typoCorrection(kana: String, limit: Int): List<ConversionCandidate> {
        if (limit <= 0 || kana.length < 3) return emptyList()
        val results = mutableListOf<ConversionCandidate>()
        val seen = mutableSetOf<String>()

        // 1. Adjacent character transposition
        for (i in 0 until kana.length - 1) {
            val swapped = buildString {
                append(kana, 0, i)
                append(kana[i + 1])
                append(kana[i])
                append(kana, i + 2, kana.length)
            }
            if (swapped != kana && seen.add(swapped)) {
                val entries = dict[swapped]
                if (entries != null) {
                    for (entry in entries.take(2)) {
                        results.add(ConversionCandidate(
                            surface = entry.surface,
                            reading = swapped,
                            cost = entry.cost + 6000, // Penalty for typo — keep these below real candidates
                        ))
                        if (results.size >= limit) return results.sortedBy { it.cost }
                    }
                }
                // Also try Viterbi on the swapped reading
                val viterbi = latticeConvert(swapped)
                for (v in viterbi.take(1)) {
                    if (seen.add(v.surface)) {
                        results.add(v.copy(cost = v.cost + 6000))
                        if (results.size >= limit) return results.sortedBy { it.cost }
                    }
                }
            }
        }

        // 2. Common misreading/mistyping patterns
        val corrections = mapOf(
            // === 定番の誤読 ===
            "ふいんき" to "ふんいき",        // 雰囲気
            "たいく" to "たいいく",          // 体育
            "がいしゅつ" to "きしゅつ",       // 既出
            "ぜいいん" to "ぜんいん",        // 全員
            "いちよう" to "いちおう",        // 一応
            "そうゆう" to "そういう",        // そういう
            "こんにちわ" to "こんにちは",
            "こんばんわ" to "こんばんは",
            "づつ" to "ずつ",              // ～ずつ
            "しゅみれーしょん" to "しみゅれーしょん", // シミュレーション
            "うるおぼえ" to "うろおぼえ",     // うろ覚え
            "ていかく" to "てきかく",        // 的確
            "げいいん" to "げんいん",        // 原因
            "がいいん" to "げんいん",        // 原因
            "えんえん" to "えんえん",        // 延々（「永遠」と混同されがち。自己マップだが辞書引きで候補出す）
            "かんぺき" to "かんぺき",        // 完璧
            "ひつぜん" to "ひつぜん",
            "ぜったい" to "ぜったい",
            // === 助詞「は」「へ」「を」の誤り ===
            "わたしわ" to "わたしは",
            "きょうわ" to "きょうは",
            "ぼくわ" to "ぼくは",
            "あなたわ" to "あなたは",
            "それわ" to "それは",
            "これわ" to "これは",
            "あれわ" to "あれは",
            "どれわ" to "どれは",
            "だれわ" to "だれは",
            "なにわ" to "なには",
            "どこえ" to "どこへ",
            "こっちえ" to "こっちへ",
            "そっちえ" to "そっちへ",
            "あっちえ" to "あっちへ",
            "うちえ" to "うちへ",
            "がっこうえ" to "がっこうへ",
            "かいしゃえ" to "かいしゃへ",
            // === 長音の誤入力 ===
            "とうり" to "とおり",            // 通り
            "おうきい" to "おおきい",        // 大きい
            "おうい" to "おおい",            // 多い
            "こうり" to "こおり",            // 氷
            "とうい" to "とおい",            // 遠い
            "おうきな" to "おおきな",        // 大きな
            // === カタカナ語の誤読 ===
            "こみにけーしょん" to "こみゅにけーしょん", // コミュニケーション
            "あぼがど" to "あぼかど",        // アボカド
            "ばっく" to "ばっぐ",            // バッグ
            "べっと" to "べっど",            // ベッド
            "でぃすくとっぷ" to "ですくとっぷ", // デスクトップ
            "あたっち" to "あたっち",
            "ぼらんてぃあ" to "ぼらんてぃあ",
            "ぷれぜんてーしょん" to "ぷれぜんてーしょん",
            // === IT用語 ===
            "ぱらめーた" to "ぱらめーたー",   // パラメーター
            "ぷろぱてぃ" to "ぷろぱてぃー",   // プロパティー
            "でぃれくとり" to "でぃれくとりー", // ディレクトリー
            "れぽじとり" to "りぽじとりー",   // リポジトリー
            "ぶらうざ" to "ぶらうざー",      // ブラウザー
            "さーば" to "さーばー",          // サーバー
            "こんてな" to "こんてなー",      // コンテナー
            "どっか" to "どっかー",          // Docker
            "くばねてぃす" to "くーばねてぃす", // Kubernetes
            "ぎっとはぶ" to "ぎっとはぶ",     // GitHub
            // === 話し言葉の誤り ===
            "ゆわれた" to "いわれた",         // 言われた
            "ゆった" to "いった",            // 言った
            "ゆってた" to "いってた",         // 言ってた
            "ゆってる" to "いってる",         // 言ってる
            "ゆう" to "いう",               // 言う
            "ちがくて" to "ちがって",         // 違って
            "ちがかった" to "ちがった",        // 違った
            "やっぱし" to "やっぱり",         // やっぱり
            "やぱり" to "やっぱり",
            "すいません" to "すみません",
            "あざす" to "ありがとうございます",
            "あざっす" to "ありがとうございます",
            "おなしゃす" to "おねがいします",
            "わかんない" to "わからない",
            "しんない" to "しらない",
            "つーか" to "というか",
            "てか" to "というか",
            "じゃね" to "ではないか",
            // === 音便の誤入力 ===
            "あったかい" to "あたたかい",     // 暖かい
            "つったってる" to "つったってる",
            "おもしれー" to "おもしろい",
            "すげー" to "すごい",
            "やべー" to "やばい",
            "でけー" to "でかい",
            "はえー" to "はやい",
            "うめー" to "うまい",
            "つえー" to "つよい",
            "ねみー" to "ねむい",
            // === 二重母音・促音の誤り ===
            "おとうさん" to "おとうさん",
            "おかあさん" to "おかあさん",
            "にいさん" to "にいさん",
            "ねえさん" to "ねえさん",
            "ちっちゃい" to "ちいさい",       // 小さい
            "おっきい" to "おおきい",        // 大きい
            "ちっさい" to "ちいさい",
        )
        val corrected = corrections[kana]
        if (corrected != null && corrected != kana) {
            val entries = dict[corrected]
            if (entries != null) {
                for (entry in entries.take(3)) {
                    if (seen.add(entry.surface)) {
                        results.add(ConversionCandidate(
                            surface = entry.surface,
                            reading = corrected,
                            cost = entry.cost + 500, // Small penalty — likely what user meant
                        ))
                    }
                }
            }
            val viterbi = latticeConvert(corrected)
            for (v in viterbi.take(2)) {
                if (seen.add(v.surface)) {
                    results.add(v.copy(cost = v.cost + 500))
                }
            }
        }

        // 3. Single character deletion (one extra char typed)
        if (kana.length >= 4) {
            for (i in kana.indices) {
                val deleted = kana.removeRange(i, i + 1)
                if (seen.add(deleted)) {
                    val entries = dict[deleted]
                    if (entries != null) {
                        for (entry in entries.take(1)) {
                            results.add(ConversionCandidate(
                                surface = entry.surface,
                                reading = deleted,
                                cost = entry.cost + 3000,
                            ))
                            if (results.size >= limit) return results.sortedBy { it.cost }
                        }
                    }
                }
            }
        }

        return results.sortedBy { it.cost }.take(limit)
    }

    // --- Viterbi segmentation with POS connection costs ---

    companion object {
        private const val DEFAULT_CONNECTION_COST = 2000

        /** 0-based slot the best exact-match loanword candidate is promoted to. */
        private const val LOANWORD_PROMOTE_INDEX = 4

        /** Lattice: POS id used for unknown single-kana nodes (名詞,一般). */
        private const val LATTICE_UNKNOWN_POS_ID = 1852
        /** Lattice: cost of an unknown single-kana node. */
        private const val LATTICE_UNKNOWN_COST = 12000
        /** Lattice: per-reading cap on dictionary entries entering the lattice. */
        private const val LATTICE_MAX_ENTRIES_PER_READING = 40
        /** Mozc id.def range of 接頭詞 (prefix) POS ids. */
        private val PREFIX_POS_IDS = 2594..2640
        /** Cost added to a lattice path that ends on a prefix (see eosPenalty). */
        private const val LATTICE_PREFIX_END_PENALTY = 3000
        /** Lattice paths taken from each normalized / variant reading. */
        private const val LATTICE_VARIANT_TAKE = 5
        /** Cost added to paths of a kana-variant reading (を→お …). */
        private const val KANA_VARIANT_PENALTY = 500
        /** predict(): conversions of the typed reading taken into the pool. */
        private const val PREDICT_CONVERSIONS = 12
        /** Completions: minimum typed kana before completions are offered. */
        private const val COMPLETION_MIN_KANA = 2
        private const val COMPLETION_MAX_READINGS = 20000
        private const val COMPLETION_MAX_EXTRA_KANA = 8
        private const val COMPLETION_LIMIT = 10
        /**
         * Completion pricing on top of the one-word path cost (tuned on
         * tools/benchmark/completions.tsv; the sentence benchmark is
         * unaffected): a small bonus, because a whole word beats the
         * fragmentary conversions of a half-typed reading (ありが → 有賀),
         * minus a per-extra-kana penalty.
         */
        private const val COMPLETION_BASE_PENALTY = -500
        private const val COMPLETION_PER_KANA_PENALTY = 200

        /**
         * Slot used instead of [LOANWORD_PROMOTE_INDEX] when the top
         * candidate is a katakana word (see [promoteLoanwordCandidate]).
         */
        private const val LOANWORD_PROMOTE_INDEX_KATAKANA_TOP = 1

        /** Katakana word: katakana letters plus ー/・, at least 2 chars. */
        private fun isKatakanaWord(s: String): Boolean =
            s.length >= 2 && s.all { it in 'ァ'..'ヺ' || it == 'ー' || it == '・' }

        /**
         * Cost penalty for main-dictionary candidates merged via the
         * long-vowel-normalized reading ([longVowelNormalizedMatch]),
         * applied on top of the floor described there. Sized from
         * measured cost bands (harness, empty learning): loanword
         * surfaces land ~2000-6000 （サーバー 2174, コンピューター ~1600),
         * far above the typed reading's Viterbi mis-conversions
         * (≳13000), so with the penalty they stay real candidates;
         * native readings are protected by the floor, not by this
         * value alone (the ー spelling can hold arbitrarily cheap
         * unrelated entries — くせー at raw cost 293).
         */
        private const val LONG_VOWEL_NORMALIZED_PENALTY = 1500

        /** Hiragana -> the vowel ('a'/'i'/'u'/'e'/'o') of its syllable, for
         *  [normalizeLongVowelReading]. ん/っ/ー and non-kana are absent:
         *  they close the syllable, so a following vowel kana starts a new
         *  one instead of lengthening. */
        private val KANA_VOWEL: Map<Char, Char> = HashMap<Char, Char>().apply {
            for (c in "あかさたなはまやらわがざだばぱ") put(c, 'a')
            for (c in "いきしちにひみりゐぎじぢびぴ") put(c, 'i')
            for (c in "うくすつぬふむゆるぐずづぶぷ") put(c, 'u')
            for (c in "えけせてねへめれゑげぜでべぺ") put(c, 'e')
            for (c in "おこそとのほもよろをごぞどぼぽ") put(c, 'o')
            put('ゃ', 'a'); put('ゅ', 'u'); put('ょ', 'o')
        }

        /** Per syllable vowel, the kana that merely lengthen it when they
         *  follow: the doubled vowel itself, plus い for e-vowels (えい)
         *  and う for o-vowels (おう). */
        private val LONG_VOWEL_LENGTHENERS: Map<Char, Set<Char>> = mapOf(
            'a' to setOf('あ'),
            'i' to setOf('い'),
            'u' to setOf('う'),
            'e' to setOf('え', 'い'),
            'o' to setOf('お', 'う'),
        )

        /** Demotion for intrinsic-case words clashing with the typed case. */
        private const val ENGLISH_CASE_MISMATCH_PENALTY = 1200

        /** Max keys scanned per predictEnglish() prefix query (see step 2). */
        private const val ENGLISH_PREFIX_SCAN_LIMIT = 500

        /** Kana readings (most frequent) converted into the romaji swipe lexicon. */
        private const val ROMAJI_SWIPE_LEXICON_LIMIT = 20000

        // Dictionary snapshot (fast restart): magic "NCSP", on-disk layout
        // version — bump when the serialized layout or DictEntry changes.
        private const val SNAPSHOT_MAGIC = 0x4E435350
        private const val SNAPSHOT_FORMAT_VERSION = 2
        private const val SNAPSHOT_FILE_NAME = "dict_snapshot.bin"
        private const val MAX_SNAPSHOT_STRING_BYTES = 1 shl 20

        // predict() memoization: bounded LRU over recent composing prefixes.
        private const val PREDICT_CACHE_MAX_ENTRIES = 64
        // KenLM weight for post-hoc rescoring (high: LM should strongly influence final ranking)
        private const val KENLM_WEIGHT = 5000f

        // Shelly Bridge term cost: base cost lands it in the same range as an ordinary
        // dictionary/spell-correction hit (see spellCorrect()'s +3000/+2500 penalties above) —
        // high enough that it never outranks an actual exact-match dictionary word, but still
        // competitive so a relevant repo/branch/cwd/technical term can surface.
        private const val SHELLY_BRIDGE_TERM_BASE_COST = 3500
        private const val SHELLY_BRIDGE_TERM_PER_CHAR_PENALTY = 400

        // Mozc POS ID range checks (from id.def)
        fun isNoun(id: Int) = id in 1841..2193
        fun isVerb(id: Int) = id in 434..1840
        fun isAdjective(id: Int) = id in 2194..2588
        fun isAuxVerb(id: Int) = id in 29..267
        fun isParticle(id: Int) = id in 268..433
        fun isContentWord(id: Int) = id in 434..2588  // 動詞+名詞+形容詞
        fun isFunctionWord(id: Int) = id in 29..433   // 助動詞+助詞
        fun isAdverb(id: Int) = id in 12..28
        fun isConjunction(id: Int) = id in 2591..2593
        fun isInterjection(id: Int) = id in 2589..2590
        fun isSymbol(id: Int) = id in 2641..2656

        // Full-width katakana -> half-width katakana mapping
        private val HALF_WIDTH_KATAKANA_MAP = mapOf(
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

    /** Raw (unscaled) Mozc connection cost. */
    private fun rawConnectionCost(prevRightId: Int, currLeftId: Int): Int {
        if (connectionCostFlat.isEmpty() || numIds == 0) return DEFAULT_CONNECTION_COST
        val r = prevRightId.coerceIn(0, numIds - 1)
        val l = currLeftId.coerceIn(0, numIds - 1)
        return connectionCostFlat[r * numIds + l].toInt()
    }

    /**
     * Mozc's matrix charges nothing for X→EOS, so a reading can end on a
     * bare prefix (あい → 相, 接頭詞). Real Mozc keeps such candidates
     * down with segment-level rules this engine does not have; a flat
     * penalty on paths whose last word is a prefix does the same job.
     */
    private fun eosPenalty(rid: Int): Int =
        if (rid in PREFIX_POS_IDS) LATTICE_PREFIX_END_PENALTY else 0

    private fun isLatticeSymbolSurface(surface: String): Boolean = surface.any { ch ->
        val c = ch.code
        // surrogates (emoji), dingbats/misc symbols, arrows, geometric shapes, enclosed alnum
        ch.isSurrogate() || c in 0x2190..0x2BFF || c in 0x2460..0x24FF || c in 0x3200..0x33FF ||
            c == 0xFE0F || c in 0x25A0..0x25FF
    }

    /**
     * Mozc-model lattice conversion: word cost + full connection cost, exact
     * forward Viterbi (best cost per lattice node) and exact N-best by backward
     * A* (the forward costs are an admissible, exact heuristic). No ad-hoc
     * length/hiragana bonuses — the Mozc costs already encode them.
     */
    private fun latticeConvert(kana: String): List<ConversionCandidate> {
        val n = kana.length
        if (n == 0) return emptyList()
        val unknownId = LATTICE_UNKNOWN_POS_ID
        class LNode(val start: Int, val end: Int, val surface: String, val lid: Int, val rid: Int, val wcost: Int) {
            var best = Int.MAX_VALUE
        }
        val endAt = Array(n + 1) { ArrayList<LNode>() }
        val startAt = Array(n + 1) { ArrayList<LNode>() }
        val maxLen = minOf(n, 16)
        for (st in 0 until n) {
            for (len in 1..minOf(maxLen, n - st)) {
                val seg = kana.substring(st, st + len)
                val entries = dict[seg] ?: continue
                var taken = 0
                for (e in entries) {
                    if (taken >= LATTICE_MAX_ENTRIES_PER_READING) break
                    if (len < n && isLatticeSymbolSurface(e.surface)) continue
                    // Whole-reading nodes get the learning boost once, on the
                    // full surface, in rankedConversions — not here as well.
                    val wcost = if (len == n) e.cost else applyBoost(seg, e.surface, e.cost)
                    val node = LNode(st, st + len, e.surface, e.leftGroup, e.rightGroup, wcost)
                    endAt[st + len].add(node); startAt[st].add(node); taken++
                    // The same surface's other POS readings (see DictEntry.variants).
                    e.variants?.forEach { v ->
                        val vcost = if (len == n) v.cost else applyBoost(seg, v.surface, v.cost)
                        val vn = LNode(st, st + len, v.surface, v.leftGroup, v.rightGroup, vcost)
                        endAt[st + len].add(vn); startAt[st].add(vn)
                    }
                }
            }
            // Unknown-word fallback so every position stays reachable.
            val ch = kana.substring(st, st + 1)
            val unk = LNode(st, st + 1, ch, unknownId, unknownId, LATTICE_UNKNOWN_COST)
            endAt[st + 1].add(unk); startAt[st].add(unk)
        }
        // Forward: best[node] = min over prev ending at node.start.
        for (pos in 0 until n) {
            for (node in startAt[pos]) {
                var b = Int.MAX_VALUE
                if (pos == 0) {
                    b = rawConnectionCost(lastRightGroup, node.lid)
                } else {
                    for (prev in endAt[pos]) {
                        if (prev.best == Int.MAX_VALUE) continue
                        val c = prev.best + rawConnectionCost(prev.rid, node.lid)
                        if (c < b) b = c
                    }
                }
                if (b != Int.MAX_VALUE) node.best = b + node.wcost
            }
        }
        // Backward A*: partial paths from EOS; priority = node.best + suffix cost.
        class Partial(val node: LNode, val suffix: Int, val next: Partial?) {
            val f: Int get() = node.best + suffix
        }
        val pq = java.util.PriorityQueue<Partial>(compareBy { it.f })
        for (node in endAt[n]) {
            if (node.best == Int.MAX_VALUE) continue
            pq.add(Partial(node, rawConnectionCost(node.rid, 0) + eosPenalty(node.rid), null))
        }
        val results = ArrayList<ConversionCandidate>()
        val seen = HashSet<String>()
        var pops = 0
        while (pq.isNotEmpty() && results.size < 25 && pops < 20000) {
            val p = pq.poll(); pops++
            if (p.node.start == 0) {
                val segs = ArrayList<String>()
                var lastRid = p.node.rid
                var cur: Partial? = p
                while (cur != null) { segs.add(cur.node.surface); lastRid = cur.node.rid; cur = cur.next }
                val surface = segs.joinToString("")
                if (seen.add(surface)) {
                    results.add(ConversionCandidate(surface = surface, reading = kana, cost = p.f, segments = segs, rightId = lastRid))
                }
                continue
            }
            val connBase = p.node.wcost + p.suffix
            for (prev in endAt[p.node.start]) {
                if (prev.best == Int.MAX_VALUE) continue
                pq.add(Partial(prev, connBase + rawConnectionCost(prev.rid, p.node.lid), p))
            }
        }
        if (results.isEmpty()) return exactMatch(kana)
        return results
    }

    /**
     * Heuristic: detect if a kana reading is likely a loanword (foreign origin).
     * These readings should have katakana ranked higher.
     */
    private fun isLikelyLoanword(kana: String): Boolean {
        if (kana.length < 4) return false
        // Foreign-origin syllable patterns (rarely appear in native Japanese)
        val loanwordSyllables = listOf(
            "てぃ", "でぃ", "ふぁ", "ふぃ", "ふぇ", "ふぉ",
            "うぃ", "うぇ", "うぉ", "しぇ", "じぇ", "ちぇ",
            "つぁ", "つぃ", "つぇ", "つぉ", "ゔぁ", "ゔぃ", "ゔ",
        )
        for (syllable in loanwordSyllables) {
            if (kana.contains(syllable)) return true
        }
        // Readings ending with common loanword suffixes
        val loanwordSuffixes = listOf(
            "しょん", "にんぐ", "めんと", "ねす",
            "りてぃ", "ぶる", "とりー", "ありー",
            "てぃぶ", "なる", "いず", "いずむ",
            "ーしょん", "ーにんぐ", "ーめんと",
            "ーじ", "ーる", "ーと", "ーす", "ーど", "ーぷ",
            "ふぃっく", "ろじー", "ぐらふぃ",
        )
        for (suffix in loanwordSuffixes) {
            if (kana.endsWith(suffix) && kana.length >= suffix.length + 2) return true
        }
        // No dict entries at all for 5+ char reading → likely loanword
        if (kana.length >= 5 && dict[kana] == null) return true
        return false
    }

    /** Convert hiragana to katakana */
    private fun hiraganaToKatakana(hiragana: String): String {
        val sb = StringBuilder(hiragana.length)
        for (ch in hiragana) {
            sb.append(if (ch in '\u3041'..'\u3096') (ch + 0x60) else ch)
        }
        return sb.toString()
    }

    /** Convert hiragana to half-width katakana (半角カタカナ) */
    private fun toHalfWidthKatakana(hiragana: String): String {
        val sb = StringBuilder(hiragana.length * 2) // dakuten can expand
        for (ch in hiragana) {
            val kata = if (ch in '\u3041'..'\u3096') (ch + 0x60) else ch
            val hw = HALF_WIDTH_KATAKANA_MAP[kata]
            if (hw != null) {
                sb.append(hw)
            } else {
                sb.append(kata)
            }
        }
        return sb.toString()
    }


    // --- User learning persistence ---

    private fun loadUserBoost() {
        try {
            val store = storeProvider("nacre_user_dict")

            val data = store.getString("boost")
            if (data != null) {
                for (line in data.split('\n')) {
                    val parts = line.split('\t')
                    if (parts.size == 2) {
                        userBoost[parts[0]] = parts[1].toIntOrNull() ?: 0
                    }
                }
            }

            val learningData = store.getString("conversion_learning")
            if (learningData != null) {
                conversionLearning.putAll(ConversionLearning.parse(learningData))
                learningSeq.set(conversionLearning.values.maxOfOrNull { it.lastSeq } ?: 0L)
            }

            val bigramData = store.getString("bigram")
            if (bigramData != null) {
                for (line in bigramData.split('\n')) {
                    val parts = line.split('\t')
                    if (parts.size == 2) {
                        bigramBoost[parts[0]] = parts[1].toIntOrNull() ?: 0
                    }
                }
            }

            val trigramData = store.getString("trigram")
            if (trigramData != null) {
                for (line in trigramData.split('\n')) {
                    val parts = line.split('\t')
                    if (parts.size == 2) {
                        trigramBoost[parts[0]] = parts[1].toIntOrNull() ?: 0
                    }
                }
            }

            val historyData = store.getString("history")
            if (historyData != null) {
                for (line in historyData.split('\n')) {
                    if (line.isBlank()) continue
                    val parts = line.split('\t')
                    if (parts.size >= 2) {
                        recentHistory.add(ConversionCandidate(
                            surface = parts[0],
                            reading = parts[1],
                            cost = 0,
                        ))
                    }
                }
            }

            // English commit history: one committed word per line, newest
            // first (the order saveUserBoost writes). Re-validate on load
            // so a hand-edited or stale store can never inject strings the
            // recorder itself would have rejected.
            val englishHistoryData = store.getString("english_history")
            if (englishHistoryData != null) {
                synchronized(englishHistoryLock) {
                    for (line in englishHistoryData.split('\n')) {
                        val word = line.trim()
                        if (EnglishHistory.isRecordable(word) &&
                            recentEnglishHistory.none { it.equals(word, ignoreCase = true) }
                        ) {
                            recentEnglishHistory.add(word)
                            if (recentEnglishHistory.size >= maxHistory) break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("NacreDictionary", "Failed to load user boost", e)
        }
    }

    private fun saveUserBoost() {
        try {
            val store = storeProvider("nacre_user_dict")

            val data = userBoost.entries
                .sortedByDescending { it.value }
                .take(5000)
                .joinToString("\n") { "${it.key}\t${it.value}" }

            val bigramData = bigramBoost.entries
                .sortedByDescending { it.value }
                .take(3000)
                .joinToString("\n") { "${it.key}\t${it.value}" }

            val trigramData = trigramBoost.entries
                .sortedByDescending { it.value }
                .take(2000)
                .joinToString("\n") { "${it.key}\t${it.value}" }

            val historyData = recentHistory
                .take(maxHistory)
                .joinToString("\n") { "${it.surface}\t${it.reading}" }

            val englishHistoryData = synchronized(englishHistoryLock) {
                recentEnglishHistory.take(maxHistory).joinToString("\n")
            }

            store.putString("boost", data)
            store.putString(
                "conversion_learning",
                ConversionLearning.serialize(conversionLearning.values),
            )
            store.putString("bigram", bigramData)
            store.putString("trigram", trigramData)
            store.putString("history", historyData)
            store.putString("english_history", englishHistoryData)
        } catch (e: Exception) {
            Log.w("NacreDictionary", "Failed to save user boost", e)
        }
    }

    // --- User dictionary persistence ---

    private fun loadUserDictionary() {
        try {
            val store = storeProvider("nacre_user_dict")
            val data = store.getString("user_dictionary") ?: return
            for (line in data.split('\n')) {
                if (line.isBlank()) continue
                val parts = line.split('\t')
                if (parts.size >= 2) {
                    val reading = parts[0]
                    val surface = parts[1]
                    val comment = if (parts.size >= 3) parts[2] else ""
                    userDictionary.getOrPut(reading) { mutableListOf() }
                        .add(UserDictEntry(reading, surface, comment))
                }
            }
            Log.i("NacreDictionary", "User dictionary loaded: ${userDictionary.values.sumOf { it.size }} entries")
        } catch (e: Exception) {
            Log.w("NacreDictionary", "Failed to load user dictionary", e)
        }
    }

    private fun saveUserDictionary() {
        try {
            val store = storeProvider("nacre_user_dict")
            val data = userDictionary.values.flatten()
                .joinToString("\n") { "${it.reading}\t${it.surface}\t${it.comment}" }
            store.putString("user_dictionary", data)
        } catch (e: Exception) {
            Log.w("NacreDictionary", "Failed to save user dictionary", e)
        }
    }

    /**
     * Inject user dictionary entries into the main dict so they appear in conversion candidates.
     * User entries get a high priority (low cost).
     */
    private fun injectUserDictionary() {
        for ((reading, entries) in userDictionary) {
            val existing = dict.getOrPut(reading) { mutableListOf() }
            for (entry in entries) {
                if (existing.none { it.surface == entry.surface }) {
                    existing.add(DictEntry(
                        surface = entry.surface,
                        cost = 500, // High priority — user registered words
                        leftGroup = 0,
                        rightGroup = 0,
                    ))
                }
            }
        }
    }

    /**
     * Register a word in the user dictionary.
     * @param reading hiragana reading
     * @param surface the kanji/text surface form
     * @param comment optional memo
     */
    fun registerUserWord(readingRaw: String, surfaceRaw: String, comment: String = "") {
        // Strip the \t / \n that delimit the flat-file format — a multi-line surface
        // (e.g. a pasted address) would otherwise split/corrupt other entries.
        val reading = readingRaw.replace('\t', ' ').replace('\n', ' ').trim()
        val surface = surfaceRaw.replace('\t', ' ').replace('\n', ' ').trim()
        if (reading.isEmpty() || surface.isEmpty()) return
        val entries = userDictionary.getOrPut(reading) { mutableListOf() }
        if (entries.none { it.surface == surface }) {
            entries.add(UserDictEntry(reading, surface, comment))
            // Also inject into the live dict
            val existing = dict.getOrPut(reading) { mutableListOf() }
            if (existing.none { it.surface == surface }) {
                existing.add(DictEntry(surface = surface, cost = 500, leftGroup = 0, rightGroup = 0))
            }
            saveUserDictionary()
            bumpPredictStateVersion()
        }
    }

    /**
     * Remove a word from the user dictionary.
     */
    fun removeUserWord(reading: String, surface: String) {
        bumpPredictStateVersion()
        userDictionary[reading]?.removeAll { it.surface == surface }
        if (userDictionary[reading]?.isEmpty() == true) userDictionary.remove(reading)
        dict[reading]?.removeAll { it.surface == surface && it.cost == 500 }
        saveUserDictionary()
    }

    /** All registered user words (sorted by reading), for the 単語登録 management UI. */
    fun listUserWords(): List<UserDictEntry> =
        userDictionary.values.flatten().sortedBy { it.reading }

    /**
     * Re-read the user dictionary from prefs and re-inject into the live dict.
     * Lets edits made in the Settings screen (same prefs) take effect on the next
     * keyboard open without an IME restart.
     */
    fun reloadUserDictionary() {
        userDictionary.clear()
        loadUserDictionary()
        injectUserDictionary()
        bumpPredictStateVersion()
    }

    // --- Phrase memory persistence ---

    private fun loadPhraseMemory() {
        try {
            val store = storeProvider("nacre_user_dict")
            val data = store.getString("phrase_memory") ?: return
            for (line in data.split('\n')) {
                if (line.isBlank()) continue
                val parts = line.split('\t')
                if (parts.size >= 3) {
                    val reading = parts[0]
                    val surface = parts[1]
                    val count = parts[2].toIntOrNull() ?: 1
                    val epoch = if (parts.size >= 4) parts[3].toIntOrNull() ?: 0 else 0
                    val key = reading.take(2)
                    phraseMemory.getOrPut(key) { mutableListOf() }
                        .add(PhraseEntry(reading, surface, count, epoch))
                }
            }
            Log.i("NacreDictionary", "Phrase memory loaded: ${phraseMemory.values.sumOf { it.size }} phrases")
        } catch (e: Exception) {
            Log.w("NacreDictionary", "Failed to load phrase memory", e)
        }
    }

    private fun savePhraseMemory() {
        try {
            val store = storeProvider("nacre_user_dict")
            val data = phraseMemory.values.flatten()
                .sortedByDescending { it.count }
                .take(maxPhrases)
                .joinToString("\n") { "${it.reading}\t${it.surface}\t${it.count}\t${it.lastEpoch}" }
            store.putString("phrase_memory", data)
        } catch (e: Exception) {
            Log.w("NacreDictionary", "Failed to save phrase memory", e)
        }
    }

    /**
     * Record a committed phrase for phrase completion.
     * Only stores phrases of 4+ characters (shorter ones are handled by normal learning).
     */
    fun recordPhrase(reading: String, surface: String) {
        if (surface.length < 4) return
        bumpPredictStateVersion()
        val key = reading.take(2)
        val entries = phraseMemory.getOrPut(key) { mutableListOf() }
        val existing = entries.find { it.surface == surface }
        if (existing != null) {
            existing.count++
            existing.lastEpoch = learningEpoch
        } else {
            entries.add(PhraseEntry(reading, surface, 1, learningEpoch))
            // Evict oldest if over limit
            if (entries.size > 50) {
                entries.sortByDescending { it.count }
                while (entries.size > 40) entries.removeAt(entries.lastIndex)
            }
        }
        savePhraseMemory()
    }

    /**
     * Look up phrase completions for a partial reading.
     * Returns phrases whose reading starts with the given prefix.
     */
    fun findPhraseCompletions(readingPrefix: String, limit: Int = 5): List<ConversionCandidate> {
        if (readingPrefix.length < 2) return emptyList()
        val key = readingPrefix.take(2)
        val entries = phraseMemory[key] ?: return emptyList()
        return entries
            .filter { it.reading.startsWith(readingPrefix) && it.reading.length > readingPrefix.length }
            .sortedWith(compareByDescending<PhraseEntry> { it.count }.thenByDescending { it.lastEpoch })
            .take(limit)
            .map { entry ->
                val age = (learningEpoch - entry.lastEpoch).coerceAtLeast(0)
                val frequency = minOf(entry.count, 8) * 220
                val recency = maxOf(0, 50 - age) * 15
                val cost = maxOf(80, 2300 - frequency - recency)
                ConversionCandidate(surface = entry.surface, reading = entry.reading, cost = cost)
            }
    }

    // --- Frequency decay ---

    /**
     * Apply frequency decay to user boost scores.
     * Scores decay by 10% each epoch (session), preventing stale entries from dominating.
     */
    private fun applyFrequencyDecay() {
        val decayFactor = 0.9f
        val iterator = userBoost.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val decayed = (entry.value * decayFactor).toInt()
            if (decayed <= 0) {
                iterator.remove()
            } else {
                entry.setValue(decayed)
            }
        }
        // Also decay bigram/trigram boosts
        for (map in listOf(bigramBoost, trigramBoost)) {
            val iter = map.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                val decayed = (entry.value * decayFactor).toInt()
                if (decayed <= 0) iter.remove()
                else entry.setValue(decayed)
            }
        }
    }

    private fun saveEpoch() {
        try {
            val store = storeProvider("nacre_user_dict")
            learningEpoch = store.getInt("learning_epoch", 0) + 1
            store.putInt("learning_epoch", learningEpoch)
        } catch (e: Exception) {
            Log.w("NacreDictionary", "Failed to save epoch", e)
        }
    }
}

/**
 * Pure logic for the English commit history (surfaced by
 * [NacreDictionary.predictEnglish] step 7): which committed words are worth
 * remembering, how the list is maintained, and how history hits are costed
 * for a typed prefix. Extracted so it is unit-testable on a plain JVM, and
 * deliberately mirrors the Japanese side's historyMatchesFor() penalty
 * scheme so the two languages rank history the same way.
 */
internal object EnglishHistory {

    /** Base cost of the newest history hit, before penalties. */
    const val BASE_COST = 900

    /** Extra cost per step down the history list (older = weaker). */
    const val RECENCY_STEP = 150

    /** Max history hits surfaced per prefix (mirrors historyMatchesFor). */
    const val MAX_MATCHES = 3

    /**
     * A committable alphabet word: at least two ASCII letters, with an
     * internal apostrophe allowed ("don't"). Single letters, digits, code
     * tokens and mixed-script strings are not history material.
     */
    fun isRecordable(word: String): Boolean {
        if (word.length < 2) return false
        var letters = 0
        for (ch in word) {
            when {
                ch in 'a'..'z' || ch in 'A'..'Z' -> letters++
                ch == '\'' -> Unit
                else -> return false
            }
        }
        return letters >= 2
    }

    /**
     * Remember [word] at the front of [history] (newest first), dropping
     * any older entry for the same word (case-insensitive — the newest
     * committed case wins) and capping the list at [max]. Returns true if
     * the word was recorded.
     */
    fun record(history: MutableList<String>, word: String, max: Int): Boolean {
        if (!isRecordable(word)) return false
        history.removeAll { it.equals(word, ignoreCase = true) }
        history.add(0, word)
        while (history.size > max) history.removeAt(history.size - 1)
        return true
    }

    /**
     * History hits for [prefix]: entries whose lowercase form starts with
     * the lowercase prefix (exact matches included). Surfaces keep their
     * committed case. Cost = [BASE_COST] + a recency step + a penalty
     * proportional to the untyped remainder — high for 1-2 char prefixes,
     * lower for longer ones — mirroring historyMatchesFor(), so a long
     * history word cannot bury dictionary completions on a short prefix.
     */
    fun matchesFor(
        history: List<String>,
        prefix: String,
        limit: Int = MAX_MATCHES,
    ): List<ConversionCandidate> {
        if (prefix.isEmpty() || history.isEmpty()) return emptyList()
        val prefixLower = prefix.lowercase()
        val perCharPenalty = if (prefixLower.length <= 2) 2000 else 1200
        val results = ArrayList<ConversionCandidate>()
        for ((index, word) in history.withIndex()) {
            val lower = word.lowercase()
            if (!lower.startsWith(prefixLower)) continue
            val unmatched = lower.length - prefixLower.length
            results.add(
                ConversionCandidate(
                    surface = word,
                    reading = prefix,
                    cost = BASE_COST + index * RECENCY_STEP + unmatched * perCharPenalty,
                )
            )
            if (results.size >= limit) break
        }
        return results
    }
}
