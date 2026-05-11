package space.manus.nacre.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.manus.nacre.ai.KenLmJni
import space.manus.nacre.config.ConfigRepository
import space.manus.nacre.config.PresetProvider
import space.manus.nacre.config.ThemeProvider
import space.manus.nacre.ime.feedback.HapticManager
import space.manus.nacre.ime.feedback.SoundManager
import space.manus.nacre.ime.keyboard.KeyLighting
import java.io.File
import kotlin.math.roundToInt

private val NacreBackground = Color(0xFF1A1A2E)
private val NacreSurface = Color(0xFF16213E)
private val NacreAccent = Color(0xFF00D4AA)
private val NacreText = Color(0xFFE0E0E0)
private val NacreTextDim = Color(0xFF8888AA)

private data class ModelDiscovery(
    val kenLmPath: String? = null,
    val compactKenLmPath: String? = null,
    val llmPath: String? = null,
    val senseVoiceDir: String? = null,
    val vadPath: String? = null,
)

@Composable
private fun rememberModelDiscovery(downloader: space.manus.nacre.ai.ModelDownloader): State<ModelDiscovery> {
    return produceState(initialValue = ModelDiscovery(), downloader) {
        value = withContext(Dispatchers.IO) {
            ModelDiscovery(
                kenLmPath = downloader.getKenLmModelPath(),
                compactKenLmPath = downloader.getCompactKenLmModelPath(),
                llmPath = downloader.getLlmModelPath(),
                senseVoiceDir = downloader.getSenseVoiceModelDir(),
                vadPath = downloader.getVadModelPath(),
            )
        }
    }
}

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NacreSettingsScreen()
        }
    }
}

@Composable
fun NacreSettingsScreen() {
    val context = LocalContext.current
    val config = remember { ConfigRepository(context) }

    // Read crash log
    val crashLog = remember {
        try {
            val logDir = File(context.filesDir, "crash-logs")
            logDir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.firstOrNull()
                ?.readText()
        } catch (_: Exception) { null }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NacreBackground)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "Nacre",
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            color = NacreAccent,
        )

        Text(
            text = "Developer Keyboard",
            fontSize = 14.sp,
            color = NacreTextDim,
        )

        Spacer(modifier = Modifier.height(32.dp))

        // --- Setup ---
        SectionHeader("Setup")

        SettingsCard(
            title = "1. Enable Nacre",
            description = "Open system settings to enable Nacre as an input method",
            onClick = {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            },
        )

        Spacer(modifier = Modifier.height(8.dp))

        SettingsCard(
            title = "2. Select Nacre",
            description = "Switch to Nacre as your active keyboard",
            onClick = {
                val imm = context.getSystemService(InputMethodManager::class.java)
                imm.showInputMethodPicker()
            },
        )

        Spacer(modifier = Modifier.height(24.dp))

        // --- Sound ---
        SectionHeader("Sound")
        SoundSection(config, context)

        Spacer(modifier = Modifier.height(24.dp))

        // --- Haptics ---
        SectionHeader("Haptics")
        HapticSection(config, context)

        Spacer(modifier = Modifier.height(24.dp))

        // --- Lighting ---
        SectionHeader("Key Lighting")
        LightingSection(config, context)

        Spacer(modifier = Modifier.height(24.dp))

        // --- Layout ---
        SectionHeader("Layout")
        LayoutSection(config)

        Spacer(modifier = Modifier.height(24.dp))

        // --- Preset ---
        SectionHeader("Key Preset")
        PresetSection(config)

        Spacer(modifier = Modifier.height(24.dp))

        // --- Theme ---
        SectionHeader("Theme")
        ThemeSection(config, context)

        Spacer(modifier = Modifier.height(24.dp))

        // --- Auto Convert ---
        SectionHeader("Auto Convert")
        AutoConvertSection(config)

        Spacer(modifier = Modifier.height(24.dp))

        // --- AI Models ---
        SectionHeader("AI Models")
        StoragePermissionCard()
        Spacer(modifier = Modifier.height(8.dp))
        FirstRunModelsBanner()
        KenLmModelSection()
        Spacer(modifier = Modifier.height(8.dp))
        LlmModelSection()
        Spacer(modifier = Modifier.height(8.dp))
        WhisperModelSection()

        Spacer(modifier = Modifier.height(24.dp))

        // --- Cloud LLM (voice refinement) ---
        SectionHeader("Cloud LLM (voice refinement)")
        CloudLlmSection()

        Spacer(modifier = Modifier.height(24.dp))

        // --- Reset ---
        var showResetDialog by remember { mutableStateOf(false) }
        Button(
            onClick = { showResetDialog = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF442222),
                contentColor = Color(0xFFFF6B6B),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reset All Settings")
        }

        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = { Text("Reset Settings?") },
                text = { Text("All settings will be restored to defaults.") },
                confirmButton = {
                    TextButton(onClick = {
                        config.resetToDefaults()
                        showResetDialog = false
                    }) {
                        Text("Reset", color = Color(0xFFFF6B6B))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetDialog = false }) {
                        Text("Cancel")
                    }
                },
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "No internet permission. Your keystrokes stay on device.",
            fontSize = 12.sp,
            color = NacreTextDim,
        )

        // Crash log
        if (crashLog != null) {
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "Last Crash Log",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFFF6B6B),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A1A)),
            ) {
                Text(
                    text = crashLog,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = NacreText,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}

// --- Section Components ---

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = NacreAccent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    )
}

