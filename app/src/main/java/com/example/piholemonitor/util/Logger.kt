package com.example.piholemonitor.util

import com.example.piholemonitor.domain.LogEntry
import com.example.piholemonitor.domain.LogTag
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * In-memory ring buffer logger with file output.
 * Maintains last 1000 entries for UI display.
 * Writes to /data/local/tmp/pihole_monitor.log via root shell.
 */
object Logger {

    private const val MAX_ENTRIES = 1000
    private const val LOG_FILE = "/data/local/tmp/pihole_monitor.log"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val buffer = ConcurrentLinkedDeque<LogEntry>()

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    /**
     * Log a message with the given tag.
     */
    fun log(tag: LogTag, message: String) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            tag = tag,
            message = message
        )

        // Add to ring buffer
        buffer.addLast(entry)
        while (buffer.size > MAX_ENTRIES) {
            buffer.pollFirst()
        }

        // Update StateFlow
        _entries.value = buffer.toList()

        // Format log line
        val timestamp = dateFormat.format(Date(entry.timestamp))
        val line = "[$timestamp] [${tag.name}] $message"

        // Write to file via root (fire and forget)
        try {
            Shell.cmd("echo '${line.replace("'", "\\'")}' >> $LOG_FILE").submit()
        } catch (_: Exception) {
            // If shell not ready, skip file write
        }

        // Also logcat
        android.util.Log.d("PiholeMonitor", line)
    }

    /**
     * Clear all log entries.
     */
    fun clear() {
        buffer.clear()
        _entries.value = emptyList()
        try {
            Shell.cmd("echo '' > $LOG_FILE").submit()
        } catch (_: Exception) {}
    }

    /**
     * Read the full log file content (for export).
     */
    fun readLogFile(): String {
        return try {
            val result = Shell.cmd("cat $LOG_FILE").exec()
            if (result.isSuccess) result.out.joinToString("\n") else ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Format a timestamp for display.
     */
    fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestamp))
    }

    /**
     * Format a full timestamp for display.
     */
    fun formatFullTime(timestamp: Long): String {
        return dateFormat.format(Date(timestamp))
    }
}
