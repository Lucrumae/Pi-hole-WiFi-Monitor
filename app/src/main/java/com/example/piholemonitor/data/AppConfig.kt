package com.example.piholemonitor.data

/**
 * Data class holding all user-configurable settings.
 * Default values match the specification defaults.
 */
data class AppConfig(
    val wifiSsid: String = "YourSSID",
    val wifiPass: String = "YourPassword",
    val customIp: String = "192.168.1.100",
    val customPrefix: String = "24",
    val customMask: String = "255.255.255.0",
    val dnsPrimary: String = "127.0.0.1",
    val dnsSecondary: String = "127.0.0.1",
    val piholeIp: String = "127.0.0.1",
    val piholePort: String = "80",
    val wifiIface: String = "wlan0",
    val enableEthTether: Boolean = false,
    val enableUsbTether: Boolean = false,
    val monitorInterval: Int = 15,
    val piholeWaitTimeout: Int = 120,
    val wifiConnectTimeout: Int = 30,
    val autoStartOnBoot: Boolean = false
) {
    companion object {
        val DEFAULT = AppConfig()
    }
}
