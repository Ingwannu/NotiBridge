package me.teamwicked.notibridge.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.teamwicked.notibridge.service.KeepAliveService

/**
 * Fires the moment the user swipes away the foreground service notification.
 * We immediately re-post it, mirroring notifyhook's behavior where the
 * "running" notification effectively cannot be dismissed: it is the visible
 * proof that the background listener is alive.
 */
class ServiceNotificationDismissedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISMISSED) return
        KeepAliveService.repostNotification(context)
    }

    companion object {
        private const val ACTION_DISMISSED = "me.teamwicked.notibridge.SERVICE_NOTIFICATION_DISMISSED"
        private const val REQUEST_CODE = 7331

        fun deleteIntent(context: Context): PendingIntent {
            val intent = Intent(context, ServiceNotificationDismissedReceiver::class.java)
                .apply { action = ACTION_DISMISSED }
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
