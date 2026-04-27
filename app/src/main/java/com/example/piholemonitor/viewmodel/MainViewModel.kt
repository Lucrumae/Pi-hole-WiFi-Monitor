package com.example.piholemonitor.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.piholemonitor.data.SettingsRepository
import com.example.piholemonitor.domain.LogEntry
import com.example.piholemonitor.domain.ServiceState
import com.example.piholemonitor.service.PiholeMonitorService
import com.example.piholemonitor.util.Logger
import com.example.piholemonitor.util.ShellExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the main screens: Dashboard, Log, Status.
 * Observes service state and log entries.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    val serviceState: StateFlow<ServiceState> = PiholeMonitorService.serviceState

    private val _capabilities = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val capabilities: StateFlow<Map<String, Boolean>> = _capabilities.asStateFlow()

    val logEntries: StateFlow<List<LogEntry>> = Logger.entries

    private val _rootAvailable = MutableStateFlow<Boolean?>(null)
    val rootAvailable: StateFlow<Boolean?> = _rootAvailable.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    init {
        checkRoot()
        // Collect capabilities from service
        viewModelScope.launch {
            PiholeMonitorService.capabilitiesState.collect { caps ->
                _capabilities.value = caps
            }
        }
    }

    fun checkRoot() {
        viewModelScope.launch {
            _rootAvailable.value = try {
                ShellExecutor.isRootAvailable()
            } catch (_: Exception) {
                false
            }
        }
    }

    fun startService() {
        val intent = Intent(context, PiholeMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopService() {
        val intent = Intent(context, PiholeMonitorService::class.java).apply {
            action = PiholeMonitorService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun clearLogs() {
        Logger.clear()
    }

    fun getLogFileContent(): String {
        return Logger.readLogFile()
    }

    fun testPiholeConnection(host: String, port: String) {
        viewModelScope.launch {
            _isTesting.value = true
            _testResult.value = null

            try {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    // Try nc first
                    var success = ShellExecutor.runRootBool("nc -z -w 2 $host $port")
                    if (!success) success = ShellExecutor.runRootBool("toybox nc -z -w 2 $host $port")
                    if (!success) success = ShellExecutor.runRootBool("busybox nc -z -w 2 $host $port")
                    if (!success && port == "53") {
                        success = ShellExecutor.runRootBool("nslookup -timeout=2 google.com $host")
                    }
                    success
                }

                _testResult.value = if (result) {
                    "✓ Pi-hole is reachable at $host:$port"
                } else {
                    "✗ Pi-hole not reachable. Is Pi Deploy running?"
                }
            } catch (e: Exception) {
                _testResult.value = "✗ Error: ${e.message}"
            } finally {
                _isTesting.value = false
            }
        }
    }

    fun clearTestResult() {
        _testResult.value = null
    }

    fun getDeviceInfo(): Map<String, String> {
        val info = mutableMapOf<String, String>()
        info["Android Version"] = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        info["Architecture"] = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

        try {
            val kernel = ShellExecutor.runRootOutput("uname -r")
            info["Kernel"] = kernel.ifEmpty { "unknown" }
        } catch (_: Exception) {
            info["Kernel"] = "unknown"
        }

        return info
    }

    fun redetectCapabilities() {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val detector = com.example.piholemonitor.service.CapabilityDetector()
                detector.detectAll()
                // Update the capabilities flow so the Status screen can display it
                _capabilities.value = detector.capabilities
            }
        }
    }
}
