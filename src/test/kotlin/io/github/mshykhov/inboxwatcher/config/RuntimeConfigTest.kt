package io.github.mshykhov.inboxwatcher.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RuntimeConfigTest {
    private val required =
        mapOf(
            "GOOGLE_CLIENT_ID" to "cid",
            "GOOGLE_CLIENT_SECRET" to "csecret",
            "GOOGLE_REFRESH_TOKEN" to "rtoken",
            "GEMINI_API_KEY" to "gkey",
            "TELEGRAM_BOT_TOKEN" to "btoken",
            "TELEGRAM_CHAT_ID" to "9001",
        )

    @Test
    fun `parses a full config`() {
        val config =
            RuntimeConfig.fromMap(
                required +
                    mapOf(
                        "CEREBRAS_API_KEY" to "ckey",
                        "GROQ_API_KEY" to "qkey",
                        "GEMINI_ENABLED" to "false",
                        "CEREBRAS_ENABLED" to "true",
                        "STATE_DB_PATH" to "/data/x.db",
                        "HTTP_PORT" to "9090",
                        "POLL_INTERVAL_SECONDS" to "30",
                        "SILENCE_ALERT_HOURS" to "6",
                        "PUBLIC_BASE_URL" to "https://mail.example.test/",
                    ),
            )

        assertEquals("cid", config.googleClientId)
        assertEquals("gkey", config.geminiApiKey)
        assertEquals(false, config.geminiEnabled)
        assertEquals(true, config.cerebrasEnabled)
        assertEquals("ckey", config.cerebrasApiKey)
        assertEquals("qkey", config.groqApiKey)
        assertEquals("9001", config.telegramChatId)
        assertEquals("/data/x.db", config.stateDbPath)
        assertEquals(9090, config.httpPort)
        assertEquals(30L, config.pollIntervalSeconds)
        assertEquals(6L, config.silenceAlertHours)
        assertEquals("https://mail.example.test", config.publicBaseUrl, "trailing slash must be trimmed")
    }

    @Test
    fun `applies defaults for optionals`() {
        val config = RuntimeConfig.fromMap(required)

        assertNull(config.cerebrasApiKey)
        assertNull(config.groqApiKey)
        assertEquals(true, config.geminiEnabled)
        assertEquals(false, config.cerebrasEnabled)
        assertEquals("/state/inbox-watcher.db", config.stateDbPath)
        assertEquals(8080, config.httpPort)
        assertEquals(60L, config.pollIntervalSeconds)
        assertEquals(12L, config.silenceAlertHours)
        assertNull(config.publicBaseUrl)
    }

    @Test
    fun `rejects missing required keys naming them`() {
        val error =
            assertFailsWith<ConfigException> {
                RuntimeConfig.fromMap(required - "GEMINI_API_KEY" - "TELEGRAM_CHAT_ID")
            }

        assertEquals(true, error.message?.contains("GEMINI_API_KEY"))
        assertEquals(true, error.message?.contains("TELEGRAM_CHAT_ID"))
    }

    @Test
    fun `rejects a non-numeric port`() {
        assertFailsWith<ConfigException> {
            RuntimeConfig.fromMap(required + ("HTTP_PORT" to "not-a-number"))
        }
    }

    @Test
    fun `rejects an invalid gemini enabled flag`() {
        assertFailsWith<ConfigException> {
            RuntimeConfig.fromMap(required + ("GEMINI_ENABLED" to "sometimes"))
        }
    }

    @Test
    fun `rejects an invalid cerebras enabled flag`() {
        assertFailsWith<ConfigException> {
            RuntimeConfig.fromMap(required + ("CEREBRAS_ENABLED" to "sometimes"))
        }
    }
}
