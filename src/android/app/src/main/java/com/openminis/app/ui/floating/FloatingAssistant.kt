package com.openminis.app.ui.floating

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.openminis.app.automation.AutomationPrefs

/**
 * [T-floating-assistant] Floating Smart Assistant — a draggable, resizable
 * window the user can open from anywhere in the app to chat with ANY model
 * and (in later stages) run tools. v1: bubble + panel + model selector +
 * direct model chat.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FloatingAssistantOverlay(
    viewModel: FloatingAssistantViewModel,
    providerRepository: com.openminis.app.data.repository.ProviderRepository,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember { AutomationPrefs.get(context) }
    val messages by viewModel.messages.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val modelEntries by viewModel.modelEntries.collectAsState()
    val selectedEntryId by viewModel.selectedEntryId.collectAsState()

    val screenW = LocalConfiguration.current.screenWidthDp
    val screenH = LocalConfiguration.current.screenHeightDp

    var open by remember { mutableStateOf(prefs.assistantOpen) }
    var offsetX by remember { mutableStateOf(prefs.assistantX) }
    var offsetY by remember { mutableStateOf(prefs.assistantY) }
    var panelW by remember { mutableStateOf(prefs.assistantW) }
    var panelH by remember { mutableStateOf(prefs.assistantH) }
    var input by remember { mutableStateOf("") }
    var modelMenu by remember { mutableStateOf(false) }
    var providerMenu by remember { mutableStateOf(false) }
    // Current provider label for the selected model
    var selProviderLabel by remember { mutableStateOf("") }
    // Update provider label when model changes
    LaunchedEffect(selectedEntryId) {
        selProviderLabel = runCatching {
            providerRepository.config.value.instances
                .firstOrNull { it.id == (modelEntries.firstOrNull { it.id == selectedEntryId }?.providerInstanceId) }?.label
                ?: ""
        }.getOrDefault("")
    }

    fun clampPanel() {
        val w = panelW.toFloat().coerceAtLeast(52f)
        offsetX = offsetX.coerceIn(-(screenW - w).toFloat().coerceAtLeast(0f), 0f)
        offsetY = offsetY.coerceIn(0f, (screenH - panelH - 60).toFloat().coerceAtLeast(0f))
        panelW = panelW.coerceIn(220, screenW)
        panelH = panelH.coerceIn(240, (screenH - 120).coerceAtLeast(240))
    }
    fun saveGeom() {
        prefs.assistantX = offsetX
        prefs.assistantY = offsetY
        prefs.assistantW = panelW
        prefs.assistantH = panelH
        prefs.assistantOpen = open
    }

    LaunchedEffect(Unit) { clampPanel() }

    if (!open) {
        // Collapsed bubble — drag to move, tap to open.
        Box(
            modifier = modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(offsetX.toInt(), offsetY.toInt()) }
                .size(54.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                .combinedClickable(
                    onClick = { open = true; saveGeom() },
                    onLongClick = { open = true; saveGeom() },
                )
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = { saveGeom() }) { change, drag ->
                        change.consume()
                        offsetX += drag.x
                        offsetY += drag.y
                        clampPanel()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("🤖", style = MaterialTheme.typography.titleLarge)
        }
        return
    }

    Card(
        modifier = modifier
            .align(Alignment.TopEnd)
            .offset { IntOffset(offsetX.toInt(), offsetY.toInt()) }
            .width(panelW.dp)
            .height(panelH.dp)
            .imePadding(),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header: drag + model selector + resize + close ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .pointerInput(Unit) {
                        detectDragGestures(onDragEnd = { saveGeom() }) { change, drag ->
                            change.consume()
                            offsetX += drag.x
                            offsetY += drag.y
                            clampPanel()
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("🤖", style = MaterialTheme.typography.titleMedium)
                // Two-step selector: API first, then its models.
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
                    DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                        val providers = modelEntries
                            .groupBy { e ->
                                runCatching { providerRepository.config.value.instances }
                                    .getOrNull()?.firstOrNull { it.id == e.providerInstanceId }?.label
                                    ?: e.model.provider
                            }
                        if (providers.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("(لیست خالی — اول یک API تنظیم کن)") },
                                onClick = { providerMenu = false },
                            )
                        }
                        providers.forEach { (provider, entries) ->
                            DropdownMenuItem(
                                text = { Text(provider, maxLines = 1) },
                                onClick = {
                                    selProviderLabel = provider
                                    providerMenu = false
                                    // Auto-select the first model of this API.
                                    viewModel.selectModel(entries.first().id)
                                },
                            )
                        }
                    }
                }
                // Model selector — only models of the selected API.
                Box {
                    Text(
                        modelEntries.firstOrNull { it.id == selectedEntryId }?.model?.displayName ?: "انتخاب مدل",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        modifier = Modifier
                            .clickable { modelMenu = true }
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        val filtered = if (selProviderLabel.isNotBlank()) {
                            modelEntries.filter { e ->
                                val lbl = runCatching { providerRepository.config.value.instances }
                                    .getOrNull()?.firstOrNull { it.id == e.providerInstanceId }?.label
                                    ?: e.model.provider
                                lbl == selProviderLabel
                            }
                        } else modelEntries
                        if (filtered.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("(مدلی برای این API نیست)") },
                                onClick = { modelMenu = false },
                            )
                        }
                        filtered.forEach { e ->
                            DropdownMenuItem(
                                text = { Text(e.model.displayName, maxLines = 1) },
                                onClick = { viewModel.selectModel(e.id); modelMenu = false },
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("－", modifier = Modifier.clickable {
                    panelW = (panelW - 40).coerceAtLeast(220)
                    panelH = (panelH - 40).coerceAtLeast(240)
                    saveGeom()
                }, style = MaterialTheme.typography.titleMedium)
                Text("＋", modifier = Modifier.clickable {
                    panelW = (panelW + 40).coerceAtMost(520)
                    panelH = (panelH + 40).coerceAtMost(760)
                    saveGeom()
                }, style = MaterialTheme.typography.titleMedium)
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "بستن",
                    modifier = Modifier.size(18.dp).clickable { open = false; saveGeom() },
                )
            }

            // ── Messages ──
            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(messages, key = { "${it.role}-${it.hashCode()}" }) { m ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            // Copy message text to clipboard on tap (user asked:
                            // "وقتی متنی چیزی گفتم قابل کپی کردن باشد").
                            runCatching {
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("assistant", m.text))
                            }
                        },
                    ) {
                        Text(
                            (if (m.role == "user") "🧑 " else "🤖 ") + m.text,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(6.dp),
                            maxLines = 20,
                        )
                    }
                }
            }
            if (busy) {
                Row(modifier = Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("در حال پاسخ…", style = MaterialTheme.typography.labelSmall)
                }
            }

            // ── Input row ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("برای دستیار بنویس…", style = MaterialTheme.typography.labelSmall) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true,
                )
                IconButton(
                    onClick = { viewModel.send(input); input = "" },
                    enabled = !busy && input.isNotBlank(),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "ارسال") }
            }
        }
    }
}
