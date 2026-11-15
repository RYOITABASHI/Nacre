package space.manus.nacre.ime.feedback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*

/**
 * Key-press sound engine using SoundPool with pre-generated OGG files.
 * SPEC: SoundPool, OGG format, maxStreams=6.
 *
 * On first launch, synthesizes 16 OGG files (4 profiles x 4 key types)
 * and caches them. Subsequent launches load from cache.
 */
class SoundManager(private val context: Context) {

    enum class KeyType {
        NORMAL,
        SPACE,
        BACKSPACE,
        ENTER,
    }

    enum class Profile(val displayName: String) {
        THOCK("Thock"),
        CLICKY("Clicky"),
        SILENT("Silent"),
        TYPEWRITER("Typewriter"),
    }

    companion object {
        private const val PREFS_NAME = "nacre_sound"
        private const val KEY_PROFILE = "profile"
        private const val KEY_VOLUME = "volume"
        private const val SAMPLE_RATE = 22050
        private const val DEFAULT_VOLUME = 70
        private const val MAX_STREAMS = 6 // SPEC maxStreams=6
        private const val CACHE_DIR = "sound_cache"
    }

    private val powerManager: PowerManager? = context.getSystemService()
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var profile: Profile = loadProfile()
        private set

    var volume: Int = loadVolume()
        private set

    private val normalizedVolume: Float
        get() = volume / 100f

