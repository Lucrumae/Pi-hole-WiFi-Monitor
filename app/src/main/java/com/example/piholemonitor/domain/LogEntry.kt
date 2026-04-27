package com.example.piholemonitor.domain

/**
 * A single log entry with timestamp, severity tag, and message.
 */
data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val tag: LogTag,
    val message: String
)

/**
 * Log severity / category tags.
 */
enum class LogTag {
    INFO,
    OK,
    CMD,
    FALLBACK,
    WARN,
    ERROR
}
