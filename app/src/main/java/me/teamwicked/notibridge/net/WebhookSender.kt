package me.teamwicked.notibridge.net

import java.util.concurrent.TimeUnit
import me.teamwicked.notibridge.model.ContentType
import me.teamwicked.notibridge.model.Hook
import me.teamwicked.notibridge.model.HttpMethod
import me.teamwicked.notibridge.model.NotificationPayload
import me.teamwicked.notibridge.util.RegexExtractor
import me.teamwicked.notibridge.util.TemplateEngine
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** Outcome of one HTTP attempt. */
data class SendOutcome(
    val success: Boolean,
    val responseCode: Int?,
    val responseBody: String,
    val errorMessage: String,
    /** Milliseconds spent inside the HTTP call itself. */
    val elapsedMillis: Long,
    /** Rendered body (or a short descriptor for binary), for the send log. */
    val requestBodyPreview: String,
)

/**
 * Builds and executes the webhook HTTP request for one hook/payload pair.
 *
 * A fresh OkHttpClient per hook keeps per-hook timeout semantics trivial and
 * avoids cross-hook interference; OkHttp shares its connection pool only per
 * client, and webhook traffic here is low-volume, so the cost is acceptable.
 */
class WebhookSender(
    private val globalsProvider: suspend () -> Map<String, String>,
    private val globalsPublisher: suspend (Map<String, String>) -> Unit,
) {

    suspend fun send(
        hook: Hook,
        payload: NotificationPayload,
        overrideBody: ByteArray? = null,
    ): SendOutcome {
        val extraction = RegexExtractor.extract(hook.regexRules, payload)
        if (extraction.globalVariables.isNotEmpty()) {
            // Global captures must be visible to subsequent hooks immediately.
            globalsPublisher(extraction.globalVariables)
        }
        val globals = globalsProvider()

        val bodyBytes: ByteArray? = when {
            !hook.method.hasBody -> null
            overrideBody != null -> overrideBody
            hook.bodyFileBytes() != null -> hook.bodyFileBytes()
            else -> TemplateEngine.render(
                template = hook.bodyTemplate,
                payload = payload,
                variables = extraction.localVariables,
                globals = globals,
            ).toByteArray(Charsets.UTF_8)
        }

        val preview = when {
            !hook.method.hasBody -> "(GET - no body)"
            hook.contentType == ContentType.BINARY ->
                "<binary ${bodyBytes?.size ?: 0} bytes${hook.bodyFileName.let { if (it.isBlank()) "" else " from $it" }}>"
            else -> bodyBytes?.toString(Charsets.UTF_8)?.take(8_000).orEmpty()
        }

        val requestBody: RequestBody? = bodyBytes?.toRequestBody(
            hook.contentType.mimeType.toMediaTypeOrNull(),
        )

        val requestBuilder = Request.Builder().url(hook.url.trim())
        hook.headers.forEach { (name, value) ->
            if (name.isNotBlank()) requestBuilder.addHeader(name.trim(), value)
        }
        if (hook.authToken.isNotBlank()) {
            requestBuilder.addHeader(
                hook.authHeaderName.ifBlank { "Authorization" },
                hook.authToken,
            )
        }
        when (hook.method) {
            HttpMethod.GET -> requestBuilder.get()
            HttpMethod.POST -> requestBuilder.post(requestBody ?: ByteArray(0).toRequestBody(null))
            HttpMethod.PUT -> requestBuilder.put(requestBody ?: ByteArray(0).toRequestBody(null))
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(hook.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(hook.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(hook.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        val startedAt = System.nanoTime()
        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val elapsed = (System.nanoTime() - startedAt) / 1_000_000
                val responseBody = response.body?.string().orEmpty()
                SendOutcome(
                    success = response.isSuccessful,
                    responseCode = response.code,
                    responseBody = responseBody,
                    errorMessage = if (response.isSuccessful) "" else "HTTP ${response.code}",
                    elapsedMillis = elapsed,
                    requestBodyPreview = preview,
                )
            }
        } catch (e: Exception) {
            val elapsed = (System.nanoTime() - startedAt) / 1_000_000
            SendOutcome(
                success = false,
                responseCode = null,
                responseBody = "",
                errorMessage = e.message ?: e.javaClass.simpleName,
                elapsedMillis = elapsed,
                requestBodyPreview = preview,
            )
        }
    }
}