@Composable
private fun SoundSection(config: ConfigRepository, context: Context) {
    val soundPrefs = context.getSharedPreferences("nacre_sound", Context.MODE_PRIVATE)
    var selectedProfile by remember {
        mutableStateOf(
            soundPrefs.getString("profile", SoundManager.Profile.THOCK.name)
                ?: SoundManager.Profile.THOCK.name,
        )
    }
    var volume by remember {
        mutableStateOf(soundPrefs.getInt("volume", 70).toFloat())
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Sound Profile", color = NacreText, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (profile in SoundManager.Profile.entries) {
                    val isSelected = selectedProfile == profile.name
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) NacreAccent.copy(alpha = 0.2f) else Color.Transparent)
                            .then(
                                if (isSelected) Modifier.border(1.dp, NacreAccent, RoundedCornerShape(8.dp))
                                else Modifier,
                            )
                            .clickable {
                                selectedProfile = profile.name
                                soundPrefs
                                    .edit()
                                    .putString("profile", profile.name)
                                    .apply()
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = profile.displayName,
                            color = if (isSelected) NacreAccent else NacreTextDim,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Volume: ${volume.roundToInt()}%", color = NacreText, fontSize = 14.sp)
            Slider(
                value = volume,
                onValueChange = { volume = it },
                onValueChangeFinished = {
                    soundPrefs.edit().putInt("volume", volume.roundToInt()).apply()
                },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = NacreAccent,
                    activeTrackColor = NacreAccent,
                    inactiveTrackColor = NacreTextDim.copy(alpha = 0.3f),
                ),
            )
        }
    }
}

