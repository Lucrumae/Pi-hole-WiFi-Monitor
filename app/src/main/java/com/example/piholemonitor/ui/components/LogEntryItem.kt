package com.example.piholemonitor.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.piholemonitor.domain.LogEntry
import com.example.piholemonitor.domain.LogTag
import com.example.piholemonitor.ui.theme.*
import com.example.piholemonitor.util.Logger

/**
 * Renders a single log entry with color-coded tag.
 */
@Composable
fun LogEntryItem(
    entry: LogEntry,
    modifier: Modifier = Modifier
) {
    val color = when (entry.tag) {
        LogTag.INFO -> MaterialTheme.colorScheme.onSurface
        LogTag.OK -> PiholeGreen
        LogTag.CMD -> PiholeGray
        LogTag.FALLBACK -> PiholeAmber
        LogTag.WARN -> PiholeAmber
        LogTag.ERROR -> PiholeRed
    }

    val weight = when (entry.tag) {
        LogTag.ERROR -> FontWeight.Bold
        LogTag.WARN -> FontWeight.SemiBold
        else -> FontWeight.Normal
    }

    val fontSize = when (entry.tag) {
        LogTag.CMD -> 11.sp
        else -> 13.sp
    }

    Text(
        text = "[${Logger.formatTime(entry.timestamp)}] [${entry.tag.name}] ${entry.message}",
        color = color,
        fontWeight = weight,
        fontSize = fontSize,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        lineHeight = 18.sp
    )
}
