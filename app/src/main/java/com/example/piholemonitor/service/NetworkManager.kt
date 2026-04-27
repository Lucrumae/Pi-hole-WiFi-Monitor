package com.example.piholemonitor.service

import com.example.piholemonitor.data.AppConfig
import com.example.piholemonitor.domain.LogTag
import com.example.piholemonitor.util.Logger
import com.example.piholemonitor.util.ShellExecutor

/**
 * Handles custom IP assignment and DNS configuration.
 * All methods try commands unconditionally — if a command doesn't exist,
 * the shell returns exit 1 and the next fallback is tried immediately.
 */
class NetworkManager {

    /**
     * Phase 4: Add custom static IP to the WiFi interface.
     * Order: ip (modern) → ndc → toybox → ifconfig → busybox
     */
    fun addCustomIp(config: AppConfig): Boolean {
        Logger.log(LogTag.INFO, "Phase 4: Adding custom IP ${config.customIp}/${config.customPrefix} to ${config.wifiIface}...")

        val ip = config.customIp
        val prefix = config.customPrefix
        val mask = config.customMask
        val iface = config.wifiIface

        val commands = listOf(
            "ip addr add $ip/$prefix dev $iface",                    // Modern (iproute2)
            "ndc interface setcfg $iface $ip $prefix up",            // Android 5-9
            "toybox ip addr add $ip/$prefix dev $iface",             // Toybox fallback
            "ifconfig ${iface}:0 $ip netmask $mask up",              // Legacy alias
            "toybox ifconfig ${iface}:0 $ip netmask $mask up",       // Toybox legacy
            "busybox ip addr add $ip/$prefix dev $iface",            // Busybox
            "busybox ifconfig ${iface}:0 $ip netmask $mask up"       // Busybox legacy
        )

        val result = ShellExecutor.tryFallbacks("add_custom_ip", commands)
        return result != null
    }

    /**
     * Check if the custom IP is currently assigned.
     */
    fun hasCustomIp(config: AppConfig): Boolean {
        val ip = config.customIp
        val iface = config.wifiIface

        // Try each detection method
        run {
            val r = ShellExecutor.runRoot("ip addr show $iface")
            if (r.success && r.stdout.contains(ip)) return true
        }
        run {
            val r = ShellExecutor.runRoot("toybox ip addr show $iface")
            if (r.success && r.stdout.contains(ip)) return true
        }
        run {
            val r = ShellExecutor.runRoot("ifconfig $iface")
            if (r.success && r.stdout.contains(ip)) return true
            val r2 = ShellExecutor.runRoot("ifconfig ${iface}:0")
            if (r2.success && r2.stdout.contains(ip)) return true
        }
        run {
            val r = ShellExecutor.runRoot("busybox ip addr show $iface")
            if (r.success && r.stdout.contains(ip)) return true
        }

        return false
    }

    /**
     * Phase 5: Set DNS — runs ALL methods regardless of success.
     * Multiple enforcement layers are needed simultaneously.
     */
    fun setDns(config: AppConfig): Boolean {
        Logger.log(LogTag.INFO, "Phase 5: Setting DNS to ${config.dnsPrimary} / ${config.dnsSecondary}...")

        val dns1 = config.dnsPrimary
        val dns2 = config.dnsSecondary
        val iface = config.wifiIface
        var anySuccess = false

        // Method 1: cmd connectivity dns (Android 12+)
        run {
            val r = ShellExecutor.runRoot("cmd connectivity dns set $iface $dns1 $dns2")
            if (r.success) anySuccess = true
            else Logger.log(LogTag.FALLBACK, "cmd connectivity dns — exit ${r.exitCode}")
        }

        // Method 2: settings put global (Android 4.2+)
        run {
            val r1 = ShellExecutor.runRoot("settings put global wifi_static_dns1 $dns1")
            val r2 = ShellExecutor.runRoot("settings put global wifi_static_dns2 $dns2")
            if (r1.success && r2.success) anySuccess = true
            else Logger.log(LogTag.FALLBACK, "settings put dns — exit ${r1.exitCode}/${r2.exitCode}")
        }

        // Method 3: ndc resolver (Android 5+)
        run {
            val r1 = ShellExecutor.runRoot("ndc resolver setnetdns $iface \"\" $dns1 $dns2")
            if (!r1.success) {
                ShellExecutor.runRoot("ndc resolver setifdns $iface \"\" $dns1 $dns2")
            } else {
                anySuccess = true
            }
            ShellExecutor.runRoot("ndc resolver setdefaultif $iface")
        }

        // Method 4: setprop (All versions)
        run {
            val commands = listOf(
                "setprop net.dns1 $dns1",
                "setprop net.dns2 $dns2",
                "setprop net.$iface.dns1 $dns1",
                "setprop net.$iface.dns2 $dns2",
                "setprop dhcp.$iface.dns1 $dns1",
                "setprop dhcp.$iface.dns2 $dns2"
            )
            var allOk = true
            for (cmd in commands) {
                if (!ShellExecutor.runRoot(cmd).success) allOk = false
            }
            if (allOk) anySuccess = true
            else Logger.log(LogTag.FALLBACK, "setprop dns — some commands failed")
        }

        // Method 5: iptables DNAT (All versions)
        run {
            // Delete existing rules first (ignore errors)
            ShellExecutor.runRoot("iptables -t nat -D OUTPUT -p udp --dport 53 -j DNAT --to-destination $dns1:53")
            ShellExecutor.runRoot("iptables -t nat -D OUTPUT -p tcp --dport 53 -j DNAT --to-destination $dns1:53")
            // Add new rules
            val r1 = ShellExecutor.runRoot("iptables -t nat -A OUTPUT -p udp --dport 53 -j DNAT --to-destination $dns1:53")
            val r2 = ShellExecutor.runRoot("iptables -t nat -A OUTPUT -p tcp --dport 53 -j DNAT --to-destination $dns1:53")
            if (r1.success && r2.success) anySuccess = true
            else Logger.log(LogTag.FALLBACK, "iptables DNAT — exit ${r1.exitCode}/${r2.exitCode}")
        }

        // Method 6: content insert (Android 4.2+)
        run {
            val r1 = ShellExecutor.runRoot("content insert --uri content://settings/global --bind name:s:wifi_static_dns1 --bind value:s:$dns1")
            val r2 = ShellExecutor.runRoot("content insert --uri content://settings/global --bind name:s:wifi_static_dns2 --bind value:s:$dns2")
            if (r1.success && r2.success) anySuccess = true
            else Logger.log(LogTag.FALLBACK, "content insert dns — exit ${r1.exitCode}/${r2.exitCode}")
        }

        if (anySuccess) {
            Logger.log(LogTag.OK, "DNS configured (at least one method succeeded)")
        } else {
            Logger.log(LogTag.ERROR, "set_dns: all methods failed")
        }

        return anySuccess
    }

    /**
     * Remove iptables DNAT rules on service stop (cleanup).
     */
    fun cleanupIptables(config: AppConfig) {
        val dns1 = config.dnsPrimary
        Logger.log(LogTag.INFO, "Cleaning up iptables DNAT rules...")
        ShellExecutor.runRoot("iptables -t nat -D OUTPUT -p udp --dport 53 -j DNAT --to-destination $dns1:53")
        ShellExecutor.runRoot("iptables -t nat -D OUTPUT -p tcp --dport 53 -j DNAT --to-destination $dns1:53")
    }
}
