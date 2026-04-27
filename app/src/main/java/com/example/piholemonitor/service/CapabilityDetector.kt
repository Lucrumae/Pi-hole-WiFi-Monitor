package com.example.piholemonitor.service

import com.example.piholemonitor.domain.LogTag
import com.example.piholemonitor.util.Logger
import com.example.piholemonitor.util.ShellExecutor

/**
 * Detects available shell commands on the device via `command -v`.
 * Results are cached; re-detection requires explicit call.
 */
class CapabilityDetector {

    /**
     * All commands to detect.
     */
    private val commandsToDetect = listOf(
        "ip", "ifconfig", "route", "wpa_cli", "cmd", "svc",
        "settings", "ndc", "setprop", "getprop", "nc", "ping",
        "awk", "sed", "grep", "dumpsys", "toybox", "busybox",
        "nslookup", "iptables", "content"
    )

    /**
     * Map of command name to availability (true = detected).
     */
    private val _capabilities = mutableMapOf<String, Boolean>()
    val capabilities: Map<String, Boolean> get() = _capabilities.toMap()

    /**
     * Check if a specific command is available.
     */
    fun has(command: String): Boolean = _capabilities[command] == true

    /**
     * Detect all capabilities. Call once on service start.
     */
    fun detectAll() {
        Logger.log(LogTag.INFO, "Detecting available commands...")
        _capabilities.clear()

        for (cmd in commandsToDetect) {
            val result = ShellExecutor.runRoot("command -v $cmd")
            val available = result.success && result.stdout.isNotBlank()
            _capabilities[cmd] = available

            val icon = if (available) "✓" else "✗"
            Logger.log(LogTag.INFO, "$icon $cmd ${if (available) "detected" else "not found"}")
        }

        val detected = _capabilities.count { it.value }
        val total = _capabilities.size
        Logger.log(LogTag.INFO, "Capability detection complete: $detected/$total commands available")
    }
}
