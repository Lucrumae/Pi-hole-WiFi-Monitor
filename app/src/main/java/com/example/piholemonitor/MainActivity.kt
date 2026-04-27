package com.example.piholemonitor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.piholemonitor.ui.screens.*
import com.example.piholemonitor.ui.theme.PiholeMonitorTheme
import com.example.piholemonitor.ui.theme.PiholeRed
import com.example.piholemonitor.viewmodel.ConfigViewModel
import com.example.piholemonitor.viewmodel.MainViewModel
import com.topjohnwu.superuser.Shell

/**
 * Main entry point. Sets up libsu, checks root, and hosts the Compose UI.
 */
class MainActivity : ComponentActivity() {

    companion object {
        init {
            // Configure libsu before any shell is created
            Shell.enableVerboseLogging = BuildConfig.DEBUG
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(10)
            )
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            PiholeMonitorTheme {
                PiholeMonitorApp()
            }
        }
    }
}

@Composable
fun PiholeMonitorApp() {
    val mainViewModel: MainViewModel = viewModel()
    val configViewModel: ConfigViewModel = viewModel()

    val rootAvailable by mainViewModel.rootAvailable.collectAsState()
    val serviceState by mainViewModel.serviceState.collectAsState()
    val logEntries by mainViewModel.logEntries.collectAsState()
    val capabilities by mainViewModel.capabilities.collectAsState()
    val config by configViewModel.config.collectAsState()
    val saveMessage by configViewModel.saveMessage.collectAsState()
    val testResult by mainViewModel.testResult.collectAsState()
    val isTesting by mainViewModel.isTesting.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Show save message as snackbar
    LaunchedEffect(saveMessage) {
        saveMessage?.let {
            snackbarHostState.showSnackbar(it)
            configViewModel.clearSaveMessage()
        }
    }

    // Show test result as dialog
    var showTestDialog by remember { mutableStateOf(false) }
    LaunchedEffect(testResult) {
        if (testResult != null) showTestDialog = true
    }

    // Root not available — show error screen
    if (rootAvailable == false) {
        RootRequiredScreen(onRetry = { mainViewModel.checkRoot() })
        return
    }

    // Root still checking
    if (rootAvailable == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = "Dashboard") },
                    label = { Text("Dashboard") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Configuration") },
                    label = { Text("Config") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.List, contentDescription = "Log") },
                    label = { Text("Log") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Info, contentDescription = "Status") },
                    label = { Text("Status") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> DashboardScreen(
                    serviceState = serviceState,
                    logEntries = logEntries,
                    onStart = { mainViewModel.startService() },
                    onStop = { mainViewModel.stopService() },
                    onLogTap = { selectedTab = 2 }
                )
                1 -> ConfigurationScreen(
                    config = config,
                    onConfigChange = { configViewModel.updateConfig(it) },
                    onSave = { configViewModel.saveConfig() },
                    onReset = { configViewModel.resetToDefaults() },
                    onTestConnection = { host, port ->
                        mainViewModel.testPiholeConnection(host, port)
                    },
                    isTesting = isTesting
                )
                2 -> LogScreen(
                    logEntries = logEntries,
                    onClear = { mainViewModel.clearLogs() },
                    onExport = { mainViewModel.getLogFileContent() }
                )
                3 -> StatusScreen(
                    rootAvailable = rootAvailable,
                    capabilities = capabilities,
                    deviceInfo = mainViewModel.getDeviceInfo(),
                    onRedetect = { mainViewModel.redetectCapabilities() }
                )
            }
        }
    }

    // Test result dialog
    if (showTestDialog && testResult != null) {
        AlertDialog(
            onDismissRequest = {
                showTestDialog = false
                mainViewModel.clearTestResult()
            },
            title = { Text("Pi-hole Connection Test") },
            text = { Text(testResult ?: "") },
            confirmButton = {
                TextButton(onClick = {
                    showTestDialog = false
                    mainViewModel.clearTestResult()
                }) { Text("OK") }
            }
        )
    }
}

@Composable
fun RootRequiredScreen(onRetry: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "⚠",
                style = MaterialTheme.typography.headlineLarge,
                color = PiholeRed
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Root Access Required",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = PiholeRed
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Grant root permissions via Magisk/KSU and try again.\nThis app cannot function without root.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}
