package com.example.piholemonitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.piholemonitor.MainActivity
import com.example.piholemonitor.R
import com.example.piholemonitor.data.AppConfig
import com.example.piholemonitor.data.SettingsRepository
import com.example.piholemonitor.domain.LogTag
import com.example.piholemonitor.domain.ServiceState
import com.example.piholemonitor.util.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground service that runs the Pi-hole monitoring pipeline.
 * Executes phases 0-8 sequentially and exposes state via StateFlow.
 */
class PiholeMonitorService : Service() {

    companion object {
        private const val CHANNEL_ID = "pihole_monitor"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.example.piholemonitor.STOP"

        private val _serviceState = MutableStateFlow(ServiceState())
        val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

        private val _capabilitiesState = MutableStateFlow<Map<String, Boolean>>(emptyMap())
        val capabilitiesState: StateFlow<Map<String, Boolean>> = _capabilitiesState.asStateFlow()

        fun isRunning(): Boolean = _serviceState.value.isRunning
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var startTime = 0L

    // Cache config so onDestroy doesn't need to block on DataStore
    @Volatile
    private var cachedConfig: AppConfig? = null

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var capabilityDetector: CapabilityDetector
    private lateinit var wifiManager: WifiManager
    private lateinit var networkManager: NetworkManager
    private lateinit var piholeChecker: PiholeChecker
    private lateinit var tetheringManager: TetheringManager
    private lateinit var monitorLoop: MonitorLoop

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(applicationContext)
        capabilityDetector = CapabilityDetector()
        wifiManager = WifiManager()
        networkManager = NetworkManager()
        piholeChecker = PiholeChecker()
        tetheringManager = TetheringManager()
        monitorLoop = MonitorLoop(wifiManager, networkManager, piholeChecker, tetheringManager)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        startMonitoring()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Logger.log(LogTag.INFO, "Service stopping...")

        // Cancel the monitoring job first
        monitorJob?.cancel()

        // Cleanup iptables using cached config (non-blocking)
        cachedConfig?.let { config ->
            try {
                networkManager.cleanupIptables(config)
            } catch (_: Exception) {}
        }

        serviceScope.cancel()

        _serviceState.value = ServiceState(isRunning = false)
        Logger.log(LogTag.INFO, "Service stopped")

        super.onDestroy()
    }

    private fun startMonitoring() {
        if (monitorJob?.isActive == true) return

        startTime = System.currentTimeMillis()
        _serviceState.value = ServiceState(isRunning = true, currentPhase = "Initializing")

        monitorJob = serviceScope.launch {
            try {
                val config = settingsRepo.getConfig()
                cachedConfig = config  // Cache for onDestroy
                Logger.log(LogTag.INFO, "Pi-hole Monitor Service starting...")
                Logger.log(LogTag.INFO, "Config: SSID=${config.wifiSsid}, IP=${config.customIp}, Pi-hole=${config.piholeIp}:${config.piholePort}")

                // Phase 0: Detect capabilities
                updatePhase("Phase 0: Detecting capabilities")
                capabilityDetector.detectAll()
                _capabilitiesState.value = capabilityDetector.capabilities

                // Phase 1: Enable WiFi
                updatePhase("Phase 1: Enabling WiFi")
                wifiManager.wifiEnable(config)

                // Phase 2: Connect to WiFi
                updatePhase("Phase 2: Connecting to WiFi")
                wifiManager.wifiConnect(config)

                // Phase 3: Wait for connection
                updatePhase("Phase 3: Waiting for WiFi connection")
                wifiManager.wifiWaitConnected(config)

                // Phase 4: Add custom IP
                updatePhase("Phase 4: Adding custom IP")
                networkManager.addCustomIp(config)

                // Phase 5: Set DNS
                updatePhase("Phase 5: Setting DNS")
                networkManager.setDns(config)

                // Phase 6: Wait for Pi-hole
                updatePhase("Phase 6: Waiting for Pi-hole")
                piholeChecker.waitForPihole(config)

                // Phase 7: Tethering
                updatePhase("Phase 7: Configuring tethering")
                tetheringManager.tetherEthEnable(config)
                tetheringManager.tetherUsbEnable(config)

                // Phase 8: Monitor loop
                updatePhase("Phase 8: Monitoring")
                _serviceState.value = _serviceState.value.copy(
                    currentPhase = "Monitoring",
                    wifiConnected = true,
                    currentIp = wifiManager.getCurrentIp(config),
                    piholeUp = true
                )
                updateNotification("● Monitoring — Pi-hole UP")

                monitorLoop.run(config, _serviceState, startTime)

            } catch (e: CancellationException) {
                Logger.log(LogTag.INFO, "Service cancelled")
            } catch (e: Exception) {
                Logger.log(LogTag.ERROR, "Service error: ${e.message}")
                _serviceState.value = _serviceState.value.copy(
                    currentPhase = "Error: ${e.message}"
                )
                updateNotification("✗ Error: ${e.message}")
            }
        }
    }

    private fun updatePhase(phase: String) {
        _serviceState.value = _serviceState.value.copy(
            currentPhase = phase,
            uptimeMs = System.currentTimeMillis() - startTime
        )
        updateNotification(phase)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pi-hole Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Pi-hole WiFi Monitor service status"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        // Main tap intent
        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPending = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop action
        val stopIntent = Intent(this, PiholeMonitorService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pi-hole Monitor")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(mainPending)
            .addAction(R.drawable.ic_notification, "Stop", stopPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(content: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(content))
        } catch (_: Exception) {}
    }
}
