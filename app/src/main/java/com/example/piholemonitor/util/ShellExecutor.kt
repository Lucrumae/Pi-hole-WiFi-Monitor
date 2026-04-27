package com.example.piholemonitor.util

import com.example.piholemonitor.domain.LogTag
import com.example.piholemonitor.domain.ShellResult
import com.topjohnwu.superuser.Shell
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Wrapper around libsu for executing root shell commands.
 * All commands have a 5-second timeout — if a command hangs,
 * it returns failure immediately so the fallback chain continues.
 */
object ShellExecutor {

    /** Timeout for each individual shell command (milliseconds). */
    private const val COMMAND_TIMEOUT_MS = 5000L

    /**
     * Execute a single root command with timeout, logging it before and after.
     * If the command hangs beyond 5 seconds, it returns failure automatically.
     */
    fun runRoot(cmd: String): ShellResult {
        Logger.log(LogTag.CMD, cmd)
        val shellResult = executeWithTimeout(cmd)
        if (shellResult.success) {
            Logger.log(LogTag.OK, "${extractCmdName(cmd)} — exit 0")
        }
        return shellResult
    }

    /**
     * Execute a root command quietly with timeout — NO log entries.
     * Used for status/check commands that run frequently
     * (wifiIsConnected, wifiHasIp, hasCustomIp, checkPort)
     * to avoid flooding the log.
     */
    fun runRootQuiet(cmd: String): ShellResult {
        return executeWithTimeout(cmd)
    }

    /**
     * Execute a command with timeout, returning true if exit code is 0.
     */
    fun runRootBool(cmd: String): Boolean {
        return runRoot(cmd).success
    }

    /**
     * Execute a command with timeout and return stdout trimmed.
     */
    fun runRootOutput(cmd: String): String {
        return runRoot(cmd).stdout.trim()
    }

    /**
     * Execute a command quietly with timeout and return stdout trimmed (no logging).
     */
    fun runRootOutputQuiet(cmd: String): String {
        return runRootQuiet(cmd).stdout.trim()
    }

    /**
     * Core execution: run a shell command with a 5-second timeout.
     * Uses libsu's async submit() + CountDownLatch so the shell session
     * stays healthy even if we stop waiting.
     */
    private fun executeWithTimeout(cmd: String): ShellResult {
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()
        val latch = CountDownLatch(1)
        var exitCode = -1

        try {
            Shell.cmd(cmd).to(stdout, stderr).submit { result ->
                exitCode = result.code
                latch.countDown()
            }

            val completed = latch.await(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)

            if (!completed) {
                return ShellResult(
                    command = cmd,
                    exitCode = -1,
                    stdout = "",
                    stderr = "TIMEOUT after ${COMMAND_TIMEOUT_MS}ms",
                    success = false
                )
            }

            return ShellResult(
                command = cmd,
                exitCode = exitCode,
                stdout = stdout.joinToString("\n"),
                stderr = stderr.joinToString("\n"),
                success = exitCode == 0
            )
        } catch (e: Exception) {
            return ShellResult(
                command = cmd,
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "ERROR",
                success = false
            )
        }
    }

    /**
     * Try a list of commands in order. Stop at first success.
     * Log FALLBACK for each failure (including timeout), ERROR if all fail.
     * Returns the first successful ShellResult, or null if all failed.
     */
    fun tryFallbacks(operationName: String, commands: List<String>): ShellResult? {
        if (commands.isEmpty()) {
            Logger.log(LogTag.ERROR, "$operationName: no commands to try")
            return null
        }
        for ((index, cmd) in commands.withIndex()) {
            val result = runRoot(cmd)
            if (result.success) {
                return result
            }
            val isTimeout = result.stderr.startsWith("TIMEOUT")
            val detail = if (isTimeout) "TIMEOUT" else "exit ${result.exitCode}"
            val stderrSnippet = if (!isTimeout && result.stderr.isNotEmpty()) " — ${result.stderr.take(100)}" else ""

            if (index < commands.size - 1) {
                Logger.log(LogTag.FALLBACK, "${extractCmdName(cmd)} — $detail$stderrSnippet, trying next...")
            } else {
                Logger.log(LogTag.FALLBACK, "${extractCmdName(cmd)} — $detail$stderrSnippet")
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
                val isTimeout = result.stderr.startsWith("TIMEOUT")
                val detail = if (isTimeout) "TIMEOUT" else "exit ${result.exitCode}"
                val stderrSnippet = if (!isTimeout && result.stderr.isNotEmpty()) " — ${result.stderr.take(100)}" else ""
                Logger.log(LogTag.FALLBACK, "${extractCmdName(cmd)} — $detail$stderrSnippet")
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
     * Extract short command name for logging (first three words).
     */
    private fun extractCmdName(cmd: String): String {
        val parts = cmd.trim().split("\\s+".toRegex())
        return parts.take(3).joinToString(" ")
    }
}
