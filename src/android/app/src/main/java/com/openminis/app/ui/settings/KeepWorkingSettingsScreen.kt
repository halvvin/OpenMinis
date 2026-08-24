package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openminis.app.data.KeepWorkingConfig
import com.openminis.app.data.KeepWorkingPrefs

/**
 * [T-keep-working-engine] Settings → Keep Working screen.
 *
 * Configures the auto-continue engine: when a task fails mid-flight
 * (network loss, rate limit, provider error), the app re-sends the
 * continuation command after [KeepWorkingConfig.intervalSeconds], up to
 * [KeepWorkingConfig.maxAttempts] times.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeepWorkingSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { KeepWorkingPrefs.get(context) }

    var enabled by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("") }
    var intervalText by remember { mutableStateOf("30") }
    var attemptsText by remember { mutableStateOf("5") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val c = prefs.load()
        enabled = c.enabled
        command = c.command
        intervalText = c.intervalSeconds.toString()
        attemptsText = c.maxAttempts.toString()
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("موتور ادامه خودکار") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (!loaded) return@Scaffold

        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("فعال", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "اگر اینترنت قطع شود، سقف مصرف پر شود یا خطایی پیش بیاید، وظیفه به‌طور خودکار ادامه می‌یابد.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text("متن دستور ادامه") },
                supportingText = { Text("هر بار برای ادامه‌ی کار به مدل ارسال می‌شود") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            OutlinedTextField(
                value = intervalText,
                onValueChange = { intervalText = it.filter { ch -> ch.isDigit() } },
                label = { Text("فاصله بین تلاش‌ها (ثانیه)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = attemptsText,
                onValueChange = { attemptsText = it.filter { ch -> ch.isDigit() } },
                label = { Text("حداکثر تعداد تلاش") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Button(
                onClick = {
                    prefs.save(
                        KeepWorkingConfig(
                            enabled = enabled,
                            command = command.ifBlank { KeepWorkingConfig().command },
                            intervalSeconds = intervalText.toIntOrNull()?.coerceIn(5, 3600) ?: 30,
                            maxAttempts = attemptsText.toIntOrNull()?.coerceIn(1, 100) ?: 5,
                        )
                    )
                    onBack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            ) { Text("ذخیره") }
        }
    }
}
