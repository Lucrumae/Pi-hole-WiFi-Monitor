package com.example.piholemonitor.service

import com.example.piholemonitor.data.AppConfig
import com.example.piholemonitor.domain.LogTag
import com.example.piholemonitor.util.Logger
import com.example.piholemonitor.util.ShellExecutor
import kotlinx.coroutines.delay

/**
 * Handles WiFi enable, connect, status checks, and IP detection.
 * All methods use unconditional fallback chains — commands are tried in order
 * from newest Android version to oldest. If a command doesn't exist or fails,
 * the shell returns exit 1 and the next fallback is tried immediately.
 * This matches the bash script's behavior exactly.
 */
class WifiManager {

    /**
     * Phase 1: Enable WiFi. Try each method, stop at first success.
     * Order: cmd (Android 11+) → svc (7+) → settings (4.2+) → ndc (5-9) → setprop → ip link → ifconfig → busybox
     */
    suspend fun wifiEnable(config: AppConfig): Boolean {
        Logger.log(LogTag.INFO, "Phase 1: Enabling WiFi...")

        val iface = config.wifiIface
        val commands = listOf(
            "cmd wifi set-wifi-enabled enabled",        // Android 11+
            "svc wifi enable",                          // Android 7+
            "settings put global wifi_on 1",            // Android 4.2+
            "ndc wifi enable",                          // Android 5-9
            "setprop ctl.start wpa_supplicant",         // All versions
            "ip link set $iface up",                    // All versions
            "ifconfig $iface up",                       // Legacy
            "busybox ifconfig $iface up"                // Busybox fallback
        )

        val result = ShellExecutor.tryFallbacks("wifi_enable", commands)
        if (result != null) {
            Logger.log(LogTag.OK, "WiFi enabled successfully")
            delay(3000)
            return true
        }
        return false
    }

    /**
     * Phase 2: Connect to WiFi network. Skip if already connected.
     * Tries methods from newest to oldest Android version.
     */
    suspend fun wifiConnect(config: AppConfig): Boolean {
        Logger.log(LogTag.INFO, "Phase 2: Connecting to WiFi '${config.wifiSsid}'...")

        // Skip if already connected
        if (wifiIsConnected(config)) {
            Logger.log(LogTag.OK, "Already connected to ${config.wifiSsid}")
            return true
        }

        val ssid = config.wifiSsid
        val pass = config.wifiPass
        val iface = config.wifiIface

        // Method 1: cmd wifi connect-network wpa2 (Android 12+)
        run {
            val r = ShellExecutor.runRoot("cmd wifi connect-network \"$ssid\" wpa2 \"$pass\"")
            if (r.success) { Logger.log(LogTag.OK, "Connected via cmd wifi (wpa2)"); return true }
            Logger.log(LogTag.FALLBACK, "cmd wifi connect-network wpa2 — exit ${r.exitCode}, trying next method...")
        }

        // Method 2: cmd wifi connect-network open (Android 12+)
        run {
            val r = ShellExecutor.runRoot("cmd wifi connect-network \"$ssid\" open")
            if (r.success) { Logger.log(LogTag.OK, "Connected via cmd wifi (open)"); return true }
            Logger.log(LogTag.FALLBACK, "cmd wifi connect-network open — exit ${r.exitCode}, trying next method...")
        }

        // Method 3: cmd wifi add-network + scan + connect (Android 12+)
        run {
            ShellExecutor.runRoot("cmd wifi add-network \"$ssid\" wpa2 \"$pass\"")
            ShellExecutor.runRoot("cmd wifi start-scan")
            delay(3000)
            val r = ShellExecutor.runRoot("cmd wifi connect-network \"$ssid\" wpa2 \"$pass\"")
            if (r.success) { Logger.log(LogTag.OK, "Connected via cmd wifi (add+scan+connect)"); return true }
            Logger.log(LogTag.FALLBACK, "cmd wifi add+scan+connect — exit ${r.exitCode}, trying next method...")
        }

        // Method 4: wpa_cli list_networks + select (Android 5+)
        run {
            val listResult = ShellExecutor.runRoot("wpa_cli -i $iface list_networks")
            if (listResult.success) {
                val networkId = parseNetworkId(listResult.stdout, ssid)
                if (networkId != null) {
                    val r = ShellExecutor.runRoot("wpa_cli -i $iface select_network $networkId")
                    if (r.success) { Logger.log(LogTag.OK, "Connected via wpa_cli select_network"); return true }
                    Logger.log(LogTag.FALLBACK, "wpa_cli select_network — exit ${r.exitCode}, trying next method...")
                }
            }
        }

        // Method 5: wpa_cli add_network + configure (Android 5+)
        run {
            val addResult = ShellExecutor.runRoot("wpa_cli -i $iface add_network")
            if (addResult.success) {
                val netId = addResult.stdout.trim()
                ShellExecutor.runRoot("wpa_cli -i $iface set_network $netId ssid '\"$ssid\"'")
                ShellExecutor.runRoot("wpa_cli -i $iface set_network $netId psk '\"$pass\"'")
                ShellExecutor.runRoot("wpa_cli -i $iface set_network $netId key_mgmt WPA-PSK")
                ShellExecutor.runRoot("wpa_cli -i $iface enable_network $netId")
                val r = ShellExecutor.runRoot("wpa_cli -i $iface select_network $netId")
                if (r.success) { Logger.log(LogTag.OK, "Connected via wpa_cli add+configure"); return true }
                Logger.log(LogTag.FALLBACK, "wpa_cli add+configure — exit ${r.exitCode}, trying next method...")
            }
        }

        // Method 6: wpa_cli reassociate + reconnect (Android 5+)
        run {
            ShellExecutor.runRoot("wpa_cli -i $iface reassociate")
            val r = ShellExecutor.runRoot("wpa_cli -i $iface reconnect")
            if (r.success) { Logger.log(LogTag.OK, "Connected via wpa_cli reassociate/reconnect"); return true }
            Logger.log(LogTag.FALLBACK, "wpa_cli reassociate/reconnect — exit ${r.exitCode}, trying next method...")
        }

        // Method 7: ndc wifi connect (Android 5-9)
        run {
            val r = ShellExecutor.runRoot("ndc wifi connect \"$ssid\"")
            if (r.success) { Logger.log(LogTag.OK, "Connected via ndc wifi connect"); return true }
            Logger.log(LogTag.FALLBACK, "ndc wifi connect — exit ${r.exitCode}")
        }

        Logger.log(LogTag.ERROR, "wifi_connect: all methods failed")
        return false
    }

