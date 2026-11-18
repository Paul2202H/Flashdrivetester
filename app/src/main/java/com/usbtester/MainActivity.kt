
package com.usbtester

import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Locale

private val LightColors = lightColorScheme(
    primary = Color(0xFF087F73),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0F1E9),
    onPrimaryContainer = Color(0xFF003B34),
    secondary = Color(0xFF477D75),
    background = Color(0xFFF4F7F7),
    surface = Color.White,
    surfaceVariant = Color(0xFFE7EEEE),
    error = Color(0xFFBA1A1A)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF65D9C8),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF075D54),
    onPrimaryContainer = Color(0xFFB5F2E7),
    secondary = Color(0xFFA1D5CC),
    background = Color(0xFF10191C),
    surface = Color(0xFF192428),
    surfaceVariant = Color(0xFF293A3E),
    error = Color(0xFFFFB4AB)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = if (
                    androidx.compose.foundation.isSystemInDarkTheme()
                ) DarkColors else LightColors
            ) {
                TesterScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TesterScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("tester", 0)
    }

    var folderUri by remember {
        mutableStateOf(
            prefs.getString("folder", null)?.let(Uri::parse)
        )
    }

    var folderName by remember {
        mutableStateOf(
            prefs.getString("folderName", null)
                ?: "No test folder selected"
        )
    }

    var mode by remember { mutableStateOf("Quick") }
    var sizeMiB by remember { mutableStateOf("64") }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(TestProgress()) }
    var result by remember { mutableStateOf<TestResult?>(null) }
    var report by remember { mutableStateOf("") }

    var confirmFull by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    var createdFiles by remember {
        mutableStateOf<List<String>>(emptyList())
    }

    val scope = rememberCoroutineScope()
    val engine = remember { TestEngine(context) }

    var devices by remember {
        mutableStateOf(UsbInfo.connected(context))
    }

    val folderPicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                }

                folderUri = uri
                folderName = uri.lastPathSegment ?: uri.toString()

                prefs.edit()
                    .putString("folder", uri.toString())
                    .putString("folderName", folderName)
                    .apply()
            }
        }

    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(
                ctx: android.content.Context,
                intent: Intent
            ) {
                devices = UsbInfo.connected(ctx)
            }
        }

        val filter = android.content.IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(
                receiver,
                filter,
                android.content.Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    val sizeBytes =
        (sizeMiB.toLongOrNull() ?: 0L) * TestEngine.MIB

    val canStart =
        folderUri != null && !running && sizeBytes > 0

    fun beginTest() {
        startTest(
            context,
            scope,
            engine,
            folderUri,
            sizeBytes,
            {
                running = true
                result = null
                report = ""
                createdFiles = emptyList()
                progress = TestProgress()
            },
            { progress = it },
            { r ->
                result = r
                createdFiles = r.createdFiles
                running = false
                report = makeReport(r, folderName)
            },
            { error ->
                running = false
                report = "Test stopped: ${error.message}"
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(13.dp),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    "▤",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.headlineMedium
                                )
                            }
                        }

                        Column {
                            Text(
                                "FlashDrive Tester",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Storage diagnostics",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            // Welcome card

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Text(
                        "STORAGE DIAGNOSTICS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "Test capacity.\nVerify integrity.",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )

                    Text(
                        "Check your flash drive or memory card for storage and data integrity problems.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text("USB OTG") }
                        )
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text("SD Card") }
                        )
                    }
                }
            }

            // Connected devices

            SectionCard(
                title = "Connected devices",
                subtitle = "USB hardware detection"
            ) {
                if (devices.isEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("○", style = MaterialTheme.typography.headlineMedium)
                        Column {
                            Text(
                                "No USB device detected",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Connect a flash drive or SD card reader using OTG.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                devices.forEachIndexed { index, d ->
                    if (index > 0) HorizontalDivider()

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    "USB",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        Column(Modifier.weight(1f)) {
                            Text(
                                d.product.ifBlank { "USB storage device" },
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                d.manufacturer.ifBlank { "Unknown manufacturer" },
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "VID %04X  •  PID %04X".format(
                                    Locale.US,
                                    d.vendorId,
                                    d.productId
                                ),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }

                        Text(
                            "Connected",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    Text(
                        d.name,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        d.interfaceSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    "USB identity information does not prove physical authenticity.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Destination

            SectionCard(
                title = "Test destination",
                subtitle = "Select the storage location to test"
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "▤",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Column(Modifier.weight(1f)) {
                        Text(
                            if (folderUri != null) "Folder selected" else "No folder selected",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            folderName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Button(
                    onClick = { folderPicker.launch(null) },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Choose test folder")
                }

                Text(
                    "Choose a folder on your USB drive or SD card. Existing files are not intentionally modified.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Test configuration

            SectionCard(
                title = "Test configuration",
                subtitle = "Choose how much storage to test"
            ) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf("Quick", "Standard", "Full").forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = mode == label,
                            onClick = {
                                mode = label
                                sizeMiB = when (label) {
                                    "Quick" -> "64"
                                    "Standard" -> "1024"
                                    else -> sizeMiB
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, 3),
                            enabled = !running
                        ) {
                            Text(label)
                        }
                    }
                }

                OutlinedTextField(
                    value = sizeMiB,
                    onValueChange = {
                        sizeMiB = it.filter(Char::isDigit)
                    },
                    label = { Text("Test size (MiB)") },
                    supportingText = {
                        Text(
                            if (mode == "Full") {
                                "Enter the amount of free space you want to test."
                            } else {
                                "Quick: 64 MiB. Standard: 1024 MiB. You can change the size."
                            }
                        )
                    },
                    singleLine = true,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                )

                if (mode == "Full") {
                    Text(
                        "Warning: A full test may take a long time and needs enough free storage space.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Button(
                    onClick = {
                        if (mode == "Full") {
                            confirmFull = true
                        } else {
                            beginTest()
                        }
                    },
                    enabled = canStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        if (running) "Testing..." else "Start $mode Test",
                        fontWeight = FontWeight.Bold
                    )
                }

                if (running) {
                    Text(
                        "Keep your storage device connected while testing.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            // Full test confirmation

            if (confirmFull) {
                AlertDialog(
                    onDismissRequest = { confirmFull = false },
                    title = { Text("Confirm full test") },
                    text = {
                        Text(
                            "This will write ${sizeMiB} MiB of temporary test data and read it back. Make sure the selected drive has enough free space. Continue?"
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirmFull = false
                                beginTest()
                            }
                        ) {
                            Text("Start test")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { confirmFull = false }
                        ) {
                            Text("Cancel")
                        }
                    }
                )
            }

            // Progress

            if (running || progress.phase != "Idle") {
                SectionCard(
           
