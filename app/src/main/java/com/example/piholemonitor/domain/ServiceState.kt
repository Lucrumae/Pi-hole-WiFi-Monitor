package com.example.piholemonitor.domain

/**
 * Represents the current state of the PiholeMonitorService,
 * exposed as a StateFlow for UI observation.
 */
data class ServiceState(
    val isRunning: Boolean = false,
    val currentPhase: String = "Idle",
    val wifiConnected: Boolean = false,
    val currentIp: String = "",
    val piholeUp: Boolean = false,
    val failCount: Int = 0,
    val uptimeMs: Long = 0L,
    val lastReconnectTime: Long? = null
)
