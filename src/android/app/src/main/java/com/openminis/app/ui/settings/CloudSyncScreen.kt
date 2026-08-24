package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openminis.app.data.CloudSyncStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-cloud-sync] «همگام‌سازی ابری شخصی» — sync profile / skills / engine
 * config to the USER'S OWN WebDAV cloud. No Minis-operated server exists;
 * the user brings their own endpoint (Nextcloud, self-hosted WebDAV, ...).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = kotlinx.coroutines.MainScope()
    var baseUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    remember {
        val c = CloudSyncStore.load(context)
        baseUrl = c.baseUrl
        username = c.username
        password = c.password
        true
    }

    suspend fun saveAnd(fn: suspend (CloudSyncStore.Config) -> CloudSyncStore.SyncResult) {
        busy = true
        status = "…"
        val cfg = CloudSyncStore.Config(baseUrl.trim(), username.trim(), password)
        CloudSyncStore.save(context, cfg)
        val r = withContext(Dispatchers.IO) { fn(cfg) }
        status = r.message
        busy = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("همگام‌سازی ابری شخصی") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "پروفایل، مهارت‌ها (Skills)، تنظیمات موتور ادامه خودکار و شخصیت (SOUL) روی فضای ابری خودت ذخیره و بازیابی می‌شوند — بین دستگاه‌ها و بعد از نصب مجدد. هیچ سروری از سمت اپ در کار نیست؛ آدرس WebDAV خودت را بده (مثلاً Nextcloud).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = baseUrl, onValueChange = { baseUrl = it },
                label = { Text("آدرس WebDAV") },
                supportingText = { Text("مثال: https://cloud.example.com/remote.php/dav/files/user/minis") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text("نام کاربری") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("رمز / App Token") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { scope.launch { saveAnd { CloudSyncStore.pushAll(context, it) } } },
                    enabled = !busy,
                ) { Text("آپلود به ابر") }
                OutlinedButton(
                    onClick = { scope.launch { saveAnd { CloudSyncStore.pullAll(context, it) } } },
                    enabled = !busy,
                ) { Text("بازیابی از ابر") }
            }

            if (status.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }
}
