package com.example.piholemonitor.service

import com.example.piholemonitor.data.AppConfig
import com.example.piholemonitor.domain.LogTag
import com.example.piholemonitor.domain.ServiceState
import com.example.piholemonitor.util.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Phase 8: The main monitoring loop.
 * Checks WiFi, IP, Pi-hole status and auto-recovers on failure.
 */
class MonitorLoop(
    private val wifiManager: WifiManager,
    private val networkManager: NetworkManager,
    private val piholeChecker: PiholeChecker,
    private val tetheringManager: TetheringManager,
    private val caps: CapabilityDetector
) {

    /**
     * Run the infinite monitor loop.
     * Updates serviceState on each iteration.
     */
    suspend fun run(
        config: AppConfig,
        serviceState: MutableStateFlow<ServiceState>,
        startTime: Long
    ) {
        Logger.log(LogTag.INFO, "Phase 8: Starting monitor loop (interval: ${config.monitorInterval}s)")

        var failCount = 0

        while (true) {
            try {
                val uptimeMs = System.currentTimeMillis() - startTime

                if (wifiManager.wifiIsConnected(config) && wifiManager.wifiHasIp(config)) {
                    failCount = 0
                    val currentIp = wifiManager.getCurrentIp(config)

                    // Check Pi-hole
                    if (!piholeChecker.checkPort(config.piholeIp, config.piholePort)) {
                        Logger.log(LogTag.WARN, "Pi-hole unreachable — reapplying DNS")
                        networkManager.setDns(config)
                    }

                    // Check custom IP
                    if (!networkManager.hasCustomIp(config)) {
                        Logger.log(LogTag.WARN, "Custom IP missing — re-adding")
                        networkManager.addCustomIp(config)
                        networkManager.setDns(config)
                    }

                    serviceState.value = serviceState.value.copy(
                        currentPhase = "Monitoring",
                        wifiConnected = true,
                        currentIp = currentIp,
                        piholeUp = piholeChecker.checkPortStrict(config.piholeIp, config.piholePort),
                        failCount = 0,
                        uptimeMs = uptimeMs
                    )
                } else {
                    failCount++
                    Logger.log(LogTag.WARN, "WiFi down or no IP (fail $failCount/5)")

                    serviceState.value = serviceState.value.copy(
                        currentPhase = "Reconnecting ($failCount/5)",
                        wifiConnected = false,
                        failCount = failCount,
                        uptimeMs = uptimeMs
                    )

                    if (failCount >= 5) {
                        failCount = 0
                        Logger.log(LogTag.ERROR, "Max failures reached — full reconnect")

                        wifiManager.wifiEnable(config)
                        delay(2000)
                        wifiManager.wifiConnect(config)
                        val connected = wifiManager.wifiWaitConnected(config)

                        if (connected) {
                            networkManager.addCustomIp(config)
                            networkManager.setDns(config)
                            piholeChecker.waitForPihole(config)
                            tetheringManager.tetherEthEnable(config)
                            tetheringManager.tetherUsbEnable(config)

                            serviceState.value = serviceState.value.copy(
                                currentPhase = "Monitoring",
                                wifiConnected = true,
                                currentIp = wifiManager.getCurrentIp(config),
                                piholeUp = true,
                                failCount = 0,
                                lastReconnectTime = System.currentTimeMillis(),
                                uptimeMs = System.currentTimeMillis() - startTime
                            )
                        } else {
                            Logger.log(LogTag.ERROR, "Full reconnect failed")
                            serviceState.value = serviceState.value.copy(
                                currentPhase = "Reconnect Failed",
                                wifiConnected = false,
                                failCount = 0,
                                uptimeMs = System.currentTimeMillis() - startTime
                            )
                        }
                    } else {
                        // Quick reconnect
                        wifiManager.quickReconnect(config)

                        if (wifiManager.wifiIsConnected(config) && wifiManager.wifiHasIp(config)) {
                            failCount = 0
                            if (!networkManager.hasCustomIp(config)) {
                                networkManager.addCustomIp(config)
                                networkManager.setDns(config)
                            }
                            serviceState.value = serviceState.value.copy(
                                currentPhase = "Monitoring",
                                wifiConnected = true,
                                currentIp = wifiManager.getCurrentIp(config),
                                failCount = 0,
                                lastReconnectTime = System.currentTimeMillis(),
                                uptimeMs = System.currentTimeMillis() - startTime
                            )
                        }
                    }
                }

            } catch (e: Exception) {
                Logger.log(LogTag.ERROR, "Monitor loop exception: ${e.message}")
            }

            delay(config.monitorInterval * 1000L)
        }
    }
}
