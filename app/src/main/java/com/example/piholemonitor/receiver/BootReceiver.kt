package com.example.piholemonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.piholemonitor.data.SettingsRepository
import com.example.piholemonitor.service.PiholeMonitorService
import kotlinx.coroutines.runBlocking

/**
 * Receives BOOT_COMPLETED and LOCKED_BOOT_COMPLETED intents.
 * Starts the monitoring service if auto-start is enabled.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            val repo = SettingsRepository(context.applicationContext)
            val autoStart = runBlocking { repo.getAutoStartOnBoot() }

            if (autoStart) {
                val serviceIntent = Intent(context, PiholeMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