@Composable
private fun HapticSection(config: ConfigRepository, context: Context) {
    val hapticPrefs = context.getSharedPreferences("nacre_haptic", Context.MODE_PRIVATE)
    var selectedStrength by remember {
        mutableStateOf(
            hapticPrefs.getString("strength", HapticManager.Strength.MEDIUM.name)
                ?: HapticManager.Strength.MEDIUM.name,
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Haptic Strength", color = NacreText, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (strength in HapticManager.Strength.entries) {
                    val isSelected = selectedStrength == strength.name
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) NacreAccent.copy(alpha = 0.2f) else Color.Transparent)
                            .then(
                                if (isSelected) Modifier.border(1.dp, NacreAccent, RoundedCornerShape(8.dp))
                                else Modifier,
                            )
                            .clickable {
                                selectedStrength = strength.name
                                hapticPrefs
                                    .edit()
                                    .putString("strength", strength.name)
                                    .apply()
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = strength.name.lowercase()
                                .replaceFirstChar { it.uppercase() },
                            color = if (isSelected) NacreAccent else NacreTextDim,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LightingSection(config: ConfigRepository, context: Context) {
    val lightPrefs = context.getSharedPreferences("nacre_lighting", Context.MODE_PRIVATE)
    var selectedMode by remember {
        mutableStateOf(
            lightPrefs.getString("mode", KeyLighting.Mode.OFF.name)
                ?: KeyLighting.Mode.OFF.name,
        )
    }
    var hue by remember {
        mutableStateOf(lightPrefs.getFloat("hue", 170f))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Lighting Mode", color = NacreText, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))

            // Two rows for 7 modes
            for (row in KeyLighting.Mode.entries.chunked(4)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (mode in row) {
                        val isSelected = selectedMode == mode.name
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) NacreAccent.copy(alpha = 0.2f) else Color.Transparent)
                                .then(
                                    if (isSelected) Modifier.border(1.dp, NacreAccent, RoundedCornerShape(8.dp))
                                    else Modifier,
                                )
                                .clickable {
                                    selectedMode = mode.name
                                    lightPrefs
                                        .edit()
                                        .putString("mode", mode.name)
                                        .apply()
                                }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = mode.displayName,
                                color = if (isSelected) NacreAccent else NacreTextDim,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    // Fill remaining space if row has fewer items
                    repeat(4 - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (selectedMode != KeyLighting.Mode.OFF.name) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("Base Hue: ${hue.roundToInt()}\u00B0", color = NacreText, fontSize = 14.sp)
                Slider(
                    value = hue,
                    onValueChange = { hue = it },
                    onValueChangeFinished = {
                        lightPrefs.edit().putFloat("hue", hue).apply()
                    },
                    valueRange = 0f..360f,
                    colors = SliderDefaults.colors(
                        thumbColor = NacreAccent,
                        activeTrackColor = NacreAccent,
                        inactiveTrackColor = NacreTextDim.copy(alpha = 0.3f),
                    ),
                )
            }
        }
    }
}

@Composable
private fun LayoutSection(config: ConfigRepository) {
    var vAngle by remember { mutableStateOf(config.vAngle) }
    var keyboardHeight by remember { mutableStateOf(config.keyboardHeight.toFloat()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "V-Split Angle: ${vAngle.roundToInt()}\u00B0",
                color = NacreText,
                fontSize = 14.sp,
            )
            Slider(
                value = vAngle,
                onValueChange = { vAngle = it },
                onValueChangeFinished = { config.vAngle = vAngle },
                valueRange = 0f..30f,
                colors = SliderDefaults.colors(
                    thumbColor = NacreAccent,
                    activeTrackColor = NacreAccent,
                    inactiveTrackColor = NacreTextDim.copy(alpha = 0.3f),
                ),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                "Keyboard Height: ${keyboardHeight.roundToInt()}dp",
                color = NacreText,
                fontSize = 14.sp,
            )
            Slider(
                value = keyboardHeight,
                onValueChange = { keyboardHeight = it },
                onValueChangeFinished = { config.keyboardHeight = keyboardHeight.roundToInt() },
                valueRange = 180f..400f,
                colors = SliderDefaults.colors(
                    thumbColor = NacreAccent,
                    activeTrackColor = NacreAccent,
                    inactiveTrackColor = NacreTextDim.copy(alpha = 0.3f),
                ),
            )
        }
    }
}

@Composable
private fun PresetSection(config: ConfigRepository) {
    var selectedPreset by remember { mutableStateOf(config.selectedPreset) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            for (row in PresetProvider.PresetType.entries.chunked(3)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (preset in row) {
                        val isSelected = selectedPreset == preset.name
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) NacreAccent.copy(alpha = 0.2f) else Color.Transparent)
                                .then(
                                    if (isSelected) Modifier.border(1.dp, NacreAccent, RoundedCornerShape(8.dp))
                                    else Modifier,
                                )
                                .clickable {
                                    selectedPreset = preset.name
                                    config.selectedPreset = preset.name
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = preset.name,
                                color = if (isSelected) NacreAccent else NacreTextDim,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    repeat(3 - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun ThemeSection(config: ConfigRepository, context: Context) {
    var selectedTheme by remember { mutableStateOf(config.selectedTheme) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (theme in ThemeProvider.themes) {
                    val isSelected = selectedTheme.equals(theme.name, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(theme.background.toInt()))
                            .then(
                                if (isSelected) Modifier.border(2.dp, NacreAccent, RoundedCornerShape(8.dp))
                                else Modifier.border(1.dp, NacreTextDim.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
                            )
                            .clickable {
                                selectedTheme = theme.name
                                config.selectedTheme = theme.name
                                ThemeProvider.saveSelectedTheme(context, theme.name)
                            }
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = theme.name,
                            color = Color(theme.keyText.toInt()),
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoConvertSection(config: ConfigRepository) {
    val context = LocalContext.current
    val acPrefs = context.getSharedPreferences("nacre_auto_convert", Context.MODE_PRIVATE)
    var enabled by remember { mutableStateOf(acPrefs.getBoolean("auto_convert_enabled", true)) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto Convert", color = NacreText, fontSize = 14.sp)
                Text(
                    "-> to \u2192, != to \u2260, etc.",
                    color = NacreTextDim,
                    fontSize = 12.sp,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    acPrefs.edit().putBoolean("auto_convert_enabled", it).apply()
                    config.autoConvertEnabled = it
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = NacreAccent,
                    checkedTrackColor = NacreAccent.copy(alpha = 0.3f),
                ),
            )
        }
    }
}

@Composable
private fun StoragePermissionCard() {
    val context = LocalContext.current
    var hasAccess by remember {
        mutableStateOf(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                android.os.Environment.isExternalStorageManager()
            } else true
        )
    }
    // Re-check when resuming from settings
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasAccess = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    android.os.Environment.isExternalStorageManager()
                } else true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!hasAccess) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2020)),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "File access required",
                    color = Color(0xFFFF6666),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Allow file access to auto-detect models in Downloads folder",
                    color = NacreTextDim,
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                android.net.Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NacreAccent,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Grant File Access")
                }
            }
        }
    }
}

