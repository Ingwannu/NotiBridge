package me.teamwicked.notibridge

import me.teamwicked.notibridge.model.NotificationPayload
import me.teamwicked.notibridge.model.RegexRule
import me.teamwicked.notibridge.model.RegexSource
import me.teamwicked.notibridge.util.RegexExtractor
import me.teamwicked.notibridge.util.TemplateEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TemplateEngineTest {

    private fun payload() = NotificationPayload(
        appPackage = "com.kakao.talk",
        appName = "카카오톡",
        title = "홍길동",
        text = "결제금액 12,500원 확인됐습니다",
        subText = "sub",
        bigText = "big",
        summaryText = "sum",
        tickerText = "tick",
        timestampMillis = 1_700_000_000_000,
        dedupeKey = "k",
    )

    @Test
    fun `built-in tokens are replaced`() {
        val out = TemplateEngine.render(
            "{app_name}|{title}|{text}|{subtext}|{big_text}|{summary}|{ticker}|{app_package}|{timestamp_unix}",
            payload(),
            emptyMap(),
            emptyMap(),
        )
        assertEquals(
            "카카오톡|홍길동|결제금액 12,500원 확인됐습니다|sub|big|sum|tick|com.kakao.talk|1700000000",
            out,
        )
    }

    @Test
    fun `notification token expands to json`() {
        val out = TemplateEngine.render("{notification}", payload(), emptyMap(), emptyMap())
        assertEquals(true, out.startsWith("{\"app_package\":\"com.kakao.talk\""))
        assertEquals(true, out.contains("\"timestamp_unix\":1700000000"))
    }

    @Test
    fun `var and global tokens resolve from maps`() {
        val out = TemplateEngine.render(
            "{var_amount} / {global.session}",
            payload(),
            mapOf("amount" to "12,500"),
            mapOf("session" to "abc"),
        )
        assertEquals("12,500 / abc", out)
    }

    @Test
    fun `unknown tokens are left untouched`() {
        val out = TemplateEngine.render("{nope}", payload(), emptyMap(), emptyMap())
        assertEquals("{nope}", out)
    }

    @Test
    fun `package alias resolves like app_package`() {
        val out = TemplateEngine.render("{package}", payload(), emptyMap(), emptyMap())
        assertEquals("com.kakao.talk", out)
    }

    @Test
    fun `json escape protects quotes and newlines when enabled`() {
        val tricky = payload().copy(title = "따옴표\"와\n줄바꿈")
        val raw = TemplateEngine.render("{\"t\":\"{title}\"}", tricky, emptyMap(), emptyMap())
        assertEquals("{\"t\":\"따옴표\"와\n줄바꿈\"}", raw) // invalid JSON without escaping

        val escaped = TemplateEngine.render(
            "{\"t\":\"{title}\"}", tricky, emptyMap(), emptyMap(), escapeForJson = true,
        )
        assertEquals("{\"t\":\"따옴표\\\"와\\n줄바꿈\"}", escaped)
    }

    @Test
    fun `notification token is not double-escaped when json escaping`() {
        val out = TemplateEngine.render(
            "{notification}", payload(), emptyMap(), emptyMap(), escapeForJson = true,
        )
        assertEquals(true, out.startsWith("{\"app_package\""))
    }

    @Test
    fun `token regex is icu safe and matches braces`() {
        // Regression test: the previous pattern used "\{" which crashes on
        // Android's ICU regex at class init on some devices.
        val out = TemplateEngine.render("{title}{text}", payload(), emptyMap(), emptyMap())
        assertEquals("홍길동결제금액 12,500원 확인됐습니다", out)
    }
}

class RegexExtractorTest {

    private fun payload() = NotificationPayload(
        appPackage = "p",
        appName = "a",
        title = "제목",
        text = "결제금액 12,500원이 승인됐습니다. 잔액 3,000원",
        subText = "",
        bigText = "",
        summaryText = "",
        tickerText = "",
        timestampMillis = 0,
        dedupeKey = "k",
    )

