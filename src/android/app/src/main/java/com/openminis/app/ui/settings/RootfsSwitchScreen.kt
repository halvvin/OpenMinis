package com.openminis.app.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.openminis.app.sandbox.RootfsManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootfsSwitchScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { RootfsManager.getInstance(context) }
    var targetExternal by remember { mutableStateOf(manager.prefersExternal()) }
    var isProcessing by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("جابه‌جایی روت‌افس") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("انتخاب مسیر روت‌افس")
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { targetExternal = false }
            ) {
                RadioButton(selected = !targetExternal, onClick = { targetExternal = false })
                Text("حافظهٔ داخلی")
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { targetExternal = true }
            ) {
                RadioButton(selected = targetExternal, onClick = { targetExternal = true })
                Text("حافظهٔ خارجی (SD)" )
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    isProcessing = true
                    scope.launch {
                        val success = manager.moveRootfs(targetExternal)
                        isProcessing = false
                        lastResult = if (success) "تغییر مسیر با موفقیت انجام شد" else "خطا در جابجایی روت‌افس"
                    }
                },
                enabled = !isProcessing
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("جابه‌جایی")
                }
            }
            Spacer(Modifier.height(16.dp))
            lastResult?.let { msg ->
                Text(msg)
                LaunchedEffect(msg) {
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
