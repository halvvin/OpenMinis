package com.openminis.app.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.db.ChatDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One row of the cleanup list: a chat session plus its on-disk footprint. */
private data class CleanupItem(
    val id: String,
    val title: String?,
    val size: Long,
) {
    var selected: Boolean by mutableStateOf(false)
}

/**
 * Bulk storage cleanup: lists every chat session with its name + size,
 * lets the user tick exactly which ones to delete, confirms once with a
 * size summary, then deletes only the selected directories. Nothing else
 * in the app is touched.
 */
@Composable
fun StorageCleanupScreen(
    chatDao: ChatDao,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<CleanupItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var deleting by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    val selectedItems = items.filter { it.selected }
    val deleteCount = selectedItems.size
    val deleteSize = selectedItems.sumOf { it.size }

    fun load() {
        scope.launch {
            loading = true
            withContext(Dispatchers.IO) {
                val sessions = chatDao.listSessions()
                val sessionsDir = File(context.filesDir, "minis-sessions")
                val mediaDir = File(context.filesDir, "media")
                val ids = sessions.map { it.id }.toSet()
                val mediaSizes = mediaSizesBySession(mediaDir, ids)
                items = sessions.map { s ->
                    val minisSize = directorySize(File(sessionsDir, s.id))
                    val mediaSize = mediaSizes[s.id] ?: 0L
                    CleanupItem(id = s.id, title = s.title, size = minisSize + mediaSize)
                }.sortedByDescending { it.size }
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    SettingsScaffold(title = stringResource(R.string.storage_cleanup_title), onBack = onBack) {
        if (loading || deleting) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp))
            }
        } else {
            SettingsSection(header = stringResource(R.string.storage_cleanup_section)) {
                if (items.isEmpty()) {
                    Text(
                        stringResource(R.string.storage_no_sessions),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.height(360.dp)) {
                        items(items, key = { it.id }) { item ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { item.selected = !item.selected }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                Checkbox(
                                    checked = item.selected,
                                    onCheckedChange = { item.selected = it },
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    item.title ?: stringResource(R.string.storage_untitled),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                )
                                Text(
                                    Formatter.formatFileSize(context, item.size),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }

            Button(
                onClick = { showConfirm = true },
                enabled = deleteCount > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp),
            ) {
                Text(stringResource(R.string.storage_cleanup_delete_selected))
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.storage_cleanup_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.storage_cleanup_confirm_message,
                        deleteCount,
                        Formatter.formatFileSize(context, deleteSize),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    deleting = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val sessionsDir = File(context.filesDir, "minis-sessions")
                            val mediaDir = File(context.filesDir, "media")
                            // Snapshot first: list mutation while iterating would break.
                            val targets = selectedItems.map { it.id }
                            for (id in targets) {
                                File(sessionsDir, id).deleteRecursively()
                                File(mediaDir, id).deleteRecursively()
                            }
                        }
                        deleting = false
                        load()
                    }
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
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
            if (ids.contains(parent)) map[parent] = (map[parent] ?: 0L) + f.length()
        }
    }
    return map
}
