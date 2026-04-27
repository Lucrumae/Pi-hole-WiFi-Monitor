package com.example.piholemonitor.service

import com.example.piholemonitor.data.AppConfig
import com.example.piholemonitor.domain.LogTag
import com.example.piholemonitor.util.Logger
import com.example.piholemonitor.util.ShellExecutor

/**
 * Handles custom IP assignment and DNS configuration.
 * All methods use fallback chains per the specification.
 */
class NetworkManager(
    private val caps: CapabilityDetector
) {

    /**
     * Phase 4: Add custom static IP to the WiFi interface.
     */
    fun addCustomIp(config: AppConfig): Boolean {
        Logger.log(LogTag.INFO, "Phase 4: Adding custom IP ${config.customIp}/${config.customPrefix} to ${config.wifiIface}...")

        val ip = config.customIp
        val prefix = config.customPrefix
        val mask = config.customMask
        val iface = config.wifiIface

        val commands = mutableListOf<String>()

        if (caps.has("ip")) commands.add("ip addr add $ip/$prefix dev $iface")
        if (caps.has("ndc")) commands.add("ndc interface setcfg $iface $ip $prefix up")
        if (caps.has("toybox")) commands.add("toybox ip addr add $ip/$prefix dev $iface")
        if (caps.has("ifconfig")) commands.add("ifconfig ${iface}:0 $ip netmask $mask up")
        if (caps.has("toybox")) commands.add("toybox ifconfig ${iface}:0 $ip netmask $mask up")
        if (caps.has("busybox")) commands.add("busybox ip addr add $ip/$prefix dev $iface")
        if (caps.has("busybox")) commands.add("busybox ifconfig ${iface}:0 $ip netmask $mask up")

        val result = ShellExecutor.tryFallbacks("add_custom_ip", commands)
        return result != null
    }

    /**
     * Check if the custom IP is currently assigned.
     */
    fun hasCustomIp(config: AppConfig): Boolean {
        val ip = config.customIp
        val iface = config.wifiIface

        if (caps.has("ip")) {
            val r = ShellExecutor.runRoot("ip addr show $iface")
            if (r.success && r.stdout.contains(ip)) return true
        }
        if (caps.has("toybox")) {
            val r = ShellExecutor.runRoot("toybox ip addr show $iface")
            if (r.success && r.stdout.contains(ip)) return true
        }
        if (caps.has("ifconfig")) {
            val r = ShellExecutor.runRoot("ifconfig $iface")
            if (r.success && r.stdout.contains(ip)) return true

            val r2 = ShellExecutor.runRoot("ifconfig ${iface}:0")
            if (r2.success && r2.stdout.contains(ip)) return true
        }
        if (caps.has("busybox")) {
            val r = ShellExecutor.runRoot("busybox ip addr show $iface")
            if (r.success && r.stdout.contains(ip)) return true
        }

        return false
    }

    /**
     * Phase 5: Set DNS — runs ALL methods regardless of success.
     */
    fun setDns(config: AppConfig): Boolean {
        Logger.log(LogTag.INFO, "Phase 5: Setting DNS to ${config.dnsPrimary} / ${config.dnsSecondary}...")

        val dns1 = config.dnsPrimary
        val dns2 = config.dnsSecondary
        val iface = config.wifiIface
        var anySuccess = false

        // Method 1: cmd connectivity dns
        if (caps.has("cmd")) {
            val r = ShellExecutor.runRoot("cmd connectivity dns set $iface $dns1 $dns2")
            if (r.success) anySuccess = true
            else Logger.log(LogTag.FALLBACK, "cmd connectivity dns — exit ${r.exitCode}")
        }

        // Method 2: settings put global
        if (caps.has("settings")) {
            val r1 = ShellExecutor.runRoot("settings put global wifi_static_dns1 $dns1")
            val r2 = ShellExecutor.runRoot("settings put global wifi_static_dns2 $dns2")
            if (r1.success && r2.success) anySuccess = true
            else Logger.log(LogTag.FALLBACK, "settings put dns — exit ${r1.exitCode}/${r2.exitCode}")
        }

        // Method 3: ndc resolver
        if (caps.has("ndc")) {
            val r1 = ShellExecutor.runRoot("ndc resolver setnetdns $iface \"\" $dns1 $dns2")
            if (!r1.success) {
                ShellExecutor.runRoot("ndc resolver setifdns $iface \"\" $dns1 $dns2")
            } else {
                anySuccess = true
            }
            ShellExecutor.runRoot("ndc resolver setdefaultif $iface")
        }

        // Method 4: setprop
        if (caps.has("setprop")) {
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

        // Method 5: iptables DNAT
        if (caps.has("iptables")) {
            // Delete existing rules first (ignore errors)
            ShellExecutor.runRoot("iptables -t nat -D OUTPUT -p udp --dport 53 -j DNAT --to-destination $dns1:53")
            ShellExecutor.runRoot("iptables -t nat -D OUTPUT -p tcp --dport 53 -j DNAT --to-destination $dns1:53")
            // Add new rules
            val r1 = ShellExecutor.runRoot("iptables -t nat -A OUTPUT -p udp --dport 53 -j DNAT --to-destination $dns1:53")
            val r2 = ShellExecutor.runRoot("iptables -t nat -A OUTPUT -p tcp --dport 53 -j DNAT --to-destination $dns1:53")
            if (r1.success && r2.success) anySuccess = true
            else Logger.log(LogTag.FALLBACK, "iptables DNAT — exit ${r1.exitCode}/${r2.exitCode}")
        }

        // Method 6: content insert
        if (caps.has("content")) {
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
        if (caps.has("iptables")) {
            Logger.log(LogTag.INFO, "Cleaning up iptables DNAT rules...")
            ShellExecutor.runRoot("iptables -t nat -D OUTPUT -p udp --dport 53 -j DNAT --to-destination $dns1:53")
            ShellExecutor.runRoot("iptables -t nat -D OUTPUT -p tcp --dport 53 -j DNAT --to-destination $dns1:53")
        }
    }
}
