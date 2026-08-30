package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.automation.AutomationPrefs
import com.openminis.app.automation.TermuxBridge
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermuxScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AutomationPrefs.get(context) }
    val keyboardController = LocalSoftwareKeyboardController.current

    var enabled by remember { mutableStateOf(false) }
    var exchangeDir by remember { mutableStateOf("/sdcard/MinisFork") }
    var installed by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var console by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<String>() }
    var historyIdx by remember { mutableStateOf(-1) }
    var lastCmd by remember { mutableStateOf("") }
    var kbOn by remember { mutableStateOf(true) }

    val focusRequester = remember { FocusRequester() }
    val terminalScrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        enabled = prefs.termuxEnabled
        exchangeDir = prefs.loadTermux().exchangeDir
        installed = TermuxBridge.isTermuxInstalled(context)
        console = if (installed) {
            "Termux پیدا شد ✓ — دستور خود را تایپ کرده و اجرا کنید.\n"
        } else {
            "❌ Termux نصب نیست — طبق راهنمای انتهای صفحه عمل کنید.\n"
        }
        loaded = true
    }

    // Auto-scroll to bottom on new output
    LaunchedEffect(console) {
        terminalScrollState.animateScrollTo(terminalScrollState.maxValue)
    }

    fun log(line: String) {
        console = (console + line + "\n").takeLast(30_000)
    }

    fun runCommand(cmd: String) {
        if (!enabled) {
            log("❌ ابتدا سوئیچ «فعال‌سازی Termux» را روشن کنید.")
            return
        }
        if (cmd.isBlank()) return
        history.add(cmd)
        historyIdx = -1
        lastCmd = cmd
        log("minis@termux:~$ $cmd")
        command = ""
        testing = true
        scope.launch {
            val r = TermuxBridge.execute(context, cmd, exchangeDir, timeoutMs = 120_000L)
            log(r.output.ifBlank { "(بدون خروجی)" })
            testing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("یکپارچه‌سازی Termux") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                    }
                },
            )
        },
    ) { padding ->
        if (!loaded) return@Scaffold

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── Status row ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("فعال‌سازی Termux", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (installed) "✅ Termux آماده استفاده است" else "❌ Termux یافت نشد",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it; prefs.termuxEnabled = it
                })
            }

            // ── Mini terminal ──
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1117))
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(8.dp)
                ) {
                    // Output area
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(terminalScrollState)
                    ) {
                        Text(
                            text = console,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = Color(0xFF7EE787)
                            )
                        )
                    }

                    Divider(color = Color(0xFF21262D), thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))

                    // Inline prompt (BasicTextField + prefix)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "minis@termux:~$ ",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = Color(0xFF58A6FF)
                            )
                        )
                        BasicTextField(
                            value = command,
                            onValueChange = { command = it },
                            modifier = Modifier.weight(1f).focusRequester(focusRequester),
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = Color.White
                            ),
                            cursorBrush = SolidColor(Color(0xFF58A6FF)),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { runCommand(command) })
                        )
                        IconButton(
                            onClick = { runCommand(command) },
                            enabled = !testing && command.isNotBlank(),
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "ارسال",
                                tint = if (command.isNotBlank()) Color(0xFF58A6FF) else Color.Gray
                            )
                        }
                    }

                    // ── Keyboard inside the terminal ──
                    if (kbOn) {
                        Spacer(modifier = Modifier.height(6.dp))

                        // Row 1: control keys
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf("↑", "↓", "ESC", "CTRL", "ALT", "TAB", "CLR").forEach { k ->
                                Surface(
                                    onClick = {
                                        when (k) {
                                            "↑" -> if (history.isNotEmpty()) {
                                                historyIdx = if (historyIdx == -1) history.size - 1 else (historyIdx - 1).coerceAtLeast(0)
                                                command = history[historyIdx]
                                            }
                                            "↓" -> if (history.isNotEmpty()) {
                                                historyIdx = (historyIdx + 1).coerceAtMost(history.size - 1)
                                                command = history[historyIdx]
                                            }
                                            "TAB" -> command += "\t"
                                            "CLR" -> command = ""
                                            else -> log("کلید $k در شل مستقیم اعمال می‌شود.")
                                        }
                                    },
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color(0xFF21262D)
                                ) {
                                    Text(
                                        k,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.White)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))

                        // Row 2: digits
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ('0'..'9').forEach { d ->
                                Surface(
                                    onClick = { command += d },
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color(0xFF161B22)
                                ) {
                                    Text(
                                        d.toString(),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFC9D1D9))
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))

                        // Row 3: shell characters
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf("/", "~", "-", "|", ">", ">>", "<", "&", "*", ".", "_", "\"", "'", "\$", "&&", "sudo ").forEach { symbol ->
                                Surface(
                                    onClick = { command += symbol },
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color(0xFF21262D)
                                ) {
                                    Text(
                                        symbol,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF79C0FF))
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Bottom controls ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { runCommand("echo __TERMUX_OK__ && uname -a") },
                        enabled = !testing && enabled,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) { Text("تست اتصال", fontSize = 12.sp) }
                    Button(
                        onClick = { runCommand("termux-setup-storage") },
                        enabled = !testing && enabled,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) { Text("مجوز حافظه", fontSize = 12.sp) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("کیبورد ترمینال", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = kbOn, onCheckedChange = { kbOn = it }, modifier = Modifier.scale(0.8f))
                }
            }

            // ── Guide ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("راهنمای راه‌اندازی سریع", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "۱. Termux را از F-Droid نصب کنید.\n" +
                        "۲. اجرای دستور: pkg update && pkg install termux-api\n" +
                        "۳. در تنظیمات Termux گزینه Allow external apps را روشن کنید.\n" +
                        "پوشه تبادل: $exchangeDir",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}