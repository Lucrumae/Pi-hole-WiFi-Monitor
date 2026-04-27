package com.example.piholemonitor.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.piholemonitor.domain.ServiceState
import com.example.piholemonitor.domain.LogEntry
import com.example.piholemonitor.ui.components.LogEntryItem
import com.example.piholemonitor.ui.components.StatusCard
import com.example.piholemonitor.ui.theme.PiholeGreen
import com.example.piholemonitor.ui.theme.PiholeRed
import com.example.piholemonitor.util.Logger
import java.util.Locale

/**
 * Dashboard screen — service control, status cards, and recent log entries.
 */
@Composable
fun DashboardScreen(
    serviceState: ServiceState,
    logEntries: List<LogEntry>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onLogTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Service Control Card
        item {
            ServiceControlCard(
                serviceState = serviceState,
                onStart = onStart,
                onStop = onStop
            )
        }

        // Status Cards Grid
        item {
            StatusCardsGrid(serviceState = serviceState)
        }

        // Recent Log Preview
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onLogTap() },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Recent Logs",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val recentEntries = logEntries.takeLast(5)
                    if (recentEntries.isEmpty()) {
                        Text(
                            text = "No log entries yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        recentEntries.forEach { entry ->
                            LogEntryItem(entry = entry)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap to view full log →",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun ServiceControlCard(
    serviceState: ServiceState,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Pi-hole Monitor",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = serviceState.currentPhase,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (serviceState.isRunning) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Uptime: ${formatUptime(serviceState.uptimeMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { if (serviceState.isRunning) onStop() else onStart() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (serviceState.isRunning) PiholeRed else PiholeGreen
                )
            ) {
                Icon(
                    imageVector = if (serviceState.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (serviceState.isRunning) "STOP" else "START",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun StatusCardsGrid(serviceState: ServiceState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCard(
                title = "WiFi",
                isOk = serviceState.wifiConnected,
                statusText = if (serviceState.wifiConnected) "Connected" else "Disconnected",
                detail1 = if (serviceState.currentIp.isNotEmpty()) "IP: ${serviceState.currentIp}" else "",
                modifier = Modifier.weight(1f)
            )
            StatusCard(
                title = "Pi-hole",
                isOk = serviceState.piholeUp,
                statusText = if (serviceState.piholeUp) "UP" else "DOWN",
                detail1 = if (serviceState.piholeUp) "Responding" else "Not reachable",
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCard(
                title = "Custom IP",
                isOk = serviceState.currentIp.isNotEmpty(),
                statusText = if (serviceState.currentIp.isNotEmpty()) "Present" else "Not set",
                detail1 = serviceState.currentIp,
                modifier = Modifier.weight(1f)
            )
            StatusCard(
                title = "Reconnects",
                isOk = serviceState.failCount == 0,
                statusText = "Fail count: ${serviceState.failCount}/5",
                detail1 = serviceState.lastReconnectTime?.let {
                    "Last: ${Logger.formatTime(it)}"
                } ?: "",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun formatUptime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}
