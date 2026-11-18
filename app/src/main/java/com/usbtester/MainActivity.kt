package com.usbtester

import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { TesterScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TesterScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("tester", 0) }
    var folderUri by remember { mutableStateOf(prefs.getString("folder", null)?.let(Uri::parse)) }
    var folderName by remember { mutableStateOf(prefs.getString("folderName", null) ?: "No test folder selected") }
    var mode by remember { mutableStateOf("Quick") }
    var sizeMiB by remember { mutableStateOf("64") }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(TestProgress()) }
    var result by remember { mutableStateOf<TestResult?>(null) }
    var report by remember { mutableStateOf("") }
    var confirmFull by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var createdFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val engine = remember { TestEngine(context) }
    var devices by remember { mutableStateOf(UsbInfo.connected(context)) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {}
            folderUri = uri
            folderName = uri.lastPathSegment ?: uri.toString()
            prefs.edit().putString("folder", uri.toString()).putString("folderName", folderName).apply()
        }
    }

    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, intent: Intent) {
                devices = UsbInfo.connected(ctx)
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }

    val sizeBytes = (sizeMiB.toLongOrNull() ?: 0L) * TestEngine.MIB
    val canStart = folderUri != null && !running && sizeBytes > 0

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("USB Flash Drive Tester", style = MaterialTheme.typography.headlineSmall)
        Text("Non-destructive filesystem-level capacity and integrity check", style = MaterialTheme.typography.bodyMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("USB devices", style = MaterialTheme.typography.titleMedium)
                if (devices.isEmpty()) Text("No USB device currently enumerated. Connect a drive using OTG.")
                devices.forEach { d ->
                    Text("${d.product} (${d.manufacturer})")
                    Text("VID: %04X   PID: %04X".format(Locale.US, d.vendorId, d.productId))
                    Text(d.name, style = MaterialTheme.typography.bodySmall)
                    Text(d.interfaceSummary, style = MaterialTheme.typography.bodySmall)
                }
                Text("USB identity strings are not proof of authenticity.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Test destination", style = MaterialTheme.typography.titleMedium)
                Text(folderName, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { folderPicker.launch(null) }, enabled = !running) { Text("Choose test folder") }
                Text("Choose a folder on the USB drive (for example, create USBTester first). Existing files are not touched.")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Test configuration", style = MaterialTheme.typography.titleMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Quick", "Standard", "Full").forEachIndexed { index, label ->
                        SegmentedButton(selected = mode == label, onClick = {
                            mode = label
                            sizeMiB = when (label) { "Quick" -> "64"; "Standard" -> "1024"; else -> sizeMiB }
                        }, shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(label) }
                    }
                }
                OutlinedTextField(
                    value = sizeMiB, onValueChange = { sizeMiB = it.filter(Char::isDigit) },
                    label = { Text("Test size (MiB)") }, supportingText = {
                        Text(if (mode == "Full") "Enter the free space you want to test. Check free space in your file manager first." else "Quick is preliminary; Standard samples a larger region. Full should cover all available free space.")
                    }, singleLine = true, enabled = !running, modifier = Modifier.fillMaxWidth()
                )
                if (mode == "Full") {
                    Text("Full test writes temporary data equal to the selected size. It may take hours and requires that much free space plus filesystem overhead.", color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = { if (mode == "Full") confirmFull = true else startTest(
                    context, scope, engine, folderUri, sizeBytes,
                    { running = true; result = null; report = ""; progress = TestProgress() },
                    { progress = it },
                    { r -> result = r; createdFiles = r.createdFiles; running = false; report = makeReport(r, folderName) },
                    { running = false; report = "Test stopped: ${it.message}" }
                ) }, enabled = canStart, modifier = Modifier.fillMaxWidth()) { Text(if (running) "Testing…" else "Start ${mode} Test") }
            }
        }

        if (confirmFull) {
            AlertDialog(onDismissRequest = { confirmFull = false }, title = { Text("Confirm full-capacity test") },
                text = { Text("This will create ${sizeMiB} MiB of temporary files in the selected folder, then read them back. It will not format or delete existing files. Make sure the drive has enough free space. Continue?") },
                confirmButton = { TextButton(onClick = {
                    confirmFull = false
                    startTest(context, scope, engine, folderUri, sizeBytes,
                        { running = true; result = null; report = ""; progress = TestProgress() },
                        { progress = it },
                        { r -> result = r; createdFiles = r.createdFiles; running = false; report = makeReport(r, folderName) },
                        { running = false; report = "Test stopped: ${it.message}" })
                }) { Text("Start test") } },
                dismissButton = { TextButton(onClick = { confirmFull = false }) { Text("Cancel") } })
        }

        if (running || progress.phase != "Idle") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Test progress: ${progress.phase}", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                    Text("${progress.bytesDone / TestEngine.MIB} / ${progress.totalBytes / TestEngine.MIB} MiB")
                    Text("Current file: ${progress.currentFile}")
                    Text("Write: ${formatRate(progress.writeBytesPerSecond)} · Read: ${formatRate(progress.readBytesPerSecond)}")
                    Text("Verified files: ${progress.successfulFiles} · Failed: ${progress.failedFiles}")
                    if (progress.errors.isNotEmpty()) Text(progress.errors.takeLast(3).joinToString("\n"), color = MaterialTheme.colorScheme.error)
                    if (running) OutlinedButton(onClick = { /* cancellation is provided by coroutine scope below */ }) { Text("Do not disconnect the drive") }
                }
            }
        }

        result?.let { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Result: ${r.status}", style = MaterialTheme.typography.titleLarge,
                        color = when (r.status) { "PASS" -> MaterialTheme.colorScheme.primary; "FAIL" -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.tertiary })
                    Text("Tested: ${r.testedBytes / TestEngine.MIB} MiB")
                    Text("Verified: ${r.verifiedBytes / TestEngine.MIB} MiB")
                    Text("Corrupted / mismatched: ${r.corruptedBytes / TestEngine.MIB} MiB")
                    Text("Write speed: ${formatRate(r.writeBytesPerSecond)}")
                    Text("Read speed: ${formatRate(r.readBytesPerSecond)}")
                    Text("Duration: ${r.durationMillis / 1000}s")
                    Text("PASS means all selected test bytes were verified in this run; it does not certify physical authenticity.")
                    Button(onClick = { report = makeReport(r, folderName) }) { Text("Refresh report") }
                    OutlinedButton(onClick = { confirmDelete = true }, enabled = createdFiles.isNotEmpty()) { Text("Delete test files…") }
                }
            }
        }
        if (report.isNotBlank()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Test report", style = MaterialTheme.typography.titleMedium)
                    Text(report, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("USB test report", report))
                    }) { Text("Copy report") }
                }
            }
        }
        if (confirmDelete) {
            AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete test files?") },
                text = { Text("This deletes only the ${createdFiles.size} test files created by this app in the current session. Review the folder first. Continue?") },
                confirmButton = { TextButton(onClick = {
                    val tree = folderUri?.let { androidx.documentfile.provider.DocumentFile.fromTreeUri(context, it) }
                    createdFiles.forEach { tree?.findFile(it)?.delete() }
                    createdFiles = emptyList()
                    confirmDelete = false
                }) { Text("Delete test files") } },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
        }
        Text("Safety: no automatic formatting or cleanup. Keep the drive connected during testing. A disconnect or I/O error can leave partial test files.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun startTest(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    engine: TestEngine,
    uri: Uri?,
    bytes: Long,
    onStart: () -> Unit,
    onProgress: (TestProgress) -> Unit,
    onDone: (TestResult) -> Unit,
    onError: (Throwable) -> Unit
) {
    if (uri == null) return
    onStart()
    scope.launch {
        try { onDone(engine.run(uri, bytes, onProgress)) }
        catch (t: Throwable) { onError(t) }
    }
}

private fun formatRate(bytesPerSecond: Double): String =
    if (bytesPerSecond <= 0) "—" else String.format(Locale.US, "%.2f MB/s", bytesPerSecond / (1024.0 * 1024.0))

private fun makeReport(r: TestResult, folder: String): String = buildString {
    appendLine("USB FLASH DRIVE TEST REPORT")
    appendLine("Destination: $folder")
    appendLine("Assessment: ${r.status}")
    appendLine("Tested capacity: ${r.testedBytes} bytes")
    appendLine("Verified capacity: ${r.verifiedBytes} bytes")
    appendLine("Corrupted/mismatched bytes: ${r.corruptedBytes}")
    appendLine("Successful files: ${r.successfulFiles}")
    appendLine("Failed files: ${r.failedFiles}")
    appendLine("Sequential write: ${formatRate(r.writeBytesPerSecond)}")
    appendLine("Sequential read: ${formatRate(r.readBytesPerSecond)}")
    appendLine("Duration: ${r.durationMillis} ms")
    if (r.errors.isNotEmpty()) {
        appendLine("Errors:")
        r.errors.forEach { appendLine("- $it") }
    }
    appendLine("Note: Filesystem-level test only; not raw-sector verification or proof of physical authenticity.")
}
