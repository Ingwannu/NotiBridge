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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
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
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
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

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            runCatching { context.startForegroundService(intent) }
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
    }

    override fun onDestroy() {
        markRunning(this, false)
        super.onDestroy()
    }
}