    /**
     * Phase 3: Wait until WiFi is connected with an IP address.
     */
    suspend fun wifiWaitConnected(config: AppConfig): Boolean {
        Logger.log(LogTag.INFO, "Phase 3: Waiting for WiFi connection (timeout: ${config.wifiConnectTimeout}s)...")

        val deadline = System.currentTimeMillis() + config.wifiConnectTimeout * 1000L

        while (System.currentTimeMillis() < deadline) {
            if (wifiIsConnected(config)) {
                // Now wait for IP
                val ipDeadline = System.currentTimeMillis() + 10_000L
                while (System.currentTimeMillis() < ipDeadline) {
                    if (wifiHasIp(config)) {
                        Logger.log(LogTag.OK, "WiFi connected with IP address")
                        return true
                    }
                    delay(1000)
                }
            }
            delay(2000)
        }

        Logger.log(LogTag.ERROR, "WiFi connection timed out after ${config.wifiConnectTimeout}s")
        return false
    }

    /**
     * Check if WiFi is connected to the configured SSID.
     * Tries each detection method; returns true at first confirmation.
     */
    fun wifiIsConnected(config: AppConfig): Boolean {
        val ssid = config.wifiSsid
        val iface = config.wifiIface

        // Method 1: cmd wifi status (Android 11+)
        run {
            val r = ShellExecutor.runRoot("cmd wifi status")
            if (r.success) {
                val out = r.stdout.lowercase()
                if (out.contains("connected") && out.contains(ssid.lowercase())) return true
            }
        }

        // Method 2: dumpsys wifi (Android 4.1+)
        run {
            val r = ShellExecutor.runRoot("dumpsys wifi")
            if (r.success) {
                val out = r.stdout.lowercase()
                if (out.contains("mnetworkinfo") && out.contains("state: connected") && out.contains(ssid.lowercase())) return true
            }
        }

        // Method 3: wpa_cli status (Android 5+)
        run {
            val r = ShellExecutor.runRoot("wpa_cli -i $iface status")
            if (r.success) {
                val out = r.stdout.lowercase()
                if (out.contains("wpa_state=completed") && out.contains("ssid=$ssid".lowercase())) return true
            }
        }

        // Method 4: getprop checks (All versions)
        run {
            val driverStatus = ShellExecutor.runRootOutput("getprop wlan.driver.status").lowercase()
            if (driverStatus == "ok" || driverStatus == "loaded") {
                val ifaceStatus = ShellExecutor.runRootOutput("getprop wifi.interface.status").lowercase()
                if (ifaceStatus == "connected") return true
                val dhcpIp = ShellExecutor.runRootOutput("getprop dhcp.$iface.ipaddress")
                if (dhcpIp.isNotEmpty() && dhcpIp != "0.0.0.0") return true
            }
        }

        // Method 5: ip link + ip addr (All versions)
        run {
            val linkResult = ShellExecutor.runRoot("ip link show $iface")
            if (linkResult.success && linkResult.stdout.lowercase().contains("state up")) {
                val addrResult = ShellExecutor.runRoot("ip addr show $iface")
                if (addrResult.success && addrResult.stdout.contains("inet ")) return true
            }
        }

        // Method 6: ifconfig (Legacy)
        run {
            val r = ShellExecutor.runRoot("ifconfig $iface")
            if (r.success) {
                val out = r.stdout
                if (out.contains("UP") && (out.contains("inet addr:") || out.contains("inet "))) return true
            }
        }

        return false
    }

