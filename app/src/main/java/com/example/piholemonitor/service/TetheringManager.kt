package com.example.piholemonitor.service

import com.example.piholemonitor.data.AppConfig
import com.example.piholemonitor.domain.LogTag
import com.example.piholemonitor.util.Logger
import com.example.piholemonitor.util.ShellExecutor

/**
 * Handles USB and Ethernet tethering enable/disable.
 * Commands are tried unconditionally from newest to oldest Android version.
 */
class TetheringManager {

    /**
     * Phase 7a: Enable Ethernet tethering.
     */
    fun tetherEthEnable(config: AppConfig): Boolean {
        if (!config.enableEthTether) return true

        Logger.log(LogTag.INFO, "Phase 7: Enabling Ethernet tethering...")

        val commands = listOf(
            "cmd tethering start-tethering 1",              // Android 11+
            "cmd connectivity tether enable ethernet",       // Android 10+
            "svc ethernet setEnabled true",                  // Android 7+
            "settings put global tether_offload_disabled 0", // Android 8+
            "ndc tether start",                              // Android 5-9
            "setprop sys.usb.tethering 1"                    // Legacy
        )

        val result = ShellExecutor.tryFallbacks("tether_eth_enable", commands)
        return result != null
    }

    /**
     * Phase 7b: Enable USB tethering.
     */
    fun tetherUsbEnable(config: AppConfig): Boolean {
        if (!config.enableUsbTether) return true

        Logger.log(LogTag.INFO, "Phase 7: Enabling USB tethering...")

        val commands = listOf(
            "cmd tethering start-tethering 0",        // Android 11+
            "cmd connectivity tether enable usb",      // Android 10+
            "svc usb setFunctions rndis",              // Android 7+ (rndis)
            "svc usb setFunctions ncm",                // Android 12+ (ncm)
            "ndc tether start"                         // Android 5-9
        )

        val result = ShellExecutor.tryFallbacks("tether_usb_enable", commands)
        if (result != null) return true

        // Method 6 (last resort): setprop combo
        val r1 = ShellExecutor.runRoot("setprop sys.usb.config \"rndis,adb\"")
        val r2 = ShellExecutor.runRoot("setprop sys.usb.tethering 1")
        if (r1.success && r2.success) {
            Logger.log(LogTag.OK, "USB tethering enabled via setprop")
            return true
        }

        Logger.log(LogTag.ERROR, "tether_usb_enable: all methods failed")
        return false
    }
}
