package me.teamwicked.notibridge

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import me.teamwicked.notibridge.data.AppDatabase
import me.teamwicked.notibridge.data.DeliveryTaskRepository
import me.teamwicked.notibridge.data.GlobalVariableRepository
import me.teamwicked.notibridge.data.HookRepository
import me.teamwicked.notibridge.data.LogRepository
import me.teamwicked.notibridge.data.SettingsRepository
import me.teamwicked.notibridge.net.WebhookSender

/**
 * Dependency holder. A full DI framework is overkill for a single-module app,
 * so repositories are constructed once here and read from services/workers.
 */
class NotiBridgeApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val hookRepository: HookRepository by lazy { HookRepository(database) }
    val deliveryTaskRepository: DeliveryTaskRepository by lazy { DeliveryTaskRepository(database) }
    val logRepository: LogRepository by lazy { LogRepository(database) }
    val globalVariableRepository: GlobalVariableRepository by lazy { GlobalVariableRepository(database) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    val webhookSender: WebhookSender by lazy {
        WebhookSender(
            globalsProvider = { globalVariableRepository.snapshot() },
            globalsPublisher = { values -> globalVariableRepository.putAll(values) },
        )
    }

    /** Posts a user-visible result notification honoring the settings toggles. */
    fun resultNotification(
        hook: me.teamwicked.notibridge.model.Hook,
        payload: me.teamwicked.notibridge.model.NotificationPayload,
        success: Boolean,
        responseCode: Int?,
        error: String,
    ) {
        me.teamwicked.notibridge.service.ResultNotifier.notifyResult(
            context = this,
            hook = hook,
            payload = payload,
            success = success,
            responseCode = responseCode,
            errorMessage = error,
        )
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        installCrashLogger()
    }

    /**
     * Writes uncaught exceptions to a file before dying. The file survives the
     * crash, so the next launch (or `adb pull`) can reveal the real stack
     * instead of guessing from "app closes" reports.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val file = java.io.File(filesDir, "last-crash.txt")
                file.writeText(
                    buildString {
                        appendLine("thread=${thread.name}")
                        appendLine("time=${System.currentTimeMillis()}")
                        appendLine(android.util.Log.getStackTraceString(throwable))
                    },
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                getString(R.string.notification_channel_service),
                NotificationManager.IMPORTANCE_MIN,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT,
                getString(R.string.notification_channel_result),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    companion object {
        const val CHANNEL_SERVICE = "service"
        const val CHANNEL_RESULT = "result"
        const val SERVICE_NOTIFICATION_ID = 1001
    }
}
