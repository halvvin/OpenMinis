package com.openminis.app.ui.floating

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.automation.AutomationPrefs
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.floating.FloatingAssistantViewModel

/**
 * [T-floating-system] The Compose content rendered inside the
 * FloatingAssistantService's WindowManager overlay. Direct callbacks (onDrag,
 * onFocusNeeded, onResize) update LayoutParams without Compose recomposition
 * lag — 60fps drag/focus/resize.
 *
 * [F-A1] Renders the REAL agent loop: per-tool status rows, a Stop button
 * while the loop runs, and APPROVE / DENY buttons when ACCEPT mode pauses a
 * tool call. Tap a message to copy it.
 */
@Composable
fun SystemFloatingAssistantContent(
    viewModel: FloatingAssistantViewModel,
    providerRepository: com.openminis.app.data.repository.ProviderRepository?,
    onDrag: (Float, Float) -> Unit,
    onFocusNeeded: (Boolean) -> Unit,
    onResize: (Int, Int) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { AutomationPrefs.get(context) }
    val messages by viewModel.messages.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val modelEntries by viewModel.modelEntries.collectAsState()
    val selectedEntryId by viewModel.selectedEntryId.collectAsState()
    val currentAction by viewModel.currentAction.collectAsState()
    val pendingApproval by viewModel.pendingApproval.collectAsState()

    var isOpen by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var modelMenu by remember { mutableStateOf(false) }
    var providerMenu by remember { mutableStateOf(false) }
    var selProviderLabel by remember { mutableStateOf("") }

    val density = context.resources.displayMetrics.density
    val minW = (prefs.assistantW * density).toInt().coerceAtLeast((240 * density).toInt())
    val minH = (prefs.assistantH * density).toInt().coerceAtLeast((300 * density).toInt())

    LaunchedEffect(isOpen) {
        if (!isOpen) {
            onFocusNeeded(false)
            onResize(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        } else {
            onResize(minW, minH)
        }
    }

    // Update provider label when model changes
    LaunchedEffect(selectedEntryId) {
        selProviderLabel = runCatching {
            providerRepository?.config?.value?.instances
                .orEmpty()
                .firstOrNull { it.id == (modelEntries.firstOrNull { it.id == selectedEntryId }?.providerInstanceId) }?.label
                ?: ""
        }.getOrDefault("")
    }

    fun copyMessage(text: String) {
        runCatching {
            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("assistant", text))
            android.widget.Toast.makeText(context, "کپی شد", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    MaterialTheme {
        if (!isOpen) {
            // Collapsed bubble — drag via WindowManager
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.onPrimary, CircleShape)
                    .clickable { isOpen = true }
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x, dragAmount.y)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("🤖", style = MaterialTheme.typography.titleLarge)
            }
        } else {
            // Expanded panel
            Card(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // ── Header: drag + model selector + close ──
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    onDrag(dragAmount.x, dragAmount.y)
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("🤖", style = MaterialTheme.typography.titleMedium)
                        // Two-step: API selector
                        Box {
                            Text(
                                selProviderLabel.ifBlank { "انتخاب API" },
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                modifier = Modifier
                                    .clickable { providerMenu = true }
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                            DropdownMenu(expanded = providerMenu,
                                onDismissRequest = { providerMenu = false }) {
                                val providers = modelEntries
                                    .groupBy { e ->
                                        runCatching { providerRepository?.config?.value?.instances }
                                            .getOrNull()?.firstOrNull { it.id == e.providerInstanceId }?.label
                                            ?: e.model.provider
                                    }
                                if (providers.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("(لیست خالی — اول API تنظیم کن)") },
                                        onClick = { providerMenu = false })
                                }
                                providers.forEach { (provider, entries) ->
                                    DropdownMenuItem(
                                        text = { Text(provider, maxLines = 1) },
                                        onClick = {
                                            selProviderLabel = provider
                                            providerMenu = false
                                            viewModel.selectModel(entries.first().id)
                                        })
                                }
                            }
                        }
                        // Model selector
                        Box {
                            Text(
                                modelEntries.firstOrNull { it.id == selectedEntryId }?.model?.displayName
                                    ?: "انتخاب مدل",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                modifier = Modifier
                                    .clickable { modelMenu = true }
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                            DropdownMenu(expanded = modelMenu,
                                onDismissRequest = { modelMenu = false }) {
                                val filtered = if (selProviderLabel.isNotBlank()) {
                                    modelEntries.filter { e ->
                                        val lbl = runCatching { providerRepository?.config?.value?.instances }
                                            .getOrNull()?.firstOrNull { it.id == e.providerInstanceId }?.label
                                            ?: e.model.provider
                                        lbl == selProviderLabel
                                    }
                                } else modelEntries
                                if (filtered.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("(مدلی برای این API نیست)") },
                                        onClick = { modelMenu = false })
                                }
                                filtered.forEach { e ->
                                    DropdownMenuItem(
                                        text = { Text(e.model.displayName, maxLines = 1) },
                                        onClick = { viewModel.selectModel(e.id); modelMenu = false })
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        // [B8] Execution-mode gear inside the panel — the
                        // AUTO/PLANNING/ACCEPT selector used to live only in
                        // app settings; cycling it here keeps the three modes
                        // reachable mid-conversation.
                        val modeLabels = listOf("خودکار", "برنامه‌ریزی", "تایید")
                        var modeMenu by remember { mutableStateOf(false) }
                        Box {
                            Text(
                                "⚙ " + modeLabels[prefs.executionMode.coerceIn(0, 2)],
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .clickable { modeMenu = true }
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 5.dp, vertical = 3.dp),
                            )
                            DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                                modeLabels.forEachIndexed { idx, label ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            prefs.executionMode = idx
                                            modeMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        if (busy) {
                            // [F-A1] Stop the agent loop.
                            IconButton(onClick = { viewModel.stop() }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Stop, contentDescription = "توقف", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        IconButton(onClick = { isOpen = false }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "بستن")
                        }
                    }
                    // ── Messages ──
                    LazyColumn(
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {                        items(messages, key = { "${it.role}-${System.identityHashCode(it)}" }) { m ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = when {
                                        m.isError -> MaterialTheme.colorScheme.errorContainer
                                        m.role == "tool" -> MaterialTheme.colorScheme.surfaceVariant
                                        else -> MaterialTheme.colorScheme.surface
                                    }
                                ),
                            ) {
                                Text(
                                    (when (m.role) {
                                        "user" -> "🧑 "
                                        "tool" -> "🔧 "
                                        else -> "🤖 "
                                    }) + m.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .padding(6.dp)
                                        .clickable { copyMessage(m.text) },
                                    maxLines = 40)
                            }
                        }
                        if (busy) {
                            item {
                                Row(
                                    modifier = Modifier.padding(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        currentAction ?: "در حال پاسخ…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    // ── [F-A1] ACCEPT-mode approval gate ──
                    if (pendingApproval != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "اجازه‌ی «${pendingApproval}»؟",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                            )
                            TextButton(onClick = { viewModel.approvePendingTool(false) }) {
                                Text("رد", color = MaterialTheme.colorScheme.error)
                            }
                            Button(onClick = { viewModel.approvePendingTool(true) }) {
                                Text("اجازه")
                            }
                        }
                    }
                    // ── Input ──
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { onFocusNeeded(it.isFocused) },
                            placeholder = { Text("بنویسید…") },
                            singleLine = true
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = { viewModel.send(input); input = "" },
                            enabled = input.isNotBlank() && (!busy || pendingApproval != null)
                        ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "ارسال") }
                    }
                }
            }
        }
    }
}
