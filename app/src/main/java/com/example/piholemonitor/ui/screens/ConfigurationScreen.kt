package com.example.piholemonitor.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.piholemonitor.data.AppConfig

/**
 * Configuration screen — all user-configurable settings organized in sections.
 */
@Composable
fun ConfigurationScreen(
    config: AppConfig,
    onConfigChange: (AppConfig) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onTestConnection: (String, String) -> Unit,
    isTesting: Boolean,
    modifier: Modifier = Modifier
) {
    var passwordVisible by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showTooltip by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── WiFi Settings ──
        SectionHeader("WiFi Settings")

        ConfigTextField(
            label = "WiFi SSID",
            value = config.wifiSsid,
            placeholder = AppConfig.DEFAULT.wifiSsid,
            onValueChange = { onConfigChange(config.copy(wifiSsid = it)) }
        )

        OutlinedTextField(
            value = config.wifiPass,
            onValueChange = { onConfigChange(config.copy(wifiPass = it)) },
            label = { Text("WiFi Password") },
            placeholder = { Text(AppConfig.DEFAULT.wifiPass) },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password"
                    )
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        ConfigTextField(
            label = "WiFi Interface",
            value = config.wifiIface,
            placeholder = AppConfig.DEFAULT.wifiIface,
            onValueChange = { onConfigChange(config.copy(wifiIface = it)) }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // ── Network / IP Settings ──
        SectionHeader("Network / IP Settings")

        ConfigTextField(
            label = "Custom IP",
            value = config.customIp,
            placeholder = AppConfig.DEFAULT.customIp,
            onValueChange = { onConfigChange(config.copy(customIp = it)) },
            keyboardType = KeyboardType.Uri,
            isError = config.customIp.isNotEmpty() && !isValidIp(config.customIp)
        )

        ConfigTextField(
            label = "Prefix Length",
            value = config.customPrefix,
            placeholder = AppConfig.DEFAULT.customPrefix,
            onValueChange = { onConfigChange(config.copy(customPrefix = it)) },
            keyboardType = KeyboardType.Number
        )

        ConfigTextField(
            label = "Subnet Mask",
            value = config.customMask,
            placeholder = AppConfig.DEFAULT.customMask,
            onValueChange = { onConfigChange(config.copy(customMask = it)) },
            keyboardType = KeyboardType.Uri
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // ── Pi-hole Settings ──
        SectionHeader("Pi-hole Settings")

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = config.piholeIp,
                onValueChange = { onConfigChange(config.copy(piholeIp = it)) },
                label = { Text("Pi-hole IP") },
                placeholder = { Text(AppConfig.DEFAULT.piholeIp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = config.piholeIp.isNotEmpty() && !isValidIp(config.piholeIp),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showTooltip = !showTooltip }) {
                Icon(Icons.Default.Info, contentDescription = "Info")
            }
        }

        if (showTooltip) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    text = "Pi-hole for Android runs locally. Default 127.0.0.1:80",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        ConfigTextField(
            label = "Pi-hole Port",
            value = config.piholePort,
            placeholder = AppConfig.DEFAULT.piholePort,
            onValueChange = { onConfigChange(config.copy(piholePort = it)) },
            keyboardType = KeyboardType.Number,
            isError = config.piholePort.isNotEmpty() && !isValidPort(config.piholePort)
        )

        ConfigTextField(
            label = "DNS Primary",
            value = config.dnsPrimary,
            placeholder = AppConfig.DEFAULT.dnsPrimary,
            onValueChange = { onConfigChange(config.copy(dnsPrimary = it)) },
            keyboardType = KeyboardType.Uri,
            isError = config.dnsPrimary.isNotEmpty() && !isValidIp(config.dnsPrimary)
        )

        ConfigTextField(
            label = "DNS Secondary",
            value = config.dnsSecondary,
            placeholder = AppConfig.DEFAULT.dnsSecondary,
            onValueChange = { onConfigChange(config.copy(dnsSecondary = it)) },
            keyboardType = KeyboardType.Uri,
            isError = config.dnsSecondary.isNotEmpty() && !isValidIp(config.dnsSecondary)
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // ── Timing ──
        SectionHeader("Timing")

        ConfigTextField(
            label = "Monitor Interval (seconds)",
            value = config.monitorInterval.toString(),
            placeholder = AppConfig.DEFAULT.monitorInterval.toString(),
            onValueChange = {
                val v = it.toIntOrNull() ?: config.monitorInterval
                onConfigChange(config.copy(monitorInterval = v))
            },
            keyboardType = KeyboardType.Number
        )

        ConfigTextField(
            label = "Pi-hole Wait Timeout (seconds)",
            value = config.piholeWaitTimeout.toString(),
            placeholder = AppConfig.DEFAULT.piholeWaitTimeout.toString(),
            onValueChange = {
                val v = it.toIntOrNull() ?: config.piholeWaitTimeout
                onConfigChange(config.copy(piholeWaitTimeout = v))
            },
            keyboardType = KeyboardType.Number
        )

        ConfigTextField(
            label = "WiFi Connect Timeout (seconds)",
            value = config.wifiConnectTimeout.toString(),
            placeholder = AppConfig.DEFAULT.wifiConnectTimeout.toString(),
            onValueChange = {
                val v = it.toIntOrNull() ?: config.wifiConnectTimeout
                onConfigChange(config.copy(wifiConnectTimeout = v))
            },
            keyboardType = KeyboardType.Number
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // ── Features ──
        SectionHeader("Features")

        ToggleRow(
            label = "USB Tethering",
            checked = config.enableUsbTether,
            onCheckedChange = { onConfigChange(config.copy(enableUsbTether = it)) }
        )

        ToggleRow(
            label = "ETH Tethering",
            checked = config.enableEthTether,
            onCheckedChange = { onConfigChange(config.copy(enableEthTether = it)) }
        )

        ToggleRow(
            label = "Auto-start on boot",
            description = "Automatically start monitoring when the device boots",
            checked = config.autoStartOnBoot,
            onCheckedChange = { onConfigChange(config.copy(autoStartOnBoot = it)) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── Action Buttons ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f)
            ) {
                Text("Save Configuration")
            }
            OutlinedButton(
                onClick = { showResetDialog = true },
                modifier = Modifier.weight(1f)
            ) {
                Text("Reset to Defaults")
            }
        }

        // Test Connection Button
        OutlinedButton(
            onClick = { onTestConnection(config.piholeIp, config.piholePort) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isTesting
        ) {
            if (isTesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Testing...")
            } else {
                Text("▶ Test Pi-hole Connection")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    // Reset confirmation dialog
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Configuration") },
            text = { Text("Reset all settings to factory defaults?") },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    onReset()
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun ConfigTextField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        isError = isError,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun isValidIp(ip: String): Boolean {
    val parts = ip.split(".")
    if (parts.size != 4) return false
    return parts.all { part ->
        val num = part.toIntOrNull() ?: return false
        num in 0..255
    }
}

private fun isValidPort(port: String): Boolean {
    val num = port.toIntOrNull() ?: return false
    return num in 1..65535
}
