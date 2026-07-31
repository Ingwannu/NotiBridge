package me.teamwicked.notibridge.model

import kotlinx.serialization.Serializable

/**
 * Immutable snapshot of an Android notification at the moment it was posted.
 *
 * A snapshot is taken because [android.service.notification.StatusBarNotification]
 * instances are only valid inside the listener callback; forwarding happens
 * asynchronously, so the data must be detached from the framework object first.
 */
@Serializable
data class NotificationPayload(
    val appPackage: String,
    val appName: String,
    val title: String,
    val text: String,
    val subText: String,
    val bigText: String,
    val summaryText: String,
    val tickerText: String,
    /** Wall-clock time the notification was posted (ms since epoch). */
    val timestampMillis: Long,
    /**
     * Stable key used for duplicate suppression. Combines package, user-visible
     * content and posting time so repeated identical notifications within a
     * short window are only forwarded once.
     */
    val dedupeKey: String,
) {
    /** ISO-8601 style local timestamp used by the {timestamp} placeholder. */
    val timestampIso: String
        get() = java.time.Instant.ofEpochMilli(timestampMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /** {notification} expands to a JSON object containing every captured field. */
    fun toJson(): String = buildString {
        fun esc(v: String): String = buildString(v.length + 8) {
            append('"')
            v.forEach { c ->
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }
        append('{')
        append("\"app_package\":").append(esc(appPackage)).append(',')
        append("\"app_name\":").append(esc(appName)).append(',')
        append("\"title\":").append(esc(title)).append(',')
        append("\"text\":").append(esc(text)).append(',')
        append("\"subtext\":").append(esc(subText)).append(',')
        append("\"big_text\":").append(esc(bigText)).append(',')
        append("\"summary\":").append(esc(summaryText)).append(',')
        append("\"ticker\":").append(esc(tickerText)).append(',')
        append("\"timestamp\":").append(esc(timestampIso)).append(',')
        append("\"timestamp_unix\":").append(timestampMillis / 1000)
        append('}')
    }

    companion object {
        fun buildDedupeKey(appPackage: String, title: String, text: String, timestampMillis: Long): String {
            // Rounding to 5s buckets lets the dedupe window tolerate the same
            // notification being re-posted with a slightly different timestamp.
            val bucket = timestampMillis / 5_000L
            val raw = "$appPackage|$title|$text|$bucket"
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(32)
        }

        /** Payload used by the "테스트" flows in the hook editor. */
        fun sample(): NotificationPayload = NotificationPayload(
            appPackage = "com.example.chat",
            appName = "예시 채팅",
            title = "홍길동",
            text = "회의가 10분 뒤에 시작됩니다.",
            subText = "3번째 알림",
            bigText = "회의가 10분 뒤에 시작됩니다.\n참석 링크를 확인하세요.",
            summaryText = "요약 텍스트",
            tickerText = "홍길동: 회의가 10분 뒤에 시작됩니다.",
            timestampMillis = System.currentTimeMillis(),
            dedupeKey = "sample",
        )
    }
}