    /**
     * Check if the WiFi interface has an IP address.
     */
    fun wifiHasIp(config: AppConfig): Boolean {
        val iface = config.wifiIface

        // Method 1: ip addr show
        run {
            val r = ShellExecutor.runRoot("ip addr show $iface")
            if (r.success && r.stdout.contains("inet ")) return true
        }

        // Method 2: toybox ifconfig
        run {
            val r = ShellExecutor.runRoot("toybox ifconfig $iface")
            if (r.success && r.stdout.contains("inet ")) return true
        }

        // Method 3: ifconfig
        run {
            val r = ShellExecutor.runRoot("ifconfig $iface")
            if (r.success && (r.stdout.contains("inet addr:") || r.stdout.contains("inet "))) return true
        }

        // Method 4: getprop dhcp
        run {
            val ip = ShellExecutor.runRootOutput("getprop dhcp.$iface.ipaddress")
            if (ip.isNotEmpty() && ip != "0.0.0.0") return true
        }

        // Method 5: busybox ifconfig
        run {
            val r = ShellExecutor.runRoot("busybox ifconfig $iface")
            if (r.success && r.stdout.contains("inet addr:")) return true
        }

        return false
    }

    /**
     * Get the current IP address of the WiFi interface.
     */
    fun getCurrentIp(config: AppConfig): String {
        val iface = config.wifiIface

        // Method 1: ip -4 addr show + grep/cut (simple)
        run {
            val r = ShellExecutor.runRoot("ip -4 addr show $iface")
            if (r.success) {
                val ip = extractIpFromOutput(r.stdout)
                if (ip.isNotEmpty()) return ip
            }
        }

        // Method 2: ifconfig (old format)
        run {
            val r = ShellExecutor.runRoot("ifconfig $iface")
            if (r.success) {
                val ip = extractIpFromIfconfig(r.stdout)
                if (ip.isNotEmpty()) return ip
            }
        }

        // Method 3: toybox ifconfig
        run {
            val r = ShellExecutor.runRoot("toybox ifconfig $iface")
            if (r.success) {
                val ip = extractIpFromOutput(r.stdout)
                if (ip.isNotEmpty()) return ip
            }
        }

        // Method 4: getprop
        run {
            val ip = ShellExecutor.runRootOutput("getprop dhcp.$iface.ipaddress")
            if (ip.isNotEmpty() && ip != "0.0.0.0") return ip
        }

        // Method 5: busybox ifconfig
        run {
            val r = ShellExecutor.runRoot("busybox ifconfig $iface")
            if (r.success) {
                val ip = extractIpFromIfconfig(r.stdout)
                if (ip.isNotEmpty()) return ip
            }
        }

        return ""
    }

    /**
     * Quick reconnect attempt (used in monitor loop for fail counts < 5).
     */
    suspend fun quickReconnect(config: AppConfig) {
        val ssid = config.wifiSsid
        val pass = config.wifiPass
        val iface = config.wifiIface

        ShellExecutor.runRoot("cmd wifi connect-network \"$ssid\" wpa2 \"$pass\"")
        ShellExecutor.runRoot("wpa_cli -i $iface reassociate")
        ShellExecutor.runRoot("wpa_cli -i $iface reconnect")
        ShellExecutor.runRoot("ndc wifi connect \"$ssid\"")
        delay(5000)
    }

    /**
     * Extract IP from `ip addr show` output.
     */
    private fun extractIpFromOutput(output: String): String {
        val regex = Regex("""inet\s+(\d+\.\d+\.\d+\.\d+)""")
        return regex.find(output)?.groupValues?.get(1) ?: ""
    }

    /**
     * Extract IP from old-style `ifconfig` output (inet addr:x.x.x.x).
     */
    private fun extractIpFromIfconfig(output: String): String {
        // Try old format: inet addr:192.168.1.100
        val oldRegex = Regex("""inet addr:(\d+\.\d+\.\d+\.\d+)""")
        oldRegex.find(output)?.groupValues?.get(1)?.let { return it }
        // Try new format: inet 192.168.1.100
        return extractIpFromOutput(output)
    }

    /**
     * Parse wpa_cli list_networks output to find network ID for SSID.
     */
    private fun parseNetworkId(output: String, ssid: String): String? {
        val lines = output.lines()
        for (line in lines) {
            if (line.contains(ssid, ignoreCase = true)) {
                // Format: "network id / ssid / bssid / flags"
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.isNotEmpty()) {
                    val id = parts[0]
                    if (id.all { it.isDigit() }) return id
                }
                // Also try tab-separated (busybox/toybox format)
                val tabParts = line.trim().split("\t")
                if (tabParts.isNotEmpty()) {
                    val id = tabParts[0].trim()
                    if (id.all { it.isDigit() }) return id
                }
            }
        }
        return null
    }
}