/**
 * Shown above the AI model cards on first launch (or whenever none of the
 * three AI models are present) as a quick explanation of the downloads
 * about to be offered below. Hidden once any AI model has landed.
 */
@Composable
private fun FirstRunModelsBanner() {
    val context = LocalContext.current
    val downloader = remember { space.manus.nacre.ai.ModelDownloader(context) }
    val discovery by rememberModelDiscovery(downloader)
    val anyPresent = discovery.kenLmPath != null ||
        discovery.compactKenLmPath != null ||
        discovery.llmPath != null ||
        discovery.senseVoiceDir != null
    if (anyPresent) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2A3E)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "AI機能を有効化",
                color = NacreAccent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "以下のカードから必要なモデルをダウンロードしてください。" +
                    "オフラインで動作し、会話内容は端末外に送信されません。",
                color = NacreTextDim,
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * Qwen 2.5 1.5B Instruct (Q4_K_M) — ~1.1GB. Powers voice-input cleanup.
 */
@Composable
private fun LlmModelSection() {
    val context = LocalContext.current
    val downloader = remember { space.manus.nacre.ai.ModelDownloader(context) }
    val discovery by rememberModelDiscovery(downloader)
    var modelPath by remember { mutableStateOf<String?>(null) }
    var modelSize by remember {
        mutableStateOf(modelPath?.let { java.io.File(it).length() / 1024 / 1024 } ?: 0L)
    }
    var downloading by remember { mutableStateOf(false) }
    var progressPct by remember { mutableStateOf(0) }
    var progressBytes by remember { mutableStateOf(0L to 0L) }

    // Re-check when resuming
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && modelPath == null) {
                val found = discovery.llmPath
                if (found != null) {
                    modelPath = found
                    modelSize = java.io.File(found).length() / 1024 / 1024
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(discovery.llmPath) {
        val found = discovery.llmPath
        if (modelPath == null && found != null) {
            modelPath = found
            modelSize = java.io.File(found).length() / 1024 / 1024
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Qwen 2.5 1.5B", color = NacreText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.width(8.dp))
                if (modelPath != null) {
                    Text("Ready", color = Color(0xFF4CAF50), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("Not found", color = Color(0xFFFF6666), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (modelPath != null) {
                Text("Voice input cleanup (${modelSize}MB)", color = NacreTextDim, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(2.dp))
                Text(modelPath!!, color = NacreTextDim.copy(alpha = 0.5f), fontSize = 10.sp, maxLines = 1)
            } else {
                Text(
                    "音声入力のLLM整文用（~1.1GB）。ダウンロード後、キーボード再起動で有効化。",
                    color = NacreTextDim,
                    fontSize = 12.sp,
                )
            }
            if (downloading) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { progressPct / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = NacreAccent,
                    trackColor = NacreTextDim.copy(alpha = 0.3f),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "${progressPct}% (${progressBytes.first / 1024 / 1024}/${progressBytes.second / 1024 / 1024}MB)",
                    color = NacreTextDim, fontSize = 11.sp,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    downloading = true
                    progressPct = 0
                    downloader.onProgress = { p ->
                        progressPct = p.percent
                        progressBytes = p.bytesDownloaded to p.totalBytes
                    }
                    downloader.downloadLlm { ok ->
                        downloading = false
                        downloader.onProgress = null
                        if (ok) {
                            val found = downloader.getLlmModelPath()
                            if (found != null) {
                                modelPath = found
                                modelSize = java.io.File(found).length() / 1024 / 1024
                                Toast.makeText(context, "Qwen model downloaded (${modelSize}MB). Restart keyboard.", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            Toast.makeText(context, "Download failed.", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                enabled = !downloading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NacreAccent,
                    contentColor = Color.Black,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (downloading) "Downloading..."
                    else if (modelPath != null) "Re-download"
                    else "Download (~1.1GB)"
                )
            }
        }
    }
}

@Composable
private fun KenLmModelSection() {
    val context = LocalContext.current
    val downloader = remember { space.manus.nacre.ai.ModelDownloader(context) }
    val discovery by rememberModelDiscovery(downloader)
    var modelPath by remember { mutableStateOf<String?>(null) }
    var modelSize by remember { mutableStateOf(modelPath?.let { java.io.File(it).length() / 1024 / 1024 } ?: 0L) }
    var compactPath by remember { mutableStateOf<String?>(null) }
    var compactSize by remember { mutableStateOf(compactPath?.let { java.io.File(it).length() / 1024 / 1024 } ?: 0L) }
    var importing by remember { mutableStateOf(false) }
    var downloadingCompact by remember { mutableStateOf(false) }
    var progressPct by remember { mutableStateOf(0) }

    // Re-check model when returning from permission settings
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && modelPath == null) {
                val found = discovery.kenLmPath
                if (found != null) {
                    modelPath = found
                    modelSize = java.io.File(found).length() / 1024 / 1024
                }
                val foundCompact = discovery.compactKenLmPath
                if (compactPath == null && foundCompact != null) {
                    compactPath = foundCompact
                    compactSize = java.io.File(foundCompact).length() / 1024 / 1024
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(discovery.kenLmPath, discovery.compactKenLmPath) {
        val found = discovery.kenLmPath
        if (modelPath == null && found != null) {
            modelPath = found
            modelSize = java.io.File(found).length() / 1024 / 1024
        }
        val foundCompact = discovery.compactKenLmPath
        if (compactPath == null && foundCompact != null) {
            compactPath = foundCompact
            compactSize = java.io.File(foundCompact).length() / 1024 / 1024
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        Thread {
            try {
                val modelsDir = downloader.getModelsDir()
                val tmpFile = File(modelsDir, "japanese-5gram.klm.tmp")
                val destFile = File(modelsDir, "japanese-5gram.klm")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmpFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 65536)
                    }
                }
                tmpFile.renameTo(destFile)
                val sizeMb = destFile.length() / 1024 / 1024
                val path = destFile.absolutePath
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    modelPath = path
                    modelSize = sizeMb
                    importing = false
                    Toast.makeText(context, "KenLM model imported (${sizeMb}MB). Restart keyboard to activate.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    importing = false
                    Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // --- Compact 3-gram (recommended default) ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("KenLM 3-gram (compact)", color = NacreText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.width(8.dp))
                if (compactPath != null) {
                    Text("Ready", color = Color(0xFF4CAF50), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("Not found", color = Color(0xFFFF6666), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (compactPath != null) {
                Text("日本語変換（推奨モデル, ${compactSize}MB）", color = NacreTextDim, fontSize = 12.sp)
            } else {
                Text("日本語変換の推奨モデル（~161MB、ほとんどのユーザーはこれで十分）", color = NacreTextDim, fontSize = 12.sp)
            }
            if (downloadingCompact) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progressPct / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = NacreAccent,
                    trackColor = NacreTextDim.copy(alpha = 0.3f),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text("${progressPct}%", color = NacreTextDim, fontSize = 11.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    downloadingCompact = true
                    progressPct = 0
                    downloader.onProgress = { p -> progressPct = p.percent }
                    downloader.downloadCompactKenLm { ok ->
                        downloadingCompact = false
                        downloader.onProgress = null
                        if (ok) {
                            val found = downloader.getCompactKenLmModelPath()
                            if (found != null) {
                                compactPath = found
                                compactSize = java.io.File(found).length() / 1024 / 1024
                                Toast.makeText(context, "Compact KenLM downloaded (${compactSize}MB). Restart keyboard.", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            Toast.makeText(context, "Download failed.", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                enabled = !downloadingCompact,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NacreAccent,
                    contentColor = Color.Black,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (downloadingCompact) "Downloading..."
                    else if (compactPath != null) "Re-download"
                    else "Download (~161MB)"
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            androidx.compose.material3.HorizontalDivider(color = NacreTextDim.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(16.dp))

            // --- Full 5-gram (power users, sideload via file picker) ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("KenLM 5-gram (full)", color = NacreText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.width(8.dp))
                if (modelPath != null) {
                    Text("Ready", color = Color(0xFF4CAF50), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("Optional", color = NacreTextDim, fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (modelPath != null) {
                Text("上級向け高精度モデル（${modelSize}MB, compact より優先読込）", color = NacreTextDim, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(2.dp))
                Text(modelPath!!, color = NacreTextDim.copy(alpha = 0.5f), fontSize = 10.sp, maxLines = 1)
            } else {
                Text("/sdcard/Download/ に japanese-5gram.klm を置くか、下のボタンから選択（~561MB）", color = NacreTextDim, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { launcher.launch(arrayOf("*/*")) },
                enabled = !importing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NacreSurface,
                    contentColor = NacreAccent,
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, NacreAccent),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (importing) "Importing..."
                    else if (modelPath != null) "Replace Model"
                    else "Import Model (.klm)"
                )
            }
        }
    }
}

@Composable
private fun WhisperModelSection() {
    val context = LocalContext.current
    val downloader = remember { space.manus.nacre.ai.ModelDownloader(context) }
    val discovery by rememberModelDiscovery(downloader)
    var modelDir by remember { mutableStateOf<String?>(null) }
    var vadPath by remember { mutableStateOf<String?>(null) }
    val isReady = modelDir != null && vadPath != null
    var modelSize by remember {
        mutableStateOf(
            modelDir?.let {
                val model = java.io.File(it, "model.int8.onnx")
                if (model.exists()) model.length() / 1024 / 1024 else 0L
            } ?: 0L
        )
    }

    // Re-check model when returning from permission settings
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && !isReady) {
                val foundDir = discovery.senseVoiceDir
                val foundVad = discovery.vadPath
                if (foundDir != null) {
                    modelDir = foundDir
                    modelSize = java.io.File(foundDir, "model.int8.onnx").let {
                        if (it.exists()) it.length() / 1024 / 1024 else 0L
                    }
                }
                if (foundVad != null) vadPath = foundVad
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(discovery.senseVoiceDir, discovery.vadPath) {
        val foundDir = discovery.senseVoiceDir
        if (modelDir == null && foundDir != null) {
            modelDir = foundDir
            modelSize = java.io.File(foundDir, "model.int8.onnx").let {
                if (it.exists()) it.length() / 1024 / 1024 else 0L
            }
        }
        val foundVad = discovery.vadPath
        if (vadPath == null && foundVad != null) {
            vadPath = foundVad
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SenseVoice", color = NacreText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.width(8.dp))
                if (isReady) {
                    Text("Ready", color = Color(0xFF4CAF50), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("Not found", color = Color(0xFFFF6666), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (isReady) {
                Text("Offline voice input — ja/en/zh/ko (${modelSize}MB)", color = NacreTextDim, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(2.dp))
                Text(modelDir!!, color = NacreTextDim.copy(alpha = 0.5f), fontSize = 10.sp, maxLines = 1)
                if (vadPath != null) {
                    Text("VAD: $vadPath", color = NacreTextDim.copy(alpha = 0.5f), fontSize = 10.sp, maxLines = 1)
                }
            } else {
                Text(
                    "Place SenseVoice model directory + silero_vad.onnx in /sdcard/Download/",
                    color = NacreTextDim, fontSize = 12.sp,
                )
                if (modelDir == null) {
                    Text("Model: not found", color = Color(0xFFFF6666), fontSize = 11.sp)
                }
                if (vadPath == null) {
                    Text("VAD: not found", color = Color(0xFFFF6666), fontSize = 11.sp)
                }
            }
        }
    }
}

/**
 * API key inputs for the cloud LLM voice-refinement chain.
 *
 * Keys are stored per-device via CloudLlmConfig (SharedPreferences, not
 * backed up). When multiple keys are set, VoiceInputManager tries them in
 * priority order: Qwen Max → Gemini Pro → DeepSeek V3. None of them are
 * required — if all are blank the app falls back to the on-device Qwen 1.5B.
 */
@Composable
private fun CloudLlmSection() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "音声入力の整文に使うクラウドLLM。キーを貼ると優先順（Qwen → Gemini → DeepSeek）で試し、全て失敗時のみローカルQwenにフォールバック。",
                color = NacreTextDim,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "キー未設定時はオフライン（ローカルQwen）のみで動作。キーは端末内にのみ保存されます。",
                color = NacreTextDim.copy(alpha = 0.8f),
                fontSize = 11.sp,
            )

            Spacer(modifier = Modifier.height(16.dp))
            ApiKeyField(
                label = "OpenRouter (Qwen3 Next 80B, 無料)",
                hint = "openrouter.ai — メール登録だけ、完全無料",
                signupUrl = "https://openrouter.ai/settings/keys",
                initial = { space.manus.nacre.ai.cloud.CloudLlmConfig.qwenMaxKey(context).orEmpty() },
                onSave = { space.manus.nacre.ai.cloud.CloudLlmConfig.setQwenMaxKey(context, it) },
            )
            Spacer(modifier = Modifier.height(12.dp))
            ApiKeyField(
                label = "Gemini 2.5 Flash (Google AI Studio)",
                hint = "ai.google.dev — 無料約1500/日（Proは無料枠ゼロなので Flash 固定）",
                signupUrl = "https://aistudio.google.com/apikey",
                initial = { space.manus.nacre.ai.cloud.CloudLlmConfig.geminiKey(context).orEmpty() },
                onSave = { space.manus.nacre.ai.cloud.CloudLlmConfig.setGeminiKey(context, it) },
            )
            Spacer(modifier = Modifier.height(12.dp))
            ApiKeyField(
                label = "DeepSeek V3 (DeepSeek direct)",
                hint = "platform.deepseek.com — 実質無制限無料",
                signupUrl = "https://platform.deepseek.com/api_keys",
                initial = { space.manus.nacre.ai.cloud.CloudLlmConfig.deepSeekKey(context).orEmpty() },
                onSave = { space.manus.nacre.ai.cloud.CloudLlmConfig.setDeepSeekKey(context, it) },
            )
        }
    }
}

@Composable
private fun ApiKeyField(
    label: String,
    hint: String,
    signupUrl: String,
    initial: () -> String,
    onSave: (String) -> Unit,
) {
    val context = LocalContext.current
    var value by remember { mutableStateOf(initial()) }
    var revealed by remember { mutableStateOf(false) }
    val hasKey = value.isNotBlank()

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = NacreText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.width(8.dp))
            if (hasKey) {
                Text("●", color = Color(0xFF4CAF50), fontSize = 10.sp)
            } else {
                Text("—", color = NacreTextDim, fontSize = 10.sp)
            }
        }
        Text(hint, color = NacreTextDim, fontSize = 11.sp)
        Spacer(modifier = Modifier.height(4.dp))

        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (revealed || value.isEmpty())
                androidx.compose.ui.text.input.VisualTransformation.None
            else
                androidx.compose.ui.text.input.PasswordVisualTransformation(),
            placeholder = { Text("sk-…  /  key…", color = NacreTextDim.copy(alpha = 0.5f), fontSize = 12.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(
                color = NacreText, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NacreAccent,
                unfocusedBorderColor = NacreTextDim.copy(alpha = 0.3f),
                cursorColor = NacreAccent,
            ),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    onSave(value.trim())
                    Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NacreAccent,
                    contentColor = Color.Black,
                ),
                modifier = Modifier.weight(1f),
            ) { Text("Save", fontSize = 12.sp) }
            Button(
                onClick = { revealed = !revealed },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NacreSurface,
                    contentColor = NacreAccent,
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, NacreAccent),
                modifier = Modifier.weight(1f),
            ) { Text(if (revealed) "Hide" else "Show", fontSize = 12.sp) }
            Button(
                onClick = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(signupUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NacreSurface,
                    contentColor = NacreTextDim,
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, NacreTextDim.copy(alpha = 0.4f)),
                modifier = Modifier.weight(1f),
            ) { Text("Get key", fontSize = 12.sp) }
        }
    }
}

/**
 * Auto-import a model file from /sdcard/Download/ using MediaScanner to get a content URI.
 * Returns the destination File if import succeeded, null otherwise.
 */
@Composable
fun SettingsCard(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = NacreText,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                fontSize = 14.sp,
                color = NacreTextDim,
            )
        }
    }
}
