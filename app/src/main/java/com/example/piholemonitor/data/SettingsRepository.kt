package com.example.piholemonitor.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pihole_settings")

/**
 * Repository for reading/writing app configuration via Jetpack DataStore.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val WIFI_SSID = stringPreferencesKey("wifi_ssid")
        val WIFI_PASS = stringPreferencesKey("wifi_pass")
        val CUSTOM_IP = stringPreferencesKey("custom_ip")
        val CUSTOM_PREFIX = stringPreferencesKey("custom_prefix")
        val CUSTOM_MASK = stringPreferencesKey("custom_mask")
        val DNS_PRIMARY = stringPreferencesKey("dns_primary")
        val DNS_SECONDARY = stringPreferencesKey("dns_secondary")
        val PIHOLE_IP = stringPreferencesKey("pihole_ip")
        val PIHOLE_PORT = stringPreferencesKey("pihole_port")
        val WIFI_IFACE = stringPreferencesKey("wifi_iface")
        val ENABLE_ETH_TETHER = booleanPreferencesKey("enable_eth_tether")
        val ENABLE_USB_TETHER = booleanPreferencesKey("enable_usb_tether")
        val MONITOR_INTERVAL = intPreferencesKey("monitor_interval")
        val PIHOLE_WAIT_TIMEOUT = intPreferencesKey("pihole_wait_timeout")
        val WIFI_CONNECT_TIMEOUT = intPreferencesKey("wifi_connect_timeout")
        val AUTO_START_ON_BOOT = booleanPreferencesKey("auto_start_on_boot")
    }

    /**
     * Observe config changes as a Flow.
     */
    val configFlow: Flow<AppConfig> = context.dataStore.data.map { prefs ->
        AppConfig(
            wifiSsid = prefs[Keys.WIFI_SSID]?.ifEmpty { null } ?: AppConfig.DEFAULT.wifiSsid,
            wifiPass = prefs[Keys.WIFI_PASS]?.ifEmpty { null } ?: AppConfig.DEFAULT.wifiPass,
            customIp = prefs[Keys.CUSTOM_IP]?.ifEmpty { null } ?: AppConfig.DEFAULT.customIp,
            customPrefix = prefs[Keys.CUSTOM_PREFIX]?.ifEmpty { null } ?: AppConfig.DEFAULT.customPrefix,
            customMask = prefs[Keys.CUSTOM_MASK]?.ifEmpty { null } ?: AppConfig.DEFAULT.customMask,
            dnsPrimary = prefs[Keys.DNS_PRIMARY]?.ifEmpty { null } ?: AppConfig.DEFAULT.dnsPrimary,
            dnsSecondary = prefs[Keys.DNS_SECONDARY]?.ifEmpty { null } ?: AppConfig.DEFAULT.dnsSecondary,
            piholeIp = prefs[Keys.PIHOLE_IP]?.ifEmpty { null } ?: AppConfig.DEFAULT.piholeIp,
            piholePort = prefs[Keys.PIHOLE_PORT]?.ifEmpty { null } ?: AppConfig.DEFAULT.piholePort,
            wifiIface = prefs[Keys.WIFI_IFACE]?.ifEmpty { null } ?: AppConfig.DEFAULT.wifiIface,
            enableEthTether = prefs[Keys.ENABLE_ETH_TETHER] ?: AppConfig.DEFAULT.enableEthTether,
            enableUsbTether = prefs[Keys.ENABLE_USB_TETHER] ?: AppConfig.DEFAULT.enableUsbTether,
            monitorInterval = prefs[Keys.MONITOR_INTERVAL] ?: AppConfig.DEFAULT.monitorInterval,
            piholeWaitTimeout = prefs[Keys.PIHOLE_WAIT_TIMEOUT] ?: AppConfig.DEFAULT.piholeWaitTimeout,
            wifiConnectTimeout = prefs[Keys.WIFI_CONNECT_TIMEOUT] ?: AppConfig.DEFAULT.wifiConnectTimeout,
            autoStartOnBoot = prefs[Keys.AUTO_START_ON_BOOT] ?: AppConfig.DEFAULT.autoStartOnBoot
        )
    }

    /**
     * Read current config snapshot (blocking coroutine).
     */
    suspend fun getConfig(): AppConfig = configFlow.first()

    /**
     * Read only the auto-start-on-boot flag.
     */
    suspend fun getAutoStartOnBoot(): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[Keys.AUTO_START_ON_BOOT] ?: false
    }

    /**
     * Save all settings to DataStore.
     */
    suspend fun saveConfig(config: AppConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.WIFI_SSID] = config.wifiSsid
            prefs[Keys.WIFI_PASS] = config.wifiPass
            prefs[Keys.CUSTOM_IP] = config.customIp
            prefs[Keys.CUSTOM_PREFIX] = config.customPrefix
            prefs[Keys.CUSTOM_MASK] = config.customMask
            prefs[Keys.DNS_PRIMARY] = config.dnsPrimary
            prefs[Keys.DNS_SECONDARY] = config.dnsSecondary
            prefs[Keys.PIHOLE_IP] = config.piholeIp
            prefs[Keys.PIHOLE_PORT] = config.piholePort
            prefs[Keys.WIFI_IFACE] = config.wifiIface
            prefs[Keys.ENABLE_ETH_TETHER] = config.enableEthTether
            prefs[Keys.ENABLE_USB_TETHER] = config.enableUsbTether
            prefs[Keys.MONITOR_INTERVAL] = config.monitorInterval
            prefs[Keys.PIHOLE_WAIT_TIMEOUT] = config.piholeWaitTimeout
            prefs[Keys.WIFI_CONNECT_TIMEOUT] = config.wifiConnectTimeout
            prefs[Keys.AUTO_START_ON_BOOT] = config.autoStartOnBoot
        }
    }

    /**
     * Reset all settings to defaults.
     */
    suspend fun resetToDefaults() {
        saveConfig(AppConfig.DEFAULT)
    }
}
