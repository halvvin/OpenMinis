package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.automation.AgentEntry
import com.openminis.app.automation.AgentRegistry
import com.openminis.app.sandbox.ExecutionCoordinator
import kotlinx.coroutines.launch

/**
 * [T-agent-manager] «مدیریت ایجنت‌ها» — user spec §8-3.
 *
 * Fully DYNAMIC registry: the user adds any CLI agent/tool with any name
 * and any install/update/run commands. Nothing is hard-coded; guidance for
 * a tool comes from the AI model at chat time, not from this screen.
 * Commands execute inside the app's own sandbox shell with live output.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val registry = remember { AgentRegistry.get(context) }

    var agents by remember { mutableStateOf(registry.load()) }
    var showAdd by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun refresh() { agents = registry.load() }

    suspend fun runFor(entry: AgentEntry, cmd: String) {
        if (cmd.isBlank()) return
        busy = true
        output = "$ ${cmd.take(200)}\n…"
        val r = ExecutionCoordinator.execute("agent-manager", cmd, timeout = 900_000L)
        output = "$ ${cmd.take(200)}\n${r.output}"
        busy = false
        refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("مدیریت ایجنت‌ها") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(horizontal = 16.dp).imePadding()) {
            Text(
                "هر ابزار خط‌فرمانی (ایجنت) را با اسم و دستور دلخواه اضافه کن — نصب، به‌روزرسانی، حذف و اجرا. هیچ لیست ثابتی وجود ندارد؛ برای راهنمای هر ابزار از خود هوش مصنوعی در چت بپرس.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LazyColumn(
                modifier = Modifier.weight(1f).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(agents, key = { it.id }) { entry ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                                IconButton(onClick = {
                                    registry.remove(entry.id)
                                    refresh()
                                }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (entry.installCmd.isNotBlank()) TextButton(onClick = { scope.launch { runFor(entry, entry.installCmd) } }) { Text("نصب") }
                                if (entry.updateCmd.isNotBlank()) TextButton(onClick = { scope.launch { runFor(entry, entry.updateCmd) } }) { Text("به‌روزرسانی") }
                                if (entry.runCmd.isNotBlank()) TextButton(onClick = { scope.launch { runFor(entry, entry.runCmd) } }) { Text("اجرا") }
                                TextButton(onClick = { scope.launch {
                                    busy = true
                                    // [F-A2 security / P2-07] The agent name is
                                    // interpolated into a shell command. Spaces→
                                    // underscore was the only sanitization, so a
                                    // name like `x;rm -rf ~` executed arbitrary
                                    // sandbox commands. Restrict the directory
                                    // token to a safe charset — a real install
                                    // directory never contains anything else.
                                    val dirToken = entry.name.map {
                                        if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_'
                                    }.joinToString("")
                                    val r = ExecutionCoordinator.execute(
                                        "agent-manager",
                                        "du -sh ~/.${dirToken} 2>/dev/null; df -h / | tail -1",
                                        timeout = 30_000L,
                                    )
                                    output = "حجم و فضا:\n${r.output}"
                                    busy = false
                                } }) { Text("حجم") }
                            }
                            if (entry.notes.isNotBlank()) {
                                Text(entry.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            if (busy) Text("در حال اجرا…", style = MaterialTheme.typography.bodySmall)
            if (output.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(
                        output,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                        maxLines = 10,
                    )
                }
            }
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var install by remember { mutableStateOf("") }
        var update by remember { mutableStateOf("") }
        var run by remember { mutableStateOf("") }
        var notes by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("افزودن ایجنت / ابزار") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("نام دلخواه") }, singleLine = true)
                    OutlinedTextField(value = install, onValueChange = { install = it }, label = { Text("دستور نصب (مثلاً npm i -g …)") }, minLines = 1)
                    OutlinedTextField(value = update, onValueChange = { update = it }, label = { Text("دستور به‌روزرسانی (اختیاری)") }, minLines = 1)
                    OutlinedTextField(value = run, onValueChange = { run = it }, label = { Text("دستور اجرا (اختیاری)") }, minLines = 1)
                    OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("یادداشت (اختیاری)") }, minLines = 1)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = name.trim()
                    if (n.isNotEmpty()) {
                        registry.upsert(
                            AgentEntry(
                                id = registry.newId(), name = n,
                                installCmd = install.trim(), updateCmd = update.trim(),
                                runCmd = run.trim(), notes = notes.trim(),
                            )
                        )
                        refresh()
                    }
                    showAdd = false
                }) { Text("ذخیره") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("انصراف") } },
        )
    }
}
