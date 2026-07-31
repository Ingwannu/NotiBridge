package me.teamwicked.notibridge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.teamwicked.notibridge.service.KeepAliveService
import me.teamwicked.notibridge.work.DeliveryDispatcher

/**
 * Restores forwarding after reboot and app updates.
 *
 * Two things need reviving: the keep-alive foreground service (which nudges
 * the notification listener binding) and any queued deliveries that were
 * interrupted by the shutdown.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            -> {
                KeepAliveService.start(context)
                DeliveryDispatcher.kick(context)
            }
        }
    }
}
