package com.example.piholemonitor.service

import com.example.piholemonitor.data.AppConfig
import com.example.piholemonitor.domain.LogTag
import com.example.piholemonitor.util.Logger
import com.example.piholemonitor.util.ShellExecutor

/**
 * Handles USB and Ethernet tethering enable/disable.
 */
class TetheringManager(
    private val caps: CapabilityDetector
) {

    /**
     * Phase 7a: Enable Ethernet tethering.
     */
    fun tetherEthEnable(config: AppConfig): Boolean {
        if (!config.enableEthTether) return true

        Logger.log(LogTag.INFO, "Phase 7: Enabling Ethernet tethering...")

        val commands = mutableListOf<String>()
        if (caps.has("cmd")) commands.add("cmd tethering start-tethering 1")
        if (caps.has("cmd")) commands.add("cmd connectivity tether enable ethernet")
        if (caps.has("svc")) commands.add("svc ethernet setEnabled true")
        if (caps.has("settings")) commands.add("settings put global tether_offload_disabled 0")
        if (caps.has("ndc")) commands.add("ndc tether start")
        if (caps.has("setprop")) commands.add("setprop sys.usb.tethering 1")

        val result = ShellExecutor.tryFallbacks("tether_eth_enable", commands)
        return result != null
    }

    /**
     * Phase 7b: Enable USB tethering.
     */
    fun tetherUsbEnable(config: AppConfig): Boolean {
        if (!config.enableUsbTether) return true

        Logger.log(LogTag.INFO, "Phase 7: Enabling USB tethering...")

        val commands = mutableListOf<String>()
        if (caps.has("cmd")) commands.add("cmd tethering start-tethering 0")
        if (caps.has("cmd")) commands.add("cmd connectivity tether enable usb")
        if (caps.has("svc")) commands.add("svc usb setFunctions rndis")
        if (caps.has("svc")) commands.add("svc usb setFunctions ncm")
        if (caps.has("ndc")) commands.add("ndc tether start")

        val result = ShellExecutor.tryFallbacks("tether_usb_enable", commands)
        if (result != null) return true

        // Method 6 (last resort): setprop combo
        if (caps.has("setprop")) {
            val r1 = ShellExecutor.runRoot("setprop sys.usb.config \"rndis,adb\"")
            val r2 = ShellExecutor.runRoot("setprop sys.usb.tethering 1")
            if (r1.success && r2.success) {
                Logger.log(LogTag.OK, "USB tethering enabled via setprop")
                return true
            }
        }

        Logger.log(LogTag.ERROR, "tether_usb_enable: all methods failed")
        return false
    }
}
