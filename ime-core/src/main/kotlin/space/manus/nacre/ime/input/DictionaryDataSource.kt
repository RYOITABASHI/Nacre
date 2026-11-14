package space.manus.nacre.ime.input

import android.content.Context
import java.io.InputStream

/**
 * Data-source seam for [NacreDictionary].
 *
 * The dictionary engine needs exactly two things from the Android runtime:
 * bundled asset streams, and a small persistent key-value store for learning
 * data. Routing both through these interfaces lets the engine run on a plain
 * JVM (unit tests, the conversion benchmark) without touching any scoring
 * code. Production behavior is unchanged: [NacreDictionary]'s Context
 * constructor wires the same AssetManager / SharedPreferences access the
 * engine always used, just behind these interfaces.
 */

/** Opens a bundled dictionary asset (e.g. "dict/mozc_dict.bin") as a stream. */
fun interface AssetOpener {
    /** @throws java.io.IOException (e.g. FileNotFoundException) when the asset is absent. */
    fun open(name: String): InputStream
}

/**
 * Minimal persistent key-value store for learning data (user boost scores,
 * user dictionary, phrase memory, learning epoch). One instance per logical
 * store name, mirroring the SharedPreferences files used in production
 * ("nacre_user_dict", "nacre_mozc").
 */
interface DictionaryKeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getInt(key: String, defaultValue: Int): Int
    fun putInt(key: String, value: Int)
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
}

/** [DictionaryKeyValueStore] backed by a named SharedPreferences file. */
class SharedPreferencesStore(context: Context, name: String) : DictionaryKeyValueStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getInt(key: String, defaultValue: Int): Int = prefs.getInt(key, defaultValue)

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        prefs.getBoolean(key, defaultValue)
}
