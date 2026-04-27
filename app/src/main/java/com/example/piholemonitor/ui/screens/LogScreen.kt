package com.example.piholemonitor.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.piholemonitor.domain.LogEntry
import com.example.piholemonitor.domain.LogTag
import com.example.piholemonitor.ui.components.LogEntryItem

/**
 * Log screen — filterable, scrollable log viewer with export/clear.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    logEntries: List<LogEntry>,
    onClear: () -> Unit,
    onExport: () -> String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedFilter by remember { mutableStateOf<LogTag?>(null) }
    val listState = rememberLazyListState()

    val filteredEntries = remember(logEntries, selectedFilter) {
        if (selectedFilter == null) logEntries
        else logEntries.filter { it.tag == selectedFilter }
    }

    // Auto-scroll to bottom on new entries
    LaunchedEffect(filteredEntries.size) {
        if (filteredEntries.isNotEmpty()) {
            listState.animateScrollToItem(filteredEntries.size - 1)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Top bar with actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Log (${filteredEntries.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear")
                }
                IconButton(onClick = {
                    val content = onExport()
                    shareText(context, content)
                }) {
                    Icon(Icons.Default.Share, contentDescription = "Export")
                }
            }
        }

        // Filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedFilter == null,
                onClick = { selectedFilter = null },
                label = { Text("ALL") }
            )
            LogTag.entries.forEach { tag ->
                FilterChip(
                    selected = selectedFilter == tag,
                    onClick = { selectedFilter = if (selectedFilter == tag) null else tag },
                    label = { Text(tag.name) }
                )
            }
        }

        // Log entries list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 4.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(filteredEntries, key = { "${it.timestamp}-${it.tag}-${it.message.hashCode()}" }) { entry ->
                LogEntryItem(entry = entry)
            }
        }
    }
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, "Pi-hole Monitor Log")
    }
    context.startActivity(Intent.createChooser(intent, "Export Log"))
}
