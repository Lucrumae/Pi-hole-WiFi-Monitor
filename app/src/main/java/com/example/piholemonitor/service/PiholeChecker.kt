package com.example.piholemonitor.service

import com.example.piholemonitor.data.AppConfig
import com.example.piholemonitor.domain.LogTag
import com.example.piholemonitor.util.Logger
import com.example.piholemonitor.util.ShellExecutor
import kotlinx.coroutines.delay

/**
 * Checks if Pi-hole is reachable via port checking.
 * Check methods use runRootQuiet to avoid log flooding.
 */
class PiholeChecker {

    /**
     * Phase 6: Wait indefinitely for Pi-hole to come up.
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
     * Strict port check — NO ping fallback. Uses QUIET calls.
     */
    fun checkPortStrict(host: String, port: String): Boolean {
        run {
            val r = ShellExecutor.runRootQuiet("nc -z -w 2 $host $port")
            if (r.success) return true
        }
        run {
            val r = ShellExecutor.runRootQuiet("toybox nc -z -w 2 $host $port")
            if (r.success) return true
        }
        run {
            val r = ShellExecutor.runRootQuiet("busybox nc -z -w 2 $host $port")
            if (r.success) return true
        }
        if (port == "53") {
            val r = ShellExecutor.runRootQuiet("nslookup -timeout=2 google.com $host")
            if (r.success) return true
        }
        return false
    }

    /**
     * Port check with ping fallback. Uses QUIET calls.
     */
    fun checkPort(host: String, port: String): Boolean {
        if (checkPortStrict(host, port)) return true

        run {
            val r = ShellExecutor.runRootQuiet("ping -c 1 -W 2 $host")
            if (r.success) {
                Logger.log(LogTag.WARN, "Port $port unconfirmed, but ping to $host OK")
                return true
            }
        }

        return false
    }
}
