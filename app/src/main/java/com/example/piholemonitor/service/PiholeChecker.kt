package com.example.piholemonitor.service

import com.example.piholemonitor.data.AppConfig
import com.example.piholemonitor.domain.LogTag
import com.example.piholemonitor.util.Logger
import com.example.piholemonitor.util.ShellExecutor
import kotlinx.coroutines.delay

/**
 * Checks if Pi-hole is reachable via port checking.
 * Supports strict mode (no ping) and relaxed mode (with ping fallback).
 */
class PiholeChecker(
    private val caps: CapabilityDetector
) {

    /**
     * Phase 6: Wait indefinitely for Pi-hole to come up.
     * Check every 5 seconds, warn every 30 seconds.
     */
    suspend fun waitForPihole(config: AppConfig) {
        Logger.log(LogTag.INFO, "Phase 6: Waiting for Pi-hole at ${config.piholeIp}:${config.piholePort}...")

        var elapsed = 0L
        while (true) {
            if (checkPortStrict(config.piholeIp, config.piholePort)) {
                Logger.log(LogTag.OK, "Pi-hole is UP at ${config.piholeIp}:${config.piholePort}")
                return
            }

            delay(5000)
            elapsed += 5

            if (elapsed % 30 == 0L) {
                Logger.log(LogTag.WARN, "Still waiting for Pi-hole at ${config.piholeIp}:${config.piholePort} (${elapsed}s elapsed)")
            }
        }
    }

    /**
     * Strict port check — NO ping fallback.
     * Used for wait_for_pihole (Phase 6).
     */
    fun checkPortStrict(host: String, port: String): Boolean {
        // Method 1: nc
        if (caps.has("nc")) {
            val r = ShellExecutor.runRoot("nc -z -w 2 $host $port")
            if (r.success) return true
        }

        // Method 2: toybox nc
        if (caps.has("toybox")) {
            val r = ShellExecutor.runRoot("toybox nc -z -w 2 $host $port")
            if (r.success) return true
        }

        // Method 3: busybox nc
        if (caps.has("busybox")) {
            val r = ShellExecutor.runRoot("busybox nc -z -w 2 $host $port")
            if (r.success) return true
        }

        // Method 4: nslookup (only for port 53)
        if (port == "53" && caps.has("nslookup")) {
            val r = ShellExecutor.runRoot("nslookup -timeout=2 google.com $host")
            if (r.success) return true
        }

        return false
    }

    /**
     * Port check with ping fallback.
     * Used in monitor loop.
     */
    fun checkPort(host: String, port: String): Boolean {
        // Try strict methods first
        if (checkPortStrict(host, port)) return true

        // Method 5: ping fallback
        if (caps.has("ping")) {
            val r = ShellExecutor.runRoot("ping -c 1 -W 2 $host")
            if (r.success) {
                Logger.log(LogTag.WARN, "Port $port unconfirmed, but ping to $host OK")
                return true
            }
        }

        return false
    }
}
