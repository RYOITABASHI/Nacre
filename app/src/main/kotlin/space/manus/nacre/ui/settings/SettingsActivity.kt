package space.manus.nacre.ui.settings

import android.content.ComponentName
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
import androidx.compose.foundation.horizontalScroll
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.manus.nacre.BuildConfig
import space.manus.nacre.ai.KenLmJni
import space.manus.nacre.update.ApkInstaller
import space.manus.nacre.update.UpdateChecker
import space.manus.nacre.update.UpdateInfo
import space.manus.nacre.config.ConfigRepository
import space.manus.nacre.ime.foldable.LayoutMode
import space.manus.nacre.ime.foldable.LayoutSelector
import space.manus.nacre.config.PresetProvider
import space.manus.nacre.config.ThemeProvider
import space.manus.nacre.ime.feedback.HapticManager
import space.manus.nacre.ime.feedback.SoundManager
import space.manus.nacre.ime.keyboard.KeyLighting
import space.manus.nacre.ime.pointer.NacrePointerAccessibilityService
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
                senseVoiceDir = downloader.getPreferredAsrModelDir(),
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
    val defaultModelDownloader = remember { space.manus.nacre.ai.ModelDownloader(context) }

    LaunchedEffect(defaultModelDownloader) {
        defaultModelDownloader.ensureDefaultModelsDownloaded()
    }

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

        // --- App Update ---
        SectionHeader("App Update")
        AppUpdateSection()

        Spacer(modifier = Modifier.height(24.dp))

        // --- Flex Pointer ---
        SectionHeader("Flex Pointer")
        FlexPointerAccessSection(context)

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

        // --- Conversion engine (#13 Mozc native) ---
        SectionHeader("変換エンジン")
        MozcNativeSection()

        Spacer(modifier = Modifier.height(24.dp))

        // --- User dictionary (単語登録) ---
        SectionHeader("単語登録")
        WordRegistrationSection()

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

        // --- Cloud ASR (voice recognition) ---
        SectionHeader("Cloud ASR (voice recognition)")
        CloudAsrSection()

        Spacer(modifier = Modifier.height(24.dp))

        // --- Voice rewrite mode ---
        SectionHeader("Voice rewrite mode")
        VoiceRewriteSection()

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
private fun FlexPointerAccessSection(context: Context) {
    var enabled by remember { mutableStateOf(isFlexPointerAccessibilityEnabled(context)) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                enabled = isFlexPointerAccessibilityEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsCard(
        title = if (enabled) "Nacre Flex Pointer is enabled" else "Enable Nacre Flex Pointer",
        description = if (enabled) {
            "Use Ptr on the keyboard to open the Flex Mode pointer pad"
        } else {
            "Enable the accessibility service so Ptr can move an overlay cursor and send taps"
        },
        onClick = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
    )
}

private fun isFlexPointerAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(
        context,
        NacrePointerAccessibilityService::class.java,
    ).flattenToString()
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
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
    val context = LocalContext.current
    // Same process as the IME, so the IME picks this up on the next keyboard open.
    // This only affects the foldable cover / sub-display — the main display is untouched.
    val layoutPrefs = context.getSharedPreferences("nacre_layout", Context.MODE_PRIVATE)
    var coverFlick12 by remember {
        mutableStateOf(
            layoutPrefs.getString(LayoutSelector.KEY_SUB_DISPLAY_MODE, null) ==
                LayoutMode.FlickInput12Key.name,
        )
    }
    var vAngle by remember { mutableStateOf(config.vAngle) }
    var keyboardHeight by remember { mutableStateOf(config.keyboardHeight.toFloat()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // iOS-style 12-key for the foldable COVER display only. When off, the
            // cover falls back to CompactQwerty; the main display is never affected.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("\u30AB\u30D0\u30FC\u753B\u9762\u306712\u30AD\u30FC\u5165\u529B\uFF08\u30D5\u30EA\u30C3\u30AF/\u9023\u6253\uFF09", color = NacreText, fontSize = 14.sp)
                    Text(
                        "\u6298\u308A\u305F\u305F\u307F\u6642\u306E\u30AB\u30D0\u30FC\u753B\u9762\u306E\u307FiOS\u98A8\u30C6\u30F3\u30AD\u30FC\u3002\u30E1\u30A4\u30F3\u753B\u9762\u306F\u5909\u66F4\u306A\u3057",
                        color = NacreTextDim,
                        fontSize = 12.sp,
                    )
                }
                Switch(
                    checked = coverFlick12,
                    onCheckedChange = {
                        coverFlick12 = it
                        val mode = if (it) LayoutMode.FlickInput12Key.name else LayoutMode.CompactQwerty.name
                        layoutPrefs.edit().putString(LayoutSelector.KEY_SUB_DISPLAY_MODE, mode).apply()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = NacreAccent,
                        checkedTrackColor = NacreAccent.copy(alpha = 0.3f),
                    ),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

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
private fun MozcNativeSection() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("nacre_mozc", Context.MODE_PRIVATE)
    var enabled by remember { mutableStateOf(prefs.getBoolean("enabled", false)) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Mozcネイティブ変換（実験）", color = NacreText, fontSize = 14.sp)
                Text(
                    "Mozc本体エンジンで変換。OFFで従来エンジン。問題があれば自動フォールバック",
                    color = NacreTextDim,
                    fontSize = 12.sp,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    prefs.edit().putBoolean("enabled", it).apply()
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

// ── User dictionary (単語登録) ──────────────────────────────────────────────
// Reads/writes the same "nacre_user_dict" prefs the IME's NacreDictionary uses
// (tab-separated reading\tsurface\tcomment, newline-joined). Setting "dirty" makes
// the IME reload on the next keyboard open. IME + Settings share one process.

private fun loadUserWords(prefs: android.content.SharedPreferences): List<Pair<String, String>> {
    val data = prefs.getString("user_dictionary", null) ?: return emptyList()
    return data.split('\n').mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val p = line.split('\t')
        if (p.size >= 2) p[0] to p[1] else null
    }
}

private fun saveUserWords(prefs: android.content.SharedPreferences, list: List<Pair<String, String>>) {
    // Strip the \t / \n delimiters so a multi-line surface (pasted address) can't
    // split one entry into several and corrupt the flat file.
    fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').trim()
    val data = list.joinToString("\n") { "${clean(it.first)}\t${clean(it.second)}\t" }
    prefs.edit().putString("user_dictionary", data).putBoolean("dirty", true).apply()
}

private fun loadClipboardTexts(context: android.content.Context): List<String> {
    val prefs = context.getSharedPreferences("nacre_clipboard", Context.MODE_PRIVATE)
    val json = prefs.getString("history", null) ?: return emptyList()
    return try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { arr.getJSONObject(it).getString("text") }
            .filter { it.isNotBlank() }
            .take(8)
    } catch (_: Exception) {
        emptyList()
    }
}

@Composable
private fun WordRegistrationSection() {
    val context = LocalContext.current
    val udPrefs = remember { context.getSharedPreferences("nacre_user_dict", Context.MODE_PRIVATE) }
    var entries by remember { mutableStateOf(loadUserWords(udPrefs)) }
    var reading by remember { mutableStateOf("") }
    var surface by remember { mutableStateOf("") }
    val clips = remember { loadClipboardTexts(context) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "読み（かな）と表記を登録すると、変換候補の先頭に出ます。名前・住所・メールなどに。",
                color = NacreTextDim,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = reading,
                onValueChange = { reading = it },
                label = { Text("読み（かな）", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = surface,
                onValueChange = { surface = it },
                label = { Text("表記（変換後）", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (clips.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("クリップボードから表記を流用:", color = NacreTextDim, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (clip in clips) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(NacreBackground)
                                .clickable { surface = clip.replace('\n', ' ').replace('\t', ' ').trim() }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                clip.take(20).replace('\n', ' '),
                                color = NacreText,
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = {
                    val r = reading.trim()
                    val s = surface.trim()
                    if (r.isNotEmpty() && s.isNotEmpty()) {
                        val next = loadUserWords(udPrefs).toMutableList()
                        if (next.none { it.first == r && it.second == s }) next.add(r to s)
                        saveUserWords(udPrefs, next)
                        entries = next
                        reading = ""
                        surface = ""
                    }
                },
                enabled = reading.isNotBlank() && surface.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = NacreAccent),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("登録", color = NacreBackground)
            }

            if (entries.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("登録済み（${entries.size}）", color = NacreTextDim, fontSize = 11.sp)
                for ((r, s) in entries) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("$s 　(${r})", color = NacreText, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            val next = loadUserWords(udPrefs).filterNot { it.first == r && it.second == s }
                            saveUserWords(udPrefs, next)
                            entries = next
                        }) {
                            Text("削除", color = Color(0xFFFF6666), fontSize = 12.sp)
                        }
                    }
                }
            }
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
 * Qwen 2.5 1.5B Instruct (Q4_K_M) — default local voice-input cleanup model
 * (b3500-compatible, fits memory; replaced the unloadable Gemma 4).
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
                    "音声入力のLLM整文用。デフォルトで自動取得し、ダウンロード後はキーボード再起動で有効化。",
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
                                Toast.makeText(context, "Qwen 2.5 model downloaded (${modelSize}MB). Restart keyboard.", Toast.LENGTH_LONG).show()
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
                    else "Download Qwen 2.5"
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
    var downloadingFull by remember { mutableStateOf(false) }
    var progressPct by remember { mutableStateOf(0) }
    var fullProgressPct by remember { mutableStateOf(0) }

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

            // --- Full 5-gram (power users, direct download or sideload) ---
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
                Text("最高精度の日本語変換モデル（~561MB）。compact より優先して読み込みます。", color = NacreTextDim, fontSize = 12.sp)
            }
            if (downloadingFull) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { fullProgressPct / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = NacreAccent,
                    trackColor = NacreTextDim.copy(alpha = 0.3f),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text("${fullProgressPct}%", color = NacreTextDim, fontSize = 11.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    downloadingFull = true
                    fullProgressPct = 0
                    downloader.onProgress = { p -> fullProgressPct = p.percent }
                    downloader.downloadKenLm { ok ->
                        downloadingFull = false
                        downloader.onProgress = null
                        if (ok) {
                            val found = downloader.getKenLmModelPath()
                            if (found != null) {
                                modelPath = found
                                modelSize = java.io.File(found).length() / 1024 / 1024
                                Toast.makeText(context, "KenLM 5-gram downloaded (${modelSize}MB). Restart keyboard.", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            Toast.makeText(context, "Download failed.", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                enabled = !downloadingFull && !importing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NacreAccent,
                    contentColor = Color.Black,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (downloadingFull) "Downloading..."
                    else if (modelPath != null) "Re-download full"
                    else "Download full (~561MB)"
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { launcher.launch(arrayOf("*/*")) },
                enabled = !importing && !downloadingFull,
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
            modelDir?.let { downloader.getSenseVoiceModelFile(it)?.length()?.div(1024)?.div(1024) } ?: 0L
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
                    modelSize = downloader.getSenseVoiceModelFile(foundDir)?.length()?.div(1024)?.div(1024) ?: 0L
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
            modelSize = downloader.getSenseVoiceModelFile(foundDir)?.length()?.div(1024)?.div(1024) ?: 0L
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
 * required — if all are blank the app falls back to the on-device Qwen model.
 */
@Composable
private fun VoiceRewriteSection() {
    val context = LocalContext.current
    var activeId by remember { mutableStateOf(space.manus.nacre.ai.RefinePresets.activeId(context)) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "音声入力をこのモードで自動書き換えします。整文=通常の整形。英訳・要約・敬語・箇条書きに切替可。",
                color = NacreTextDim,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (preset in space.manus.nacre.ai.RefinePresets.PRESETS) {
                    val selected = preset.id == activeId
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) NacreAccent else NacreBackground)
                            .clickable {
                                activeId = preset.id
                                space.manus.nacre.ai.RefinePresets.setActiveId(context, preset.id)
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            preset.label,
                            color = if (selected) Color.Black else NacreText,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudAsrSection() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "高精度なクラウド音声認識（Groq Whisper large-v3-turbo 等）。キーを貼ると、発話の確定時に音声をクラウドへ送って文字起こしし、端末側の結果を置き換えます。Typeless 級の精度に近づきます。",
                color = NacreTextDim,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "⚠️ 音声データがクラウドに送信されます（テキストではなく音声そのもの）。キー未設定なら完全オフライン（端末内 ReazonSpeech）のまま。失敗時は自動で端末側にフォールバック。",
                color = NacreTextDim.copy(alpha = 0.8f),
                fontSize = 11.sp,
            )
            Spacer(modifier = Modifier.height(16.dp))
            ApiKeyField(
                label = "Groq Whisper (large-v3-turbo, 無料枠あり)",
                hint = "console.groq.com — OpenAI互換 /audio/transcriptions。高速・安価",
                signupUrl = "https://console.groq.com/keys",
                initial = { space.manus.nacre.ai.cloud.CloudAsrConfig.apiKey(context).orEmpty() },
                onSave = { space.manus.nacre.ai.cloud.CloudAsrConfig.setApiKey(context, it) },
            )

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                "パーソナル辞書（名前・専門語・社内語）",
                color = NacreText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "クラウドASRがこれらの語を優先して認識します（読点・改行区切り）。",
                color = NacreTextDim,
                fontSize = 11.sp,
            )
            Spacer(modifier = Modifier.height(6.dp))
            var vocab by remember {
                mutableStateOf(space.manus.nacre.ai.cloud.CloudAsrConfig.vocab(context).orEmpty())
            }
            OutlinedTextField(
                value = vocab,
                onValueChange = {
                    vocab = it
                    space.manus.nacre.ai.cloud.CloudAsrConfig.setVocab(context, it)
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("例: 板橋、Nacre、Kotlin、forkpty", color = NacreTextDim, fontSize = 13.sp) },
                minLines = 2,
                maxLines = 4,
                textStyle = androidx.compose.ui.text.TextStyle(color = NacreText, fontSize = 13.sp),
            )

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                "プロバイダ詳細（任意）",
                color = NacreText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "OpenAI互換の /audio/transcriptions を持つ別プロバイダに切替できます。空欄で Groq の既定値。",
                color = NacreTextDim,
                fontSize = 11.sp,
            )
            Spacer(modifier = Modifier.height(6.dp))
            var model by remember {
                mutableStateOf(space.manus.nacre.ai.cloud.CloudAsrConfig.model(context))
            }
            OutlinedTextField(
                value = model,
                onValueChange = {
                    model = it
                    space.manus.nacre.ai.cloud.CloudAsrConfig.setModel(context, it)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("モデル", color = NacreTextDim, fontSize = 12.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = NacreText, fontSize = 13.sp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            var baseUrl by remember {
                mutableStateOf(space.manus.nacre.ai.cloud.CloudAsrConfig.baseUrl(context))
            }
            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    space.manus.nacre.ai.cloud.CloudAsrConfig.setBaseUrl(context, it)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("ベースURL", color = NacreTextDim, fontSize = 12.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = NacreText, fontSize = 13.sp),
            )
        }
    }
}

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

@Composable
private fun AppUpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var available by remember { mutableStateOf<UpdateInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Current: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                fontSize = 14.sp,
                color = NacreText,
            )
            if (status.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = status, fontSize = 13.sp, color = NacreTextDim)
            }
            if (busy && progress > 0f) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = NacreAccent,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            val update = available
            if (update == null) {
                Button(
                    onClick = {
                        busy = true
                        status = "確認中…"
                        scope.launch {
                            try {
                                val info = withContext(Dispatchers.IO) {
                                    UpdateChecker.check(BuildConfig.VERSION_CODE)
                                }
                                if (info == null) {
                                    status = "最新です"
                                } else {
                                    status = "新しいビルド ${info.versionCode} が利用できます"
                                    available = info
                                }
                            } catch (e: Exception) {
                                status = "確認に失敗: ${e.message}"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = NacreAccent),
                ) { Text("更新を確認") }
            } else {
                Button(
                    onClick = {
                        if (!ApkInstaller.canInstall(context)) {
                            status = "設定で「不明なアプリのインストール」を許可してください"
                            context.startActivity(ApkInstaller.unknownSourcesSettingsIntent(context))
                            return@Button
                        }
                        busy = true
                        progress = 0f
                        status = "ダウンロード中…"
                        scope.launch {
                            try {
                                var lastPct = -1
                                val apk = withContext(Dispatchers.IO) {
                                    ApkInstaller.download(context, update.apkUrl, update.apkSize) { p ->
                                        // onProgress fires on the IO thread; marshal the
                                        // Compose state write to Main, throttled to whole %.
                                        val pct = (p * 100).toInt()
                                        if (pct != lastPct) {
                                            lastPct = pct
                                            scope.launch(Dispatchers.Main) { progress = p }
                                        }
                                    }
                                }
                                // Launch the system installer on the main thread from
                                // this foreground activity (reliable on Android 16/Samsung).
                                status = "インストールを開始します…"
                                ApkInstaller.install(context, apk)
                            } catch (e: Exception) {
                                status = "更新に失敗: ${e.message}"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = NacreAccent),
                ) { Text("ダウンロードして更新 (${update.versionCode})") }
            }
        }
    }
}
