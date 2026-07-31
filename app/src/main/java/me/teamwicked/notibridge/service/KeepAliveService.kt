package me.teamwicked.notibridge.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import me.teamwicked.notibridge.NotiBridgeApp
import me.teamwicked.notibridge.R
import me.teamwicked.notibridge.ui.MainActivity

/**
 * Foreground service whose only job is to keep the process warm so the
 * NotificationListener binding survives aggressive OEM task killers, and to
 * give the user a visible on/off switch plus status entry point.
 *
 * It deliberately does NOT hold a wake lock or do network work; delivery
 * itself is owned by the WorkManager dispatcher.
 */
class KeepAliveService : Service() {

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val reposter = object : Runnable {
        override fun run() {
            // Re-post the foreground notification periodically. If the user
            // swipes it away, the next tick puts it back, exactly like
            // notifyhook's resilient foreground notification.
            startForegroundCompat()
            handler.postDelayed(this, REPOST_INTERVAL_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // POST_NOTIFICATIONS denied on Android 13+ makes startForeground throw;
        // fall back to a normal sticky service instead of crashing the app.
        runCatching { startForegroundCompat() }.onFailure {
            android.util.Log.w(TAG, "startForeground failed, running as background service", it)
        }
        handler.removeCallbacks(reposter)
        handler.postDelayed(reposter, REPOST_INTERVAL_MS)
        // If the listener lost its binding (OEM kill, permission revoke),
        // requestRebind nudges the system to restore it.
        runCatching {
            NotificationHookService::class.java.let {
                requestListenerRebind(ComponentName(this, it))
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, NotiBridgeApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Fires when the user swipes the notification away; the receiver
            // re-posts it instantly, so it behaves as non-dismissible.
            .setDeleteIntent(
                me.teamwicked.notibridge.receiver.ServiceNotificationDismissedReceiver
                    .deleteIntent(this),
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotiBridgeApp.SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NotiBridgeApp.SERVICE_NOTIFICATION_ID, notification)
        }
    }

    private fun requestListenerRebind(componentName: ComponentName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.service.notification.NotificationListenerService
                .requestRebind(componentName)
        }
    }

    companion object {
        private const val TAG = "KeepAliveService"
        private const val REPOST_INTERVAL_MS = 5_000L

        /**
         * Re-posts the foreground notification by bouncing the service. Cheap
         * enough for a dismiss-triggered immediate refresh.
         */
        fun repostNotification(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, KeepAliveService::class.java))
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            // From a boot receiver, FGS launch restrictions (Android 12+)
            // can throw; retry as a plain service so the app never crashes
            // during boot-time recovery.
            runCatching { context.startForegroundService(intent) }
                .recoverCatching { context.startService(intent) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }

        fun isRunning(context: Context): Boolean {
            // ActivityManager.getRunningServices is deprecated; track state in
            // shared prefs written by the service itself instead.
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_RUNNING, false)
        }

        internal fun markRunning(context: Context, running: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_RUNNING, running)
                .apply()
        }

        private const val PREFS = "notibridge_service_state"
        private const val KEY_RUNNING = "keep_alive_running"
    }

    override fun onCreate() {
        super.onCreate()
        markRunning(this, true)
        me.teamwicked.notibridge.receiver.WatchdogReceiver.schedule(this)
    }

    override fun onDestroy() {
        handler.removeCallbacks(reposter)
        markRunning(this, false)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User swiped the app away: make sure the watchdog survives so the
        // listener + queue recover even if the system kills the process.
        me.teamwicked.notibridge.receiver.WatchdogReceiver.schedule(this)
        super.onTaskRemoved(rootIntent)
    }
}
