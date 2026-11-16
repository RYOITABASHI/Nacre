package space.manus.nacre.ime.keyboard

import android.content.Intent
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.manus.nacre.ime.NacreInputMethodService
import space.manus.nacre.ime.input.VoiceEditPolicy
import space.manus.nacre.ime.input.WritingTools

// Same palette colors as CommandPalette (kept local to this file, like
// ClipboardPanel keeps its own).
private val PanelBackground = Color(0xFF0F0F23)
private val ItemBg = Color(0xFF2A2A4A)
private val ButtonBg = Color(0xFF16213E)
private val ItemText = Color(0xFFE0E0E0)
private val ItemSubtext = Color(0xFF6666AA)
private val AccentColor = Color(0xFF00D4AA)

/**
 * How the Writing Tools panel was entered from the command palette.
 * [target] is the snapshot captured when the palette opened (selection,
 * or the paragraph before the cursor); null means there was nothing to
 * rewrite and the panel only explains why. [initialOp] runs immediately
 * when set. [availability] is the model state at palette-open time; the
 * panel re-checks before every run. [sessionId] binds the capture to
 * the input session it was taken in (InputEngine.inputSessionId): the
 * keyboard view survives field switches, so the panel re-validates it
 * before running and before applying, and refuses to touch any other
 * field — above all a password field.
 */
data class WritingLaunch(
    val target: WritingTools.Target?,
    val initialOp: WritingTools.Op?,
    val politeStyle: WritingTools.PoliteStyle,
    val availability: WritingTools.ModelAvailability,
    val selectionTooLong: Boolean,
    val sessionId: Int,
)

private sealed interface WritingUiState {
    data object Explain : WritingUiState
    data object NoModel : WritingUiState
    data object LoadingModel : WritingUiState
    data class Running(val op: WritingTools.Op) : WritingUiState
    data class Result(val op: WritingTools.Op, val result: String) : WritingUiState
    data class Failed(val message: String) : WritingUiState
}

/**
 * AI Writing Tools panel (inside the command palette). Flow: choose an
 * operation → the LOCAL LLM rewrites the captured target → the result
 * is shown for review → 適用 replaces the target in the editor. The
 * result is NEVER auto-applied, and 適用 re-verifies that the editor
 * still holds exactly the captured target before touching anything
 * (the Speak to Edit safety contract).
 */
