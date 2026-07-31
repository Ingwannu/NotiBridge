package me.teamwicked.notibridge.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import me.teamwicked.notibridge.model.DeliveryStatus

@Entity(tableName = "hooks")
data class HookEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean,
    val url: String,
    val method: String,
    val contentType: String,
    val timeoutSeconds: Int,
    /** JSON object of header name -> value. */
    val headersJson: String,
    val authHeaderName: String,
    val authToken: String,
    val bodyTemplate: String,
    val bodyFileName: String,
    val bodyFileBase64: String,
    /** JSON array of package names. Empty means "all apps". */
    val appPackagesJson: String,
    /** JSON array of RegexRule. */
    val regexRulesJson: String,
    /** JSON array of ExcludeFilter. */
    val excludeFiltersJson: String,
    val sortOrder: Int,
    val updatedAt: Long,
)

/**
 * Durable delivery queue row. Every accepted notification/hook pair becomes a
 * task so retries survive process death and reboots.
 */
@Entity(tableName = "delivery_tasks")
data class DeliveryTaskEntity(
    @PrimaryKey val id: String,
    val hookId: String,
    val status: String,
    val attemptCount: Int,
    val maxAttempts: Int,
    /** Epoch millis when the next attempt may start. */
    val nextAttemptAt: Long,
    val payloadJson: String,
    /** Snapshot of the hook at enqueue time so later edits don't corrupt retries. */
    val hookSnapshotJson: String,
    val dedupeKey: String,
    val lastError: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "send_logs")
data class SendLogEntity(
    @PrimaryKey val id: String,
    val hookId: String,
    val hookName: String,
    val appPackage: String,
    val appName: String,
    val title: String,
    val text: String,
    val requestUrl: String,
    val requestMethod: String,
    val requestBodyPreview: String,
    val success: Boolean,
    val responseCode: Int?,
    val responseBody: String,
    val errorMessage: String,
    /** Total wall time including retries, ms. */
    val durationMillis: Long,
    val createdAt: Long,
    /**
     * Mirrors the delivery task dedupe key so dedupe checks still work after
     * the task row itself has been deleted on success.
     */
    val dedupeKeyMirror: String,
)

/** Global variables shared across hooks via {global.name}. */
@Entity(tableName = "global_variables")
data class GlobalVariableEntity(
    @PrimaryKey val name: String,
    val value: String,
    val updatedAt: Long,
)

fun DeliveryTaskEntity.statusEnum(): DeliveryStatus =
    runCatching { DeliveryStatus.valueOf(status) }.getOrDefault(DeliveryStatus.PENDING)
