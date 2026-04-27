package com.example.piholemonitor.domain

/**
 * Represents the result of a shell command execution via libsu.
 */
data class ShellResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val success: Boolean
)
