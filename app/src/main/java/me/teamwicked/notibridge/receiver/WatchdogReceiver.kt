package me.teamwicked.notibridge.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import me.teamwicked.notibridge.service.KeepAliveService
import me.teamwicked.notibridge.work.DeliveryDispatcher

/**
 * Periodic watchdog, same idea as notifyhook's 15-minute self-heal: OEM task
 * killers sometimes remove the foreground service without anyone noticing.
 * A repeating inexact alarm restarts the keep-alive service and re-arms the
 * delivery queue so a killed app resumes forwarding within one interval.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        KeepAliveService.start(context)
        DeliveryDispatcher.kick(context)
        schedule(context) // inexact repeating alarms can be dropped by OEMs; re-arm every fire
    }

    companion object {
        private const val ACTION = "me.teamwicked.notibridge.WATCHDOG"
        private const val REQUEST_CODE = 4242
        private const val INTERVAL_MS = 15 * 60 * 1000L

        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            runCatching {
                alarmManager.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + INTERVAL_MS,
                    INTERVAL_MS,
                    pendingIntent(context),
                )
            }
        }

        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pendingIntent(context))
        }

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, WatchdogReceiver::class.java).apply { action = ACTION }
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
