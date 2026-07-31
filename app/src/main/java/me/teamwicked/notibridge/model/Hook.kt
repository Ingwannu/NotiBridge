package me.teamwicked.notibridge.model

import kotlinx.serialization.Serializable

/**
 * One regex extraction rule bound to a hook.
 *
 * Named capture groups `(?<name>...)` become `{var_name}` placeholders.
 * When [isGlobal] is set, matches are also published to the shared
 * GlobalVariableStore so any other hook can reference `{global.name}`.
 *
 * [variableName] overrides the group name when a rule only has unnamed
 * groups, e.g. `(\\d+)`, so users can still address the first group.
 */
@Serializable
data class RegexRule(
    val id: String = java.util.UUID.randomUUID().toString(),
    val source: RegexSource = RegexSource.ALL,
    val pattern: String = "",
    /** Fallback name used when the pattern has no named groups. */
    val variableName: String = "",
    val isGlobal: Boolean = false,
) {
    /** Returns null when the pattern compiles, otherwise a human-readable error. */
    fun validationError(): String? {
        if (pattern.isBlank()) return "정규식이 비어 있습니다."
        return try {
            if (isGlobal && variableName.isBlank() && !NAMED_GROUP.containsMatchIn(pattern)) {
                "전역 변수 규칙은 이름 있는 캡처 그룹 (?<이름>...) 또는 변수 이름이 필요합니다."
            } else {
                Regex(pattern)
                null
            }
        } catch (e: Exception) {
            "정규식 오류: ${e.message}"
        }
    }

    companion object {
        private val NAMED_GROUP = Regex("\\(\\?<([A-Za-z_][A-Za-z0-9_]*)>")
    }
}

/**
 * Condition that excludes a notification from being forwarded.
 * All fields are optional; a configured field must match for the filter to hit.
 */
@Serializable
data class ExcludeFilter(
    val id: String = java.util.UUID.randomUUID().toString(),
    val containsText: String = "",
    val regex: String = "",
    val matchTitle: Boolean = true,
    val matchText: Boolean = true,
) {
    fun isConfigured(): Boolean = containsText.isNotBlank() || regex.isNotBlank()

    fun validationError(): String? = try {
        if (regex.isNotBlank()) Regex(regex)
        null
    } catch (e: Exception) {
        "제외 필터 정규식 오류: ${e.message}"
    }

    fun matches(payload: NotificationPayload): Boolean {
        if (!isConfigured()) return false
        val candidates = buildList {
            if (matchTitle) add(payload.title)
            if (matchText) add(payload.text)
        }
        if (containsText.isNotBlank() && candidates.any { it.contains(containsText, ignoreCase = true) }) {
            return true
        }
        if (regex.isNotBlank()) {
            val re = Regex(regex)
            if (candidates.any { re.containsMatchIn(it) }) return true
        }
        return false
    }
}

/** Webhook preset shared as a `.notif` file. */
@Serializable
data class NotifPreset(
    val format: Int = 1,
    val name: String,
    val url: String,
    val method: HttpMethod,
    val contentType: ContentType,
    val timeoutSeconds: Int,
    val headers: Map<String, String>,
    val authHeaderName: String,
    val authToken: String,
    val bodyTemplate: String,
    val bodyFileName: String,
    val bodyFileBase64: String,
    val appPackages: List<String>,
    val regexRules: List<RegexRule>,
    val excludeFilters: List<ExcludeFilter>,
)

/** Domain representation of one webhook hook. */
@Serializable
data class Hook(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val url: String = "",
    val method: HttpMethod = HttpMethod.POST,
    val contentType: ContentType = ContentType.JSON,
    val timeoutSeconds: Int = 10,
    val headers: Map<String, String> = emptyMap(),
    /** Optional dedicated auth header. Sent as `<authHeaderName>: <authToken>`. */
    val authHeaderName: String = "Authorization",
    val authToken: String = "",
    val bodyTemplate: String = "",
    /** Original file name when an external file is used as the body source. */
    val bodyFileName: String = "",
    /** Cached copy of the external body file; the picker URI is not durable. */
    val bodyFileBase64: String = "",
    /**
     * Target packages. Empty means "모든 앱".
     */
    val appPackages: List<String> = emptyList(),
    val regexRules: List<RegexRule> = emptyList(),
    val excludeFilters: List<ExcludeFilter> = emptyList(),
) {
    fun bodyFileBytes(): ByteArray? =
        bodyFileBase64.takeIf { it.isNotBlank() }
            ?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }.getOrNull() }

    /** Aggregated validation errors; empty list means the hook can be saved. */
    fun validationErrors(): List<String> = buildList {
        if (name.isBlank()) add("훅 이름을 입력하세요.")
        val trimmedUrl = url.trim()
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            add("URL은 http:// 또는 https:// 로 시작해야 합니다.")
        }
        if (timeoutSeconds !in 1..120) add("타임아웃은 1~120초 사이여야 합니다.")
        regexRules.forEachIndexed { i, rule ->
            rule.validationError()?.let { add("정규식 ${i + 1}: $it") }
        }
        excludeFilters.forEachIndexed { i, filter ->
            filter.validationError()?.let { add("제외 필터 ${i + 1}: $it") }
        }
    }

    fun toPreset(): NotifPreset = NotifPreset(
        name = name,
        url = url,
        method = method,
        contentType = contentType,
        timeoutSeconds = timeoutSeconds,
        headers = headers,
        authHeaderName = authHeaderName,
        authToken = authToken,
        bodyTemplate = bodyTemplate,
        bodyFileName = bodyFileName,
        bodyFileBase64 = bodyFileBase64,
        appPackages = appPackages,
        regexRules = regexRules,
        excludeFilters = excludeFilters,
    )

    companion object {
        fun fromPreset(preset: NotifPreset): Hook = Hook(
            name = preset.name,
            url = preset.url,
            method = preset.method,
            contentType = preset.contentType,
            timeoutSeconds = preset.timeoutSeconds,
            headers = preset.headers,
            authHeaderName = preset.authHeaderName,
            authToken = preset.authToken,
            bodyTemplate = preset.bodyTemplate,
            bodyFileName = preset.bodyFileName,
            bodyFileBase64 = preset.bodyFileBase64,
            appPackages = preset.appPackages,
            regexRules = preset.regexRules,
            excludeFilters = preset.excludeFilters,
        )
    }
}
