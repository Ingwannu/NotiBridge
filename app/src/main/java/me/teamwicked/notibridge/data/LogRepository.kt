package me.teamwicked.notibridge.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import me.teamwicked.notibridge.model.DeliveryStatus
import me.teamwicked.notibridge.model.Hook
import me.teamwicked.notibridge.model.NotificationPayload

class LogRepository(private val db: AppDatabase) {

    fun observeRecentLogs(limit: Int = MAX_LOGS): Flow<List<SendLogEntity>> =
        db.sendLogDao().observeRecent(limit)

    suspend fun append(
        hook: Hook,
        payload: NotificationPayload,
        success: Boolean,
        responseCode: Int?,
        responseBody: String,
        errorMessage: String,
        requestBodyPreview: String,
        durationMillis: Long,
        dedupeKey: String,
    ) {
        db.sendLogDao().insert(
            SendLogEntity(
                id = java.util.UUID.randomUUID().toString(),
                hookId = hook.id,
                hookName = hook.name,
                appPackage = payload.appPackage,
                appName = payload.appName,
                title = payload.title,
                text = payload.text,
                requestUrl = hook.url,
                requestMethod = hook.method.wireName,
                requestBodyPreview = requestBodyPreview.take(MAX_BODY_PREVIEW),
                success = success,
                responseCode = responseCode,
                responseBody = responseBody.take(MAX_RESPONSE_BODY),
                errorMessage = errorMessage,
                durationMillis = durationMillis,
                createdAt = System.currentTimeMillis(),
                dedupeKeyMirror = dedupeKey,
            ),
        )
        db.sendLogDao().trimTo(MAX_LOGS)
    }

    suspend fun clear() = db.sendLogDao().clear()

    companion object {
        const val MAX_LOGS = 200
        private const val MAX_BODY_PREVIEW = 8_000
        private const val MAX_RESPONSE_BODY = 16_000
    }
}

class GlobalVariableRepository(private val db: AppDatabase) {

    fun observeAll(): Flow<List<GlobalVariableEntity>> = db.globalVariableDao().observeAll()

    suspend fun putAll(values: Map<String, String>) {
        val now = System.currentTimeMillis()
        values.forEach { (name, value) ->
            if (name.isNotBlank()) {
                db.globalVariableDao().put(GlobalVariableEntity(name, value, now))
            }
        }
    }

    suspend fun snapshot(): Map<String, String> =
        db.globalVariableDao().listAll().associate { it.name to it.value }

    suspend fun delete(name: String) = db.globalVariableDao().delete(name)
}

/** Durable queue for notification deliveries. */
class DeliveryTaskRepository(private val db: AppDatabase) {

    suspend fun enqueue(hook: Hook, payload: NotificationPayload): DeliveryTaskEntity {
        val task = createTask(hook, payload)
        db.deliveryTaskDao().upsert(task)
        return task
    }

    /**
     * Dedupe-aware enqueue. Inserts inside a transaction only when no recent
     * duplicate exists, so concurrent notifications can never double-enqueue.
     * Returns true when the task was actually enqueued.
     */
    suspend fun enqueueIfNotDuplicate(
        hook: Hook,
        payload: NotificationPayload,
        windowMs: Long,
    ): Boolean = db.withTransaction {
        if (isDuplicate(hook.id, payload.dedupeKey, windowMs)) {
            return@withTransaction false
        }
        enqueue(hook, payload)
        true
    }

    private suspend fun createTask(hook: Hook, payload: NotificationPayload): DeliveryTaskEntity {
        val now = System.currentTimeMillis()
        val task = DeliveryTaskEntity(
            id = java.util.UUID.randomUUID().toString(),
            hookId = hook.id,
            status = DeliveryStatus.PENDING.name,
            attemptCount = 0,
            maxAttempts = MAX_ATTEMPTS,
            nextAttemptAt = now,
            payloadJson = dbJson.encodeToString(NotificationPayload.serializer(), payload),
            hookSnapshotJson = dbJson.encodeToString(Hook.serializer(), hook),
            dedupeKey = payload.dedupeKey,
            lastError = "",
            createdAt = now,
            updatedAt = now,
        )
        db.deliveryTaskDao().upsert(task)
        return task
    }

    suspend fun claimDue(limit: Int): List<DeliveryTaskEntity> {
        val now = System.currentTimeMillis()
        return db.withTransaction {
            val due = db.deliveryTaskDao().dueTasks(now, limit)
            due.forEach { task ->
                db.deliveryTaskDao().update(
                    task.copy(status = DeliveryStatus.RUNNING.name, updatedAt = now),
                )
            }
            due.map { it.copy(status = DeliveryStatus.RUNNING.name) }
        }
    }

    suspend fun activeCount(): Int = db.deliveryTaskDao().activeCount()

    suspend fun nextRetryAt(): Long? = db.deliveryTaskDao().nextRetryAt()

    /**
     * Returns orphaned RUNNING rows to PENDING. A task is stale when it has
     * been "running" longer than the worst-case hook timeout (120s) plus
     * margin; any such row means its worker died mid-flight.
     */
    suspend fun requeueStaleRunning() {
        val now = System.currentTimeMillis()
        db.deliveryTaskDao().requeueStaleRunning(
            staleBefore = now - STALE_RUNNING_MS,
            now = now,
        )
    }

    suspend fun markRetry(task: DeliveryTaskEntity, error: String, nextAt: Long) {
        db.deliveryTaskDao().update(
            task.copy(
                status = DeliveryStatus.RETRY_WAIT.name,
                attemptCount = task.attemptCount + 1,
                nextAttemptAt = nextAt,
                lastError = error.take(2_000),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Terminal state: task row removed, outcome preserved in the send log. */
    suspend fun complete(task: DeliveryTaskEntity) {
        db.deliveryTaskDao().deleteById(task.id)
    }

    /**
     * Duplicate suppression: true when this hook already handled the same
     * dedupe key inside [windowMs]. Checks both the live queue and the log
     * mirror so a just-completed delivery is still deduped.
     */
    private suspend fun isDuplicate(hookId: String, dedupeKey: String, windowMs: Long): Boolean {
        val since = System.currentTimeMillis() - windowMs
        return db.deliveryTaskDao().countRecentTaskDuplicates(hookId, dedupeKey, since) > 0 ||
            db.deliveryTaskDao().countRecentLogDuplicates(hookId, dedupeKey, since) > 0
    }

    fun decodePayload(task: DeliveryTaskEntity): NotificationPayload =
        dbJson.decodeFromString(NotificationPayload.serializer(), task.payloadJson)

    fun decodeHook(task: DeliveryTaskEntity): Hook =
        dbJson.decodeFromString(Hook.serializer(), task.hookSnapshotJson)

    companion object {
        const val MAX_ATTEMPTS = 8
        private const val STALE_RUNNING_MS = 3 * 60_000L

        /**
         * First retry delay; doubles each attempt, capped at [MAX_BACKOFF_MS].
         * Sequence: 10s, 20s, 40s, ..., capped at 15min.
         */
        const val INITIAL_BACKOFF_MS = 10_000L
        const val MAX_BACKOFF_MS = 15 * 60_000L

        fun backoffFor(attemptCount: Int): Long {
            var delay = INITIAL_BACKOFF_MS
            repeat(attemptCount.coerceAtLeast(0)) {
                delay = (delay * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
            return delay.coerceAtMost(MAX_BACKOFF_MS)
        }
    }
}