@Composable
fun WritingToolsPanel(
    service: NacreInputMethodService,
    launch: WritingLaunch,
    onBack: () -> Unit,
    onDismissAll: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var runId by remember { mutableStateOf(0) }
    var state by remember {
        mutableStateOf<WritingUiState>(
            when {
                launch.availability == WritingTools.ModelAvailability.NO_MODEL ->
                    WritingUiState.NoModel
                launch.target == null -> WritingUiState.Explain
                launch.initialOp != null -> WritingUiState.Running(launch.initialOp)
                else -> WritingUiState.Explain
            },
        )
    }
    val target = launch.target

    /**
     * The capture is valid only in the input session it was taken in,
     * and never in a password field. Checked before any editor read and
     * before any model run: the panel's Compose state can outlive the
     * field (the user can tap another field while the palette is open).
     */
    fun editorChanged(): Boolean =
        service.inputEngine.inputSessionId != launch.sessionId ||
            service.inputEngine.isPasswordField

    fun launchTransform(op: WritingTools.Op, myRun: Int) {
        val t = target ?: return
        scope.launch {
            val raw = withContext(Dispatchers.Default) {
                service.voiceInputManager.transformForWriting(
                    t.text,
                    WritingTools.instructionFor(op, launch.politeStyle),
                )
            }
            if (runId != myRun) return@launch // superseded or cancelled
            if (editorChanged()) {
                state = WritingUiState.Failed("入力先が変わったため、中止しました。")
                return@launch
            }
            val cleaned = raw?.let { WritingTools.cleanResult(it) }
            state = if (cleaned == null) {
                WritingUiState.Failed("生成に失敗しました。時間をおいてもう一度お試しください。")
            } else {
                WritingUiState.Result(op, cleaned)
            }
        }
    }

    fun runOp(op: WritingTools.Op) {
        if (target == null) return
        if (editorChanged()) {
            state = WritingUiState.Failed("入力先が変わったため、中止しました。")
            return
        }
        val myRun = ++runId
        when (service.voiceInputManager.writingModelAvailability()) {
            WritingTools.ModelAvailability.NO_MODEL -> state = WritingUiState.NoModel
            WritingTools.ModelAvailability.READY -> {
                state = WritingUiState.Running(op)
                launchTransform(op, myRun)
            }
            WritingTools.ModelAvailability.NEEDS_LOAD -> {
                state = WritingUiState.LoadingModel
                service.voiceInputManager.loadLocalModelForWriting { ok ->
                    if (runId != myRun) return@loadLocalModelForWriting
                    if (editorChanged()) {
                        state = WritingUiState.Failed("入力先が変わったため、中止しました。")
                        return@loadLocalModelForWriting
                    }
                    if (ok) {
                        state = WritingUiState.Running(op)
                        launchTransform(op, myRun)
                    } else {
                        state = WritingUiState.Failed(
                            "AIモデルを読み込めませんでした。時間をおいてもう一度お試しください。",
                        )
                    }
                }
            }
        }
    }

    fun cancelRun() {
        // Abandon the run: invalidate the in-flight callback / coroutine
        // result so nothing is shown or applied. The generation itself
        // is NOT cancelled at the service — the slot is shared with
        // voice refinement and carries no ownership, so a service-level
        // cancel could kill a voice generation (see
        // VoiceInputManager.transformForWriting). The abandoned
        // generation ends on its own; until then the service simply
        // reports busy, which voice refinement already degrades around.
        runId++
        onBack()
    }

    fun applyResult(result: String) {
        val t = target ?: return
        // Session + password re-check BEFORE any editor read: applying
        // into a different field (or reading a password field) must
        // never happen, even if that field holds identical text.
        if (editorChanged()) {
            state = WritingUiState.Failed(
                "入力先が変わったため、適用を中止しました（元のテキストはそのままです）。",
            )
            return
        }
        val ic = service.currentInputConnection
        if (ic == null) {
            state = WritingUiState.Failed("入力先に接続できませんでした。")
            return
        }
        val applied = when (t) {
            is WritingTools.Target.Selection -> applyToSelection(ic, t.text, result)
            is WritingTools.Target.Paragraph -> applyToParagraph(ic, t.text, result)
        }
        if (applied) {
            onDismissAll()
        } else {
            state = WritingUiState.Failed(
                "文章が変わったため、適用を中止しました（元のテキストはそのままです）。",
            )
        }
    }

    LaunchedEffect(Unit) {
        launch.initialOp?.let { op ->
            if (target != null &&
                launch.availability != WritingTools.ModelAvailability.NO_MODEL
            ) {
                runOp(op)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 340.dp)
            .background(PanelBackground)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            text = "✦ AIライティング",
            color = AccentColor,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
        if (target != null) {
            Text(
                text = "対象: ${target.text.replace("\n", " ").take(48)}" +
                    if (target.text.length > 48) "…" else "",
                color = ItemSubtext,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }

        when (val s = state) {
            WritingUiState.Explain -> {
                Text(
                    text = when {
                        launch.selectionTooLong ->
                            "選択テキストが長すぎます（${WritingTools.MAX_TARGET_CHARS}文字まで）。" +
                                "範囲を短くしてからお試しください。"
                        target == null ->
                            "対象のテキストがありません。文章を入力するか、" +
                                "書き換えたい部分を選択してから開いてください。"
                        else ->
                            "操作を選んでください。"
                    },
                    color = ItemText,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
                if (target != null) {
                    for (op in WritingTools.Op.values()) {
                        WritingOpRow(op) { runOp(op) }
                    }
                }
                WritingButtonRow {
                    WritingButton("戻る") { onBack() }
                }
            }

            WritingUiState.NoModel -> {
                Text(
                    text = "AIライティングには整文AIモデル（Qwen 2.5 / Qwen3）が必要です。" +
                        "設定の「AI Models」からダウンロードしてください。" +
                        "処理はすべて端末内で行われるため、ダウンロード後はオフラインで使えます。",
                    color = ItemText,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
                WritingButtonRow {
                    WritingButton("設定を開く") {
                        try {
                            val intent = Intent().apply {
                                setClassName(
                                    service.packageName,
                                    "space.manus.nacre.ui.settings.SettingsActivity",
                                )
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            service.startActivity(intent)
                        } catch (_: Exception) {
                            // Settings activity not available
                        }
                        onDismissAll()
                    }
                    WritingButton("戻る") { onBack() }
                }
            }

            WritingUiState.LoadingModel -> {
                Text(
                    text = "AIモデルを読み込み中…（初回は時間がかかります）",
                    color = ItemText,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
                WritingButtonRow {
                    WritingButton("キャンセル") { cancelRun() }
                }
            }

            is WritingUiState.Running -> {
                Text(
                    text = "生成中…（${s.op.label}）",
                    color = ItemText,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
                WritingButtonRow {
                    WritingButton("キャンセル") { cancelRun() }
                }
            }

            is WritingUiState.Result -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 170.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(ItemBg)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(text = s.result, color = ItemText, fontSize = 13.sp)
                }
                if (target != null && s.result == target.text) {
                    Text(
                        text = "変更はありませんでした。",
                        color = ItemSubtext,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                WritingButtonRow {
                    WritingButton("適用", accent = true) { applyResult(s.result) }
                    WritingButton("やり直し") { runOp(s.op) }
                    WritingButton("やめる") { onBack() }
                }
            }

            is WritingUiState.Failed -> {
                Text(
                    text = s.message,
                    color = ItemText,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
                WritingButtonRow {
                    WritingButton("戻る") { onBack() }
                }
            }
        }
    }
}

@Composable
private fun WritingOpRow(op: WritingTools.Op, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(ItemBg)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(text = op.label, color = ItemText, fontSize = 13.sp)
    }
}

@Composable
private fun WritingButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun WritingButton(
    label: String,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (accent) AccentColor else ButtonBg)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = if (accent) Color(0xFF0F0F23) else ItemText,
            fontSize = 13.sp,
        )
    }
}

/**
 * Replace the current selection with [result], but only while the
 * editor's selection is still exactly [original] (the Speak to Edit
 * guard). commitText replaces the selection as a single edit.
 */
private fun applyToSelection(
    ic: InputConnection,
    original: String,
    result: String,
): Boolean {
    val current = try {
        ic.getSelectedText(0)?.toString()
    } catch (_: Exception) {
        null
    }
    if (!VoiceEditPolicy.isSelectionIntact(current, original)) return false
    return try {
        ic.commitText(result, 1)
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * Replace the captured paragraph — an exact suffix of the text before
 * the cursor — with [result]. The suffix is re-read and verified
 * first; the delete + commit run inside one batch edit so editors
 * with undo treat the replacement as a single step.
 *
 * The selection must be COLLAPSED: with an active selection,
 * commitText would replace the selected text as well — text this
 * flow never captured. (The suffix check alone cannot see it: text
 * before the selection start still ends with the paragraph.)
 */
private fun applyToParagraph(
    ic: InputConnection,
    original: String,
    result: String,
): Boolean {
    val extracted = try {
        ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
    } catch (_: Exception) {
        null
    } ?: return false
    if (!WritingTools.isCollapsedSelection(extracted.selectionStart, extracted.selectionEnd)) {
        return false
    }
    val now = try {
        ic.getTextBeforeCursor(original.length, 0)?.toString()
    } catch (_: Exception) {
        null
    }
    if (!WritingTools.paragraphIntact(now, original)) return false
    return try {
        ic.beginBatchEdit()
        try {
            ic.deleteSurroundingText(original.length, 0)
            ic.commitText(result, 1)
        } finally {
            ic.endBatchEdit()
        }
        true
    } catch (_: Exception) {
        false
    }
}
