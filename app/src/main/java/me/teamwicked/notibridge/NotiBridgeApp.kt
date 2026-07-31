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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
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
