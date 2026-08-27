package com.openminis.app.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.db.ChatDao
import com.openminis.app.ui.components.SettingsScaffold
import com.openminis.app.ui.components.SettingsSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageCleanupScreen(
    chatDao: ChatDao,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<SessionItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showConfirm by remember { mutableStateOf(false) }
    var deleteCount by remember { mutableStateOf(0) }
    var deleteSize by remember { mutableStateOf(0L) }

    data class SessionItem(
        val id: String,
        val title: String?,
        val size: Long,
        var selected: Boolean = false,
    )

    fun load() {
        scope.launch {
            loading = true
            withContext(Dispatchers.IO) {
                val sessions = chatDao.listSessions()
                val sessionsDir = File(context.filesDir, "minis-sessions")
                val mediaDir = File(context.filesDir, "media")
                val mediaSizes = mediaSizesBySession(mediaDir, sessions.map { it.id }.toSet())
                items = sessions.map { s ->
                    val minisDir = File(sessionsDir, s.id)
                    val minisSize = directorySize(minisDir)
                    val mediaSize = mediaSizes[s.id] ?: 0L
                    SessionItem(
                        id = s.id,
                        title = s.title,
                        size = minisSize + mediaSize,
                    )
                }.sortedByDescending { it.size }
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    SettingsScaffold(title = stringResource(R.string.storage_cleanup_title), onBack = onBack) {
        if (loading) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        } else {
            SettingsSection(header = stringResource(R.string.storage_cleanup_section)) {
                LazyColumn {
                    items(items) { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .clickable { item.selected = !item.selected }
                        ) {
                            Checkbox(checked = item.selected, onCheckedChange = { item.selected = it })
                            Spacer(Modifier.width(8.dp))
                            Text(item.title ?: stringResource(R.string.storage_untitled), modifier = Modifier.weight(1f))
                            Text(Formatter.formatFileSize(context, item.size))
                        }
                        Divider()
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        // compute summary
                        deleteCount = items.count { it.selected }
                        deleteSize = items.filter { it.selected }.sumOf { it.size }
                        showConfirm = true
                    },
                    enabled = items.any { it.selected }
                ) {
                    Text(stringResource(R.string.storage_cleanup_delete_selected))
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.storage_cleanup_confirm_title)) },
            text = {
                Text(stringResource(R.string.storage_cleanup_confirm_message, deleteCount, Formatter.formatFileSize(context, deleteSize)))
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val sessionsDir = File(chatDao.context.filesDir, "minis-sessions")
                            val mediaDir = File(chatDao.context.filesDir, "media")
                            items.filter { it.selected }.forEach { item ->
                                File(sessionsDir, item.id).deleteRecursively()
                                File(mediaDir, item.id).deleteRecursively()
                            }
                        }
                        load()
                    }
                }) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

private fun directorySize(dir: File): Long {
    if (!dir.exists()) return 0L
    var total = 0L
    dir.walkTopDown().forEach { f -> if (f.isFile) total += f.length() }
    return total
}

private fun mediaSizesBySession(mediaDir: File, ids: Set<String>): Map<String, Long> {
    if (!mediaDir.exists()) return emptyMap()
    val map = mutableMapOf<String, Long>()
    mediaDir.walkTopDown().forEach { f ->
        if (f.isFile) {
            val parent = f.parentFile?.name ?: return@forEach
            if (ids.contains(parent)) {
                map[parent] = (map[parent] ?: 0L) + f.length()
            }
        }
    }
    return map
}
