package me.teamwicked.notibridge.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.teamwicked.notibridge.NotiBridgeApp
import me.teamwicked.notibridge.data.DeliveryTaskEntity
import me.teamwicked.notibridge.data.DeliveryTaskRepository
import me.teamwicked.notibridge.data.LogRepository
import me.teamwicked.notibridge.net.WebhookSender

/**
 * WorkManager-backed dispatcher that drains the durable delivery queue.
 *
 * Why WorkManager instead of a plain coroutine in the listener service:
 * retries must survive process death, reboots and app updates, and WorkManager
 * gives us exact-enough delayed re-execution without holding the process alive.
 * The queue itself lives in Room, so a killed worker never loses tasks.
 */
class DeliveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        /** Safety bound so a constantly-refilling queue cannot pin one worker. */
        private const val MAX_BATCHES_PER_RUN = 10
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as NotiBridgeApp
        val taskRepo = app.deliveryTaskRepository
        val sender = app.webhookSender
        val logRepo = app.logRepository
        val maxConcurrent = app.settingsRepository.maxConcurrentDeliveries

        // A previous worker may have died mid-flight; rescue its tasks.
        taskRepo.requeueStaleRunning()

        // Drain the queue in batches: more notifications may arrive while we
        // are sending, and claimDue only returns up to maxConcurrent at once.
        var batches = 0
        while (batches < MAX_BATCHES_PER_RUN) {
            batches++
            val tasks = taskRepo.claimDue(maxConcurrent)
            if (tasks.isEmpty()) break

            val semaphore = Semaphore(maxConcurrent)
            coroutineScope {
                tasks.map { task ->
                    async {
                        semaphore.withPermit {
                            deliverOne(taskRepo, sender, logRepo, task)
                        }
                    }
                }.awaitAll()
            }
        }

        DeliveryDispatcher.scheduleNext(applicationContext)
        Result.success()
    }

    private suspend fun deliverOne(
        taskRepo: DeliveryTaskRepository,
        sender: WebhookSender,
        logRepo: LogRepository,
        task: DeliveryTaskEntity,
    ) {
        val hook = taskRepo.decodeHook(task)
        val payload = taskRepo.decodePayload(task)
        // The hook may have been deleted while the task sat in the queue;
        // sending for a deleted hook would be surprising (and its log row
        // would point at nothing), so skip and drop the task.
        val app = applicationContext as NotiBridgeApp
        if (app.hookRepository.findHook(task.hookId) == null) {
            taskRepo.complete(task)
            return
        }
        val startedAt = System.currentTimeMillis()

        val outcome = sender.send(hook, payload)

        if (outcome.success) {
            taskRepo.complete(task)
            logRepo.append(
                hook = hook,
                payload = payload,
                success = true,
                responseCode = outcome.responseCode,
                responseBody = outcome.responseBody,
                errorMessage = "",
                requestBodyPreview = outcome.requestBodyPreview,
                durationMillis = System.currentTimeMillis() - startedAt,
                dedupeKey = task.dedupeKey,
            )
            app.resultNotification(hook, payload, success = true, responseCode = outcome.responseCode, error = "")
            return
        }

        val nextAttempt = task.attemptCount + 1
        if (nextAttempt >= task.maxAttempts) {
            taskRepo.complete(task)
            logRepo.append(
                hook = hook,
                payload = payload,
                success = false,
                responseCode = outcome.responseCode,
                responseBody = outcome.responseBody,
                errorMessage = outcome.errorMessage.ifBlank { "최대 재시도 횟수 초과" },
                requestBodyPreview = outcome.requestBodyPreview,
                durationMillis = System.currentTimeMillis() - startedAt,
                dedupeKey = task.dedupeKey,
            )
            app.resultNotification(
                hook,
                payload,
                success = false,
                responseCode = outcome.responseCode,
                error = outcome.errorMessage,
            )
        } else {
            val delay = DeliveryTaskRepository.backoffFor(task.attemptCount)
            taskRepo.markRetry(
                task = task,
                error = outcome.errorMessage.ifBlank { "HTTP ${outcome.responseCode}" },
                nextAt = System.currentTimeMillis() + delay,
            )
        }
    }
}

/** Schedules [DeliveryWorker]: immediately when work exists, delayed for the next retry. */
object DeliveryDispatcher {

    private const val WORK_NAME = "notibridge-delivery"
    private const val RETRY_WORK_NAME = "notibridge-delivery-retry"

    /**
     * Run due work now. Uses APPEND_OR_REPLACE so a kick that arrives while a
     * worker is draining the queue is not silently dropped (REPLACE would
     * cancel the in-flight worker; KEEP would drop the new work entirely).
     */
    fun kick(context: Context) {
        val request = OneTimeWorkRequestBuilder<DeliveryWorker>()
            .setConstraints(networkConstraints())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Rearms the worker for the earliest pending retry, if any. */
    suspend fun scheduleNext(context: Context) {
        val app = context.applicationContext as NotiBridgeApp
        val active = app.deliveryTaskRepository.activeCount()
        if (active == 0) return
        val nextAt = app.deliveryTaskRepository.nextRetryAt()
            ?: System.currentTimeMillis()
        val delay = (nextAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<DeliveryWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(networkConstraints())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(RETRY_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private fun networkConstraints(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}
