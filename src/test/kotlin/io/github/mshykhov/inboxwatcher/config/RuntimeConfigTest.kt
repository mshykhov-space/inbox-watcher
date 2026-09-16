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
        assertEquals(listOf("cerebras", "groq"), config.aiProviders.map { it.name })
        assertEquals(listOf("ckey", "qkey"), config.aiProviders.map { it.apiKey })
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

        assertEquals(listOf("gemini"), config.aiProviders.map { it.name })
        assertEquals("gkey", config.aiProviders.single().apiKey)
        assertEquals("/state/inbox-watcher.db", config.stateDbPath)
        assertEquals(8080, config.httpPort)
        assertEquals(60L, config.pollIntervalSeconds)
        assertEquals(12L, config.silenceAlertHours)
        assertNull(config.publicBaseUrl)
        assertEquals(30L, config.aiRequestTimeoutSeconds)
    }

    @Test
    fun `bounds the classifier timeout separately from other HTTP calls`() {
        assertEquals(60L, RuntimeConfig.fromMap(required + ("AI_REQUEST_TIMEOUT_SECONDS" to "60")).aiRequestTimeoutSeconds)
        for (value in listOf("0", "-1", "61", "invalid")) {
            assertFailsWith<ConfigException> {
                RuntimeConfig.fromMap(required + ("AI_REQUEST_TIMEOUT_SECONDS" to value))
            }
        }
    }

    @Test
    fun `rejects missing required keys naming them`() {
        val error =
            assertFailsWith<ConfigException> {
                RuntimeConfig.fromMap(required - "GEMINI_API_KEY" - "TELEGRAM_CHAT_ID")
            }

        assertEquals(true, error.message?.contains("TELEGRAM_CHAT_ID"))
    }

    @Test
    fun `selects groq without requiring a gemini key`() {
        val config = RuntimeConfig.fromMap((required - "GEMINI_API_KEY") + ("GROQ_API_KEY" to "qkey"))
        assertEquals(listOf("groq"), config.aiProviders.map { it.name })
    }

    @Test
    fun `explicit provider list overrides legacy flags and ignores unused settings`() {
        val config =
            RuntimeConfig.fromMap(
                required +
                    mapOf(
                        "AI_PROVIDERS" to " GROQ, Gemini ",
                        "GROQ_API_KEY" to "qkey",
                        "GEMINI_ENABLED" to "false",
                        "CEREBRAS_ENABLED" to "invalid",
                        "GROQ_MODEL" to "custom-model",
                        "GEMINI_MODEL" to "custom-gemini",
                    ),
            )
        assertEquals(listOf("groq", "gemini"), config.aiProviders.map { it.name })
        assertEquals(listOf("custom-model", "custom-gemini"), config.aiProviders.map { it.model })
        assertNull(config.aiProviders.first().reasoningEffort)
        assertEquals(AiResponseFormat.JSON_OBJECT, config.aiProviders.first().responseFormat)
    }

    @Test
    fun `supports custom local providers without dummy API keys`() {
        val config =
            RuntimeConfig.fromMap(
                (required - "GEMINI_API_KEY") +
                    mapOf(
                        "AI_PROVIDERS" to "local",
                        "LOCAL_BASE_URL" to "http://localhost:1234/v1/",
                        "LOCAL_MODEL" to "my-model",
                        "LOCAL_AUTH_REQUIRED" to "false",
                        "LOCAL_RESPONSE_FORMAT" to "none",
                    ),
            )
        assertEquals("http://localhost:1234/v1", config.aiProviders.single().baseUrl)
        assertNull(config.aiProviders.single().apiKey)
        assertEquals(AiResponseFormat.NONE, config.aiProviders.single().responseFormat)
    }

    @Test
    fun `requires at least one configured provider`() {
        assertFailsWith<ConfigException> { RuntimeConfig.fromMap(required - "GEMINI_API_KEY") }
        assertFailsWith<ConfigException> { RuntimeConfig.fromMap(required + ("GEMINI_ENABLED" to "false")) }
    }

    @Test
    fun `validates selected provider configuration before starting`() {
        val base =
            required +
                mapOf(
                    "AI_PROVIDERS" to "custom",
                    "CUSTOM_BASE_URL" to "https://example.test/v1",
                    "CUSTOM_MODEL" to "model",
                    "CUSTOM_API_KEY" to "secret",
                )
        val invalid =
            listOf(
                mapOf("AI_PROVIDERS" to "custom,custom"),
                mapOf("AI_PROVIDERS" to "custom,"),
                mapOf("AI_PROVIDERS" to "../custom"),
                mapOf("CUSTOM_API_KEY" to ""),
                mapOf("CUSTOM_MODEL" to ""),
                mapOf("CUSTOM_BASE_URL" to ""),
                mapOf("CUSTOM_BASE_URL" to "file:///tmp/api"),
                mapOf("CUSTOM_BASE_URL" to "https://secret@example.test/v1"),
                mapOf("CUSTOM_BASE_URL" to "https://example.test/v1?api_key=secret"),
                mapOf("CUSTOM_BASE_URL" to "https://example.test/v1#fragment"),
                mapOf("CUSTOM_API_TYPE" to "unknown"),
                mapOf("CUSTOM_RESPONSE_FORMAT" to "bad"),
                mapOf("CUSTOM_AUTH_REQUIRED" to "bad"),
                mapOf("CUSTOM_MAX_TOKENS" to "0"),
                mapOf("CUSTOM_THINKING" to "false"),
                mapOf("CUSTOM_THINKING" to "disabled", "CUSTOM_API_TYPE" to "anthropic"),
            )
        invalid.forEach { override ->
            assertFailsWith<ConfigException>(override.keys.toString()) {
                RuntimeConfig.fromMap(base + override)
            }
        }
    }

    @Test
    fun `legacy gpt oss tuning is preserved and can be disabled`() {
        val base = required + mapOf("AI_PROVIDERS" to "groq", "GROQ_API_KEY" to "qkey")
        val provider = RuntimeConfig.fromMap(base).aiProviders.single()
        assertEquals("low", provider.reasoningEffort)
        assertEquals(AiResponseFormat.JSON_SCHEMA, provider.responseFormat)
        assertNull(
            RuntimeConfig
                .fromMap(base + ("GROQ_REASONING_EFFORT" to "none"))
                .aiProviders
                .single()
                .reasoningEffort,
        )
    }

    @Test
    fun `uses bounded NVIDIA defaults with thinking disabled`() {
        val provider =
            RuntimeConfig
                .fromMap(required + mapOf("AI_PROVIDERS" to "nvidia", "NVIDIA_API_KEY" to "key"))
                .aiProviders
                .single()

        assertEquals("https://integrate.api.nvidia.com/v1", provider.baseUrl)
        assertEquals("nvidia/nemotron-3.5-lightning-30b-a3b", provider.model)
        assertEquals(AiResponseFormat.JSON_SCHEMA, provider.responseFormat)
        assertEquals("disabled", provider.thinking)
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