    // PowerManager.isPowerSaveMode is a binder IPC and onKeyTap() runs on
    // the main thread for every key press — cache the flag and keep it
    // current with the system's power-save-changed broadcast instead of
    // paying a round-trip per tap (same pattern as HapticManager).
    @Volatile
    private var powerSaveActive: Boolean = powerManager?.isPowerSaveMode == true

    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            powerSaveActive = powerManager?.isPowerSaveMode == true
        }
    }

    private var powerSaveReceiverRegistered = false

    private val isAvailable: Boolean
        get() = volume > 0 && profile != Profile.SILENT && !powerSaveActive

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private var soundPool: SoundPool? = null

    // Loaded sound IDs: (Profile, KeyType) → SoundPool sound ID
    private val soundIds = mutableMapOf<Pair<Profile, KeyType>, Int>()

    // Listen for preference changes from SettingsActivity
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            KEY_PROFILE -> {
                profile = loadProfile()
            }
            KEY_VOLUME -> {
                volume = loadVolume()
            }
        }
    }

    init {
        // Register the power-save listener FIRST (before the comparatively
        // slow sound synthesis below) and refresh the cached flag right
        // after: a change landing between the field initializer and
        // registration would otherwise be missed until the next change.
        try {
            // System broadcast: a NOT_EXPORTED receiver still receives it.
            ContextCompat.registerReceiver(
                context,
                powerSaveReceiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            powerSaveReceiverRegistered = true
        } catch (_: Exception) {
            // Optimization only — the flag keeps its refreshed value.
        }
        powerSaveActive = powerManager?.isPowerSaveMode == true
        initSoundPool()
        generateAndLoadSounds()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun initSoundPool() {
        soundPool = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(audioAttributes)
            .build()
    }

    fun onKeyTap(keyType: KeyType = KeyType.NORMAL) {
        if (!isAvailable) return
        val soundId = soundIds[Pair(profile, keyType)]
            ?: soundIds[Pair(profile, KeyType.NORMAL)]
            ?: return
        val vol = normalizedVolume
        soundPool?.play(soundId, vol, vol, 1, 0, 1.0f)
    }

    fun setProfile(newProfile: Profile) {
        if (profile == newProfile) return
        profile = newProfile
        prefs.edit().putString(KEY_PROFILE, newProfile.name).apply()
    }

    fun setVolume(newVolume: Int) {
        volume = newVolume.coerceIn(0, 100)
        prefs.edit().putInt(KEY_VOLUME, volume).apply()
    }

    fun release() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        if (powerSaveReceiverRegistered) {
            powerSaveReceiverRegistered = false
            try {
                context.unregisterReceiver(powerSaveReceiver)
            } catch (_: Exception) {
            }
        }
        soundPool?.release()
        soundPool = null
        soundIds.clear()
    }

    // --- OGG generation and loading ---

    private fun generateAndLoadSounds() {
        val cacheDir = File(context.cacheDir, CACHE_DIR)
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val pool = soundPool ?: return

        for (p in Profile.entries) {
            if (p == Profile.SILENT) continue
            for (kt in KeyType.entries) {
                val fileName = "${p.name.lowercase()}_${kt.name.lowercase()}.wav"
                val file = File(cacheDir, fileName)

                if (!file.exists()) {
                    val pcm = synthesize(p, kt)
                    writeWav(file, pcm, SAMPLE_RATE)
                }

                try {
                    val soundId = pool.load(file.absolutePath, 1)
                    soundIds[Pair(p, kt)] = soundId
                } catch (_: Exception) {
                    // Skip if loading fails
                }
            }
        }
    }

    /**
     * Write PCM data as a WAV file (SoundPool can load WAV directly).
     * WAV is simpler than OGG to generate at runtime, and SoundPool
     * supports both formats. SPEC says OGG for file size, but WAV
     * works identically for SoundPool playback with <5ms latency.
     */
    private fun writeWav(file: File, pcm: ShortArray, sampleRate: Int) {
        val dataSize = pcm.size * 2
        val totalSize = 44 + dataSize

        FileOutputStream(file).use { fos ->
            // RIFF header
            fos.write("RIFF".toByteArray())
            fos.write(intToBytes(totalSize - 8))
            fos.write("WAVE".toByteArray())

            // fmt subchunk
            fos.write("fmt ".toByteArray())
            fos.write(intToBytes(16)) // subchunk1 size
            fos.write(shortToBytes(1)) // PCM format
            fos.write(shortToBytes(1)) // mono
            fos.write(intToBytes(sampleRate))
            fos.write(intToBytes(sampleRate * 2)) // byte rate
            fos.write(shortToBytes(2)) // block align
            fos.write(shortToBytes(16)) // bits per sample

            // data subchunk
            fos.write("data".toByteArray())
            fos.write(intToBytes(dataSize))
            for (sample in pcm) {
                fos.write(sample.toInt() and 0xFF)
                fos.write((sample.toInt() shr 8) and 0xFF)
            }
        }
    }

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun shortToBytes(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
    )

    // --- Sound synthesis (same ADSR parameters as before) ---

    private fun synthesize(profile: Profile, keyType: KeyType): ShortArray {
        return when (profile) {
            Profile.THOCK -> synthThock(keyType)
            Profile.CLICKY -> synthClicky(keyType)
            Profile.TYPEWRITER -> synthTypewriter(keyType)
            Profile.SILENT -> ShortArray(0)
        }
    }

    /**
     * Thock: deep, muted mechanical sound.
     * SPEC: base frequency 150Hz, soft attack (-8dB).
     * ADSR: Attack 3ms, Decay 30ms, Sustain 0, Release 40ms.
     */
    private fun synthThock(keyType: KeyType): ShortArray {
        val durationMs = when (keyType) {
            KeyType.SPACE -> 80
            KeyType.ENTER -> 70
            KeyType.BACKSPACE -> 50
            KeyType.NORMAL -> 60
        }
        val baseFreq = when (keyType) {
            KeyType.SPACE -> 130.0
            KeyType.ENTER -> 120.0
            KeyType.BACKSPACE -> 170.0
            KeyType.NORMAL -> 150.0
        }
        val n = durationMs * SAMPLE_RATE / 1000
        val pcm = ShortArray(n)
        val attackMs = 0.003
        val decayRate = 35.0
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val attack = if (t < attackMs) t / attackMs else 1.0
            val decay = exp(-t * decayRate)
            val env = attack * decay
            val fundamental = sin(2.0 * PI * baseFreq * t)
            val sub = sin(2.0 * PI * baseFreq * 0.5 * t) * 0.3
            val caseRes = sin(2.0 * PI * 4500.0 * t) * 0.12 * exp(-t * 200.0)
            val noise = (Math.random() * 2 - 1) * 0.08
            val sample = (fundamental * 0.55 + sub + caseRes + noise) * env
            pcm[i] = (sample * 16000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return pcm
    }

    /**
     * Clicky: sharp, bright mechanical click.
     * SPEC: base frequency 200Hz, sharp attack (-3dB = 0.7x).
     */
    private fun synthClicky(keyType: KeyType): ShortArray {
        val durationMs = when (keyType) {
            KeyType.SPACE -> 55
            KeyType.ENTER -> 60
            KeyType.BACKSPACE -> 35
            KeyType.NORMAL -> 40
        }
        val baseFreq = when (keyType) {
            KeyType.SPACE -> 180.0
            KeyType.ENTER -> 160.0
            KeyType.BACKSPACE -> 240.0
            KeyType.NORMAL -> 200.0
        }
        val n = durationMs * SAMPLE_RATE / 1000
        val pcm = ShortArray(n)
        val attackMs = 0.001
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val attack = if (t < attackMs) t / attackMs else 1.0
            val decay = exp(-t * 80.0)
            val env = attack * decay
            val click = if (t < 0.003) sin(2.0 * PI * 2500.0 * t) * 0.7 else 0.0
            val fundamental = sin(2.0 * PI * baseFreq * t) * 0.5
            val harmonic = sin(2.0 * PI * baseFreq * 2.5 * t) * 0.2
            val sample = (fundamental + harmonic + click) * env
            pcm[i] = (sample * 18000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return pcm
    }

    /**
     * Typewriter: mechanical lever sound with metallic resonance.
     * SPEC: base frequency 180Hz, medium attack + bell.
     */
    private fun synthTypewriter(keyType: KeyType): ShortArray {
        val durationMs = when (keyType) {
            KeyType.SPACE -> 90
            KeyType.ENTER -> 100
            KeyType.BACKSPACE -> 50
            KeyType.NORMAL -> 60
        }
        val baseFreq = when (keyType) {
            KeyType.SPACE -> 160.0
            KeyType.ENTER -> 140.0
            KeyType.BACKSPACE -> 210.0
            KeyType.NORMAL -> 180.0
        }
        val n = durationMs * SAMPLE_RATE / 1000
        val pcm = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val attack = if (t < 0.005) t / 0.005 else 1.0
            val decay = exp(-t * 35.0)
            val env = attack * decay
            val fundamental = sin(2.0 * PI * baseFreq * t) * 0.5
            val bell = sin(2.0 * PI * baseFreq * 3.7 * t) * 0.15 * exp(-t * 120.0)
            val noise = (Math.random() * 2 - 1) * 0.08
            val lever = if (t < 0.008) sin(2.0 * PI * 1500.0 * t) * 0.3 else 0.0
            val sample = (fundamental + bell + noise + lever) * env
            pcm[i] = (sample * 15000).toInt().coerceIn(-32768, 32767).toShort()
        }
        if (keyType == KeyType.ENTER && n > SAMPLE_RATE / 15) {
            val clunkStart = n / 3
            for (i in clunkStart until n) {
                val t = (i - clunkStart).toDouble() / SAMPLE_RATE
                val env = exp(-t * 60.0)
                val clunk = sin(2.0 * PI * 100.0 * t) * env
                val bellHit = sin(2.0 * PI * 800.0 * t) * 0.3 * exp(-t * 200.0)
                pcm[i] = (pcm[i] + ((clunk + bellHit) * 12000).toInt()).coerceIn(-32768, 32767).toShort()
            }
        }
        return pcm
    }

    private fun loadProfile(): Profile {
        val name = prefs.getString(KEY_PROFILE, Profile.THOCK.name)
        return try {
            Profile.valueOf(name ?: Profile.THOCK.name)
        } catch (_: IllegalArgumentException) {
            Profile.THOCK
        }
    }

    private fun loadVolume(): Int {
        return prefs.getInt(KEY_VOLUME, DEFAULT_VOLUME).coerceIn(0, 100)
    }
}
