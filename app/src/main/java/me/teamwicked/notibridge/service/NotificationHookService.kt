package me.teamwicked.notibridge.service

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.teamwicked.notibridge.NotiBridgeApp
import me.teamwicked.notibridge.model.NotificationPayload
import me.teamwicked.notibridge.work.DeliveryDispatcher

/**
 * Core listener: receives every posted notification, filters it, and enqueues
 * delivery tasks for every matching enabled hook.
 *
 * Deliberate design decisions:
 *  - The listener never does network I/O; it only snapshots the notification
 *    and writes to the durable queue, so slow webhooks can never block or
 *    drop later notifications.
 *  - Ongoing notifications (music players, progress, VPN...) are skipped
 *    because they are persistent state, not events worth forwarding.
 *  - Our own notifications are skipped to avoid feedback loops where a
 *    "webhook failed" notification would trigger another webhook.
 */
class NotificationHookService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        KeepAliveService.start(this)
        // A freshly (re)bound listener can mean the process was killed;
        // drain anything left in the queue from the previous run.
        DeliveryDispatcher.kick(this)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // Ask the system to rebind; the keep-alive service also retries.
        requestRebind(android.content.ComponentName(this, NotificationHookService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val payload = snapshot(sbn) ?: return
        val app = application as NotiBridgeApp
        scope.launch {
            val hooks = app.hookRepository.listEnabledHooks()
            var enqueued = false
            hooks.forEach { hook ->
                if (!matches(hook, payload)) return@forEach
                // Check + insert run in one transaction so two notifications
                // arriving back-to-back cannot both pass the dedupe check.
                val inserted = app.deliveryTaskRepository.enqueueIfNotDuplicate(
                    hook = hook,
                    payload = payload,
                    windowMs = app.settingsRepository.dedupeWindowMs,
                )
                if (inserted) enqueued = true
            }
            if (enqueued) {
                DeliveryDispatcher.kick(this@NotificationHookService)
            }
        }
    }

    private fun matches(hook: me.teamwicked.notibridge.model.Hook, payload: NotificationPayload): Boolean {
        if (hook.appPackages.isNotEmpty() && payload.appPackage !in hook.appPackages) return false
        if (hook.excludeFilters.any { it.matches(payload) }) return false
        return true
    }

    private fun snapshot(sbn: StatusBarNotification): NotificationPayload? {
        if (sbn.packageName == packageName) return null // never forward our own
        val notification = sbn.notification ?: return null
        if (sbn.isOngoing || (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return null

        val extras = notification.extras
        fun charSequence(key: String): String =
            extras.getCharSequence(key)?.toString().orEmpty()

        val title = charSequence(Notification.EXTRA_TITLE)
        val text = charSequence(Notification.EXTRA_TEXT)
        val postedAt = if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis()

        return NotificationPayload(
            appPackage = sbn.packageName.orEmpty(),
            appName = resolveAppName(sbn.packageName),
            title = title,
            text = text,
            subText = charSequence(Notification.EXTRA_SUB_TEXT),
            bigText = charSequence(Notification.EXTRA_BIG_TEXT).ifBlank { text },
            summaryText = charSequence(Notification.EXTRA_SUMMARY_TEXT),
            tickerText = notification.tickerText?.toString().orEmpty(),
            timestampMillis = postedAt,
            dedupeKey = NotificationPayload.buildDedupeKey(sbn.packageName.orEmpty(), title, text, postedAt),
        )
    }

    private fun resolveAppName(packageName: String?): String {
        if (packageName.isNullOrBlank()) return ""
        return try {
            val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            packageManager.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