    @Test
    fun `named groups become local variables`() {
        val rule = RegexRule(
            source = RegexSource.TEXT,
            pattern = "(?<amount>[0-9,]+)원",
        )
        val result = RegexExtractor.extract(listOf(rule), payload())
        assertEquals("12,500", result.localVariables["amount"])
        assertEquals(true, result.globalVariables.isEmpty())
    }

    @Test
    fun `global rules publish to globals`() {
        val rule = RegexRule(
            source = RegexSource.TEXT,
            pattern = "잔액 (?<balance>[0-9,]+)원",
            isGlobal = true,
        )
        val result = RegexExtractor.extract(listOf(rule), payload())
        assertEquals("3,000", result.globalVariables["balance"])
    }

    @Test
    fun `unnamed group falls back to variableName`() {
        val rule = RegexRule(
            source = RegexSource.TEXT,
            pattern = "([0-9,]+)원",
            variableName = "amount",
        )
        val result = RegexExtractor.extract(listOf(rule), payload())
        assertEquals("12,500", result.localVariables["amount"])
    }

    @Test
    fun `title source only matches title`() {
        val rule = RegexRule(source = RegexSource.TITLE, pattern = "(?<t>제목)")
        val result = RegexExtractor.extract(listOf(rule), payload())
        assertEquals("제목", result.localVariables["t"])

        val noMatch = RegexRule(source = RegexSource.TITLE, pattern = "(?<x>결제)")
        assertEquals(true, RegexExtractor.extract(listOf(noMatch), payload()).localVariables.isEmpty())
    }

    @Test
    fun `invalid pattern is skipped and reported by validation`() {
        val bad = RegexRule(pattern = "(unclosed")
        assertEquals(true, bad.validationError() != null)
        val result = RegexExtractor.extract(listOf(bad), payload())
        assertEquals(true, result.localVariables.isEmpty())
    }

    @Test
    fun `global rule without name or variableName is rejected`() {
        val rule = RegexRule(pattern = "[0-9]+", isGlobal = true)
        assertEquals(true, rule.validationError() != null)
    }

    @Test
    fun `test helper returns captures`() {
        val rule = RegexRule(pattern = "(?<amount>[0-9,]+)원")
        val captures = RegexExtractor.test(rule, "가격 9,900원")
        assertEquals(listOf("amount" to "9,900"), captures)
    }
}

class BackoffTest {

    @Test
    fun `backoff doubles from 10s and caps at 15min`() {
        val repo = me.teamwicked.notibridge.data.DeliveryTaskRepository
        assertEquals(10_000L, repo.backoffFor(0))
        assertEquals(20_000L, repo.backoffFor(1))
        assertEquals(40_000L, repo.backoffFor(2))
        assertEquals(15 * 60_000L, repo.backoffFor(20))
    }
}

class HookValidationTest {

    @Test
    fun `url must be http or https`() {
        val hook = me.teamwicked.notibridge.model.Hook(name = "x", url = "ftp://example.com")
        assertEquals(true, hook.validationErrors().isNotEmpty())
    }

    @Test
    fun `valid hook passes`() {
        val hook = me.teamwicked.notibridge.model.Hook(
            name = "디스코드",
            url = "https://discord.com/api/webhooks/1/2",
        )
        assertEquals(emptyList<String>(), hook.validationErrors())
    }

    @Test
    fun `preset round trip keeps fields`() {
        val hook = me.teamwicked.notibridge.model.Hook(
            name = "테스트",
            url = "https://example.com/hook",
            timeoutSeconds = 30,
            headers = mapOf("X-Key" to "v"),
            appPackages = listOf("a.b.c"),
            regexRules = listOf(RegexRule(pattern = "(?<n>[0-9]+)")),
        )
        val preset = hook.toPreset()
        val restored = me.teamwicked.notibridge.model.Hook.fromPreset(preset)
        assertEquals(hook.name, restored.name)
        assertEquals(hook.url, restored.url)
        assertEquals(hook.timeoutSeconds, restored.timeoutSeconds)
        assertEquals(hook.headers, restored.headers)
        assertEquals(hook.appPackages, restored.appPackages)
        assertEquals(hook.regexRules, restored.regexRules)
    }
}
