package com.example.piholemonitor.util

import com.example.piholemonitor.domain.LogTag
import com.example.piholemonitor.domain.ShellResult
import com.topjohnwu.superuser.Shell

/**
 * Wrapper around libsu for executing root shell commands.
 * Logs every command and its result through the Logger.
 */
object ShellExecutor {

    /**
     * Execute a single root command, logging it before and after.
     */
    fun runRoot(cmd: String): ShellResult {
        Logger.log(LogTag.CMD, cmd)
        val result = Shell.cmd(cmd).exec()
        val shellResult = ShellResult(
            command = cmd,
            exitCode = result.code,
            stdout = result.out.joinToString("\n"),
            stderr = result.err.joinToString("\n"),
            success = result.isSuccess
        )
        if (shellResult.success) {
            Logger.log(LogTag.OK, "${extractCmdName(cmd)} — exit 0")
        }
        return shellResult
    }

    /**
     * Execute a command, returning true if exit code is 0.
     */
    fun runRootBool(cmd: String): Boolean {
        return runRoot(cmd).success
    }

    /**
     * Execute a command and return stdout trimmed.
     */
    fun runRootOutput(cmd: String): String {
        return runRoot(cmd).stdout.trim()
    }

    /**
     * Try a list of commands in order. Stop at first success.
     * Log FALLBACK for each failure, ERROR if all fail.
     * Returns the first successful ShellResult, or null if all failed.
     */
    fun tryFallbacks(operationName: String, commands: List<String>): ShellResult? {
        for ((index, cmd) in commands.withIndex()) {
            val result = runRoot(cmd)
            if (result.success) {
                return result
            }
            if (index < commands.size - 1) {
                val stderrSnippet = if (result.stderr.isNotEmpty()) " — ${result.stderr.take(100)}" else ""
                Logger.log(LogTag.FALLBACK, "${extractCmdName(cmd)} — exit ${result.exitCode}$stderrSnippet, trying next method...")
            }
        }
        Logger.log(LogTag.ERROR, "$operationName: all ${commands.size} methods failed")
        return null
    }

    /**
     * Try a list of commands and run ALL regardless of success (for set_dns).
     * Returns true if at least one succeeded.
     */
    fun tryAll(operationName: String, commands: List<String>): Boolean {
        var anySuccess = false
        for (cmd in commands) {
            val result = runRoot(cmd)
            if (result.success) {
                anySuccess = true
            } else {
                val stderrSnippet = if (result.stderr.isNotEmpty()) " — ${result.stderr.take(100)}" else ""
                Logger.log(LogTag.FALLBACK, "${extractCmdName(cmd)} — exit ${result.exitCode}$stderrSnippet")
            }
        }
        if (!anySuccess) {
            Logger.log(LogTag.ERROR, "$operationName: all ${commands.size} methods failed")
        }
        return anySuccess
    }

    /**
     * Check if a command's output contains a specific string (case-insensitive).
     */
    fun outputContains(cmd: String, vararg searches: String): Boolean {
        val result = runRoot(cmd)
        if (!result.success) return false
        val output = result.stdout.lowercase()
        return searches.all { output.contains(it.lowercase()) }
    }

    /**
     * Check if root is available.
     */
    fun isRootAvailable(): Boolean {
        return try {
            val shell = Shell.getShell()
            shell.isRoot
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Extract short command name for logging (first two words).
     */
    private fun extractCmdName(cmd: String): String {
        val parts = cmd.trim().split("\\s+".toRegex())
        return parts.take(3).joinToString(" ")
    }
}
