package me.teamwicked.notibridge.model

/**
 * HTTP methods supported for webhook delivery.
 *
 * GET is intentionally supported even though webhooks are usually POST: some
 * receivers (health checks, simple bridges) only accept query-based requests.
 */
enum class HttpMethod(val wireName: String, val hasBody: Boolean) {
    POST("POST", true),
    PUT("PUT", true),
    GET("GET", false),
}

/** Body serialization formats supported by the hook editor. */
enum class ContentType(val label: String, val mimeType: String) {
    JSON("JSON", "application/json"),
    XML("XML", "application/xml"),
    PLAIN_TEXT("일반 텍스트", "text/plain; charset=utf-8"),
    FORM_URL_ENCODED("Form URL Encoded", "application/x-www-form-urlencoded"),
    HTML("HTML", "text/html; charset=utf-8"),
    BINARY("Binary", "application/octet-stream"),
}

/** Which notification text a regex rule runs against. */
enum class RegexSource(val label: String) {
    TITLE("제목"),
    TEXT("내용"),
    ALL("제목 + 내용"),
}

/** Lifecycle of a delivery task. */
enum class DeliveryStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    RETRY_WAIT,
    FAILED,
}
