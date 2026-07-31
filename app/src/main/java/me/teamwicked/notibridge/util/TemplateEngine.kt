package me.teamwicked.notibridge.util

import me.teamwicked.notibridge.model.NotificationPayload
import me.teamwicked.notibridge.model.RegexRule
import me.teamwicked.notibridge.model.RegexSource

/**
 * Expands `{placeholder}` tokens in user templates.
 *
 * Supported tokens:
 *  - notification, title, text, subtext, big_text, summary, ticker,
 *    app_name, app_package, timestamp, timestamp_unix
 *  - var_<name>        : named capture group from a hook regex rule
 *  - global.<name>     : value from the shared global variable store
 *
 * Escaping is the caller's responsibility because the right escaping depends on
 * the target Content-Type (JSON, XML, ...), which the engine does not know.
 */
object TemplateEngine {

    private val TOKEN = Regex("\\{([A-Za-z0-9_.]+)}")

    fun render(
        template: String,
        payload: NotificationPayload,
        variables: Map<String, String>,
        globals: Map<String, String>,
    ): String {
        if (template.isEmpty()) return template
        return TOKEN.replace(template) { match ->
            resolve(match.groupValues[1], payload, variables, globals) ?: match.value
        }
    }

    /** List of tokens still present in [template]; handy for editor hints. */
    fun knownTokens(): List<String> = listOf(
        "{notification}", "{title}", "{text}", "{subtext}", "{big_text}",
        "{summary}", "{ticker}", "{app_name}", "{app_package}",
        "{timestamp}", "{timestamp_unix}", "{var_이름}", "{global.이름}",
    )

    private fun resolve(
        token: String,
        payload: NotificationPayload,
        variables: Map<String, String>,
        globals: Map<String, String>,
    ): String? = when (token) {
        "notification" -> payload.toJson()
        "title" -> payload.title
        "text" -> payload.text
        "subtext" -> payload.subText
        "big_text" -> payload.bigText
        "summary" -> payload.summaryText
        "ticker" -> payload.tickerText
        "app_name" -> payload.appName
        "app_package" -> payload.appPackage
        "timestamp" -> payload.timestampIso
        "timestamp_unix" -> (payload.timestampMillis / 1000).toString()
        else -> when {
            token.startsWith("var_") -> variables[token.removePrefix("var_")]
            token.startsWith("global.") -> globals[token.removePrefix("global.")]
            else -> null
        }
    }
}

/**
 * Runs a hook's regex rules against a notification and produces:
 *  - local variables addressable as {var_name}
 *  - global variables to publish for other hooks, addressable as {global.name}
 */
object RegexExtractor {

    data class Result(
        val localVariables: Map<String, String>,
        val globalVariables: Map<String, String>,
    )

    fun extract(rules: List<RegexRule>, payload: NotificationPayload): Result {
        val local = LinkedHashMap<String, String>()
        val global = LinkedHashMap<String, String>()
        rules.forEach { rule ->
            val regex = runCatching { Regex(rule.pattern) }.getOrNull() ?: return@forEach
            val target = when (rule.source) {
                RegexSource.TITLE -> payload.title
                RegexSource.TEXT -> payload.text
                RegexSource.ALL -> listOf(payload.title, payload.text)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
            }
            val match = regex.find(target) ?: return@forEach
            val named = captureNames(rule.pattern)
            if (named.isNotEmpty()) {
                val namedValues = namedCaptures(rule.pattern, target)
                named.forEach { name ->
                    val value = namedValues[name] ?: return@forEach
                    if (rule.isGlobal) global[name] = value else local[name] = value
                }
            } else if (rule.variableName.isNotBlank()) {
                val value = match.groupValues.getOrNull(1) ?: match.value
                if (rule.isGlobal) global[rule.variableName] = value else local[rule.variableName] = value
            }
        }
        return Result(localVariables = local, globalVariables = global)
    }

    /**
     * Test helper for the editor: runs one rule against arbitrary input text
     * and returns every capture (named when available).
     */
    fun test(rule: RegexRule, input: String): List<Pair<String, String>> {
        val regex = Regex(rule.pattern)
        val match = regex.find(input) ?: return emptyList()
        val named = captureNames(rule.pattern)
        return if (named.isNotEmpty()) {
            val namedValues = namedCaptures(rule.pattern, input)
            named.mapNotNull { name -> namedValues[name]?.let { name to it } }
        } else {
            match.groupValues.mapIndexedNotNull { index, value ->
                if (index == 0) null else {
                    val name = if (index == 1 && rule.variableName.isNotBlank()) {
                        rule.variableName
                    } else {
                        "group$index"
                    }
                    name to value
                }
            }
        }
    }

    private val NAMED_GROUP = Regex("\\(\\?<([A-Za-z_][A-Za-z0-9_]*)>")

    private fun captureNames(pattern: String): List<String> =
        NAMED_GROUP.findAll(pattern).map { it.groupValues[1] }.toList()

    /**
     * Named-group values are resolved through java.util.regex because it has
     * exposed Matcher.group(String) since Android API 1, while the Kotlin
     * MatchNamedGroupCollection bridge requires API 26+ and adds nothing else.
     */
    private fun namedCaptures(pattern: String, input: String): Map<String, String> {
        val names = captureNames(pattern)
        if (names.isEmpty()) return emptyMap()
        val javaMatcher = java.util.regex.Pattern.compile(pattern).matcher(input)
        if (!javaMatcher.find()) return emptyMap()
        return names.mapNotNull { name ->
            runCatching { javaMatcher.group(name) }.getOrNull()?.let { name to it }
        }.toMap()
    }
}
