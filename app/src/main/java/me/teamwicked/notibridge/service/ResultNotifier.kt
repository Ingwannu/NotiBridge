package me.teamwicked.notibridge.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import me.teamwicked.notibridge.NotiBridgeApp
import me.teamwicked.notibridge.R
import me.teamwicked.notibridge.model.Hook
import me.teamwicked.notibridge.model.NotificationPayload
import me.teamwicked.notibridge.ui.MainActivity

/**
 * Shows a per-delivery result notification, like notifyhook's log channel.
 *
 * - Fires only when the user enabled it in Settings (default ON for failures,
 *   OFF for successes, so success spam doesn't bury the shade).
 * - These are normal dismissible notifications; the "keep-alive" foreground
 *   notification is the one that must stay pinned, and it lives in a
 *   separate channel with IMPORTANCE_MIN.
 */
object ResultNotifier {

    private const val PREFS = "notibridge_notify_settings"
    private const val KEY_NOTIFY_SUCCESS = "notify_success"
    private const val KEY_NOTIFY_FAILURE = "notify_failure"

    fun notifySuccessEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_NOTIFY_SUCCESS, false)

    fun notifyFailureEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_NOTIFY_FAILURE, true)

    fun setNotifySuccess(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NOTIFY_SUCCESS, enabled).apply()
    }

    fun setNotifyFailure(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NOTIFY_FAILURE, enabled).apply()
    }

    fun notifyResult(
        context: Context,
        hook: Hook,
        payload: NotificationPayload,
        success: Boolean,
        responseCode: Int?,
        errorMessage: String,
    ) {
        val enabled = if (success) notifySuccessEnabled(context) else notifyFailureEnabled(context)
        if (!enabled) return
        if (!canPostNotifications(context)) return

        val title = if (success) {
            "훅 전송 성공: ${hook.name}"
        } else {
            "훅 전송 실패: ${hook.name}"
        }
        val detail = buildString {
            append("${payload.appName}: ${payload.title}")
            if (responseCode != null) append(" → HTTP $responseCode")
            if (!success && errorMessage.isNotBlank()) append(" · $errorMessage")
        }

        val openApp = PendingIntent.getActivity(
            context,
            hook.id.hashCode(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, NotiBridgeApp.CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openApp)
            // Auto-cancel on tap; stays in the shade until dismissed otherwise,
            // which is the "지우기 전까지 남아있는" behavior requested.
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // One id per hook keeps the shade tidy: a newer result replaces the old.
        manager.notify(hook.id.hashCode(), notification)
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
}
