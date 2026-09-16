package io.github.mshykhov.inboxwatcher.classifier

import io.github.mshykhov.inboxwatcher.config.RuntimeConfig
import io.github.mshykhov.inboxwatcher.core.EmailMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.core.Response
import org.http4k.core.Status
import kotlin.test.Test
import kotlin.test.assertEquals

class ClassifierChainTest {
    private val noopHttp: org.http4k.core.HttpHandler = { Response(Status.OK) }

    @Test
    fun `passes configured thinking mode to a custom provider request`() {
        for (mode in listOf("enabled", "disabled")) {
            val config =
                RuntimeConfig.fromMap(
                    mapOf(
                        "GOOGLE_CLIENT_ID" to "c",
                        "GOOGLE_CLIENT_SECRET" to "s",
                        "GOOGLE_REFRESH_TOKEN" to "r",
                        "TELEGRAM_BOT_TOKEN" to "b",
                        "TELEGRAM_CHAT_ID" to "1",
                        "AI_PROVIDERS" to "zai",
                        "ZAI_API_KEY" to "key",
                        "ZAI_BASE_URL" to "https://example.test/v4",
                        "ZAI_MODEL" to "glm-4.5-flash",
                        "ZAI_THINKING" to mode.uppercase(),
                    ),
                )
            val chain =
                buildClassifierChain(config, { request ->
                    val body = Json.parseToJsonElement(request.bodyString()).jsonObject
                    assertEquals(
                        mode,
                        body
                            .getValue("thinking")
                            .jsonObject
                            .getValue("type")
                            .jsonPrimitive.content,
                    )
                    assertEquals("https://example.test/v4/chat/completions", request.uri.toString())
                    Response(Status.OK).body(
                        """{"choices":[{"message":{"content":"{\"category\":\"personal\",\"importance\":\"important\",\"urgency\":\"not_urgent\",\"summary\":\"Hi\"}"}}]}""",
                    )
                })
            assertEquals("Hi", chain.single().classify(EmailMessage("1", "Hi", "a@example.com", "Hello")).summary)
        }
    }

    private fun config(
        geminiEnabled: Boolean = true,
        cerebrasEnabled: Boolean = false,
        cerebras: String? = null,
        groq: String? = null,
    ) = RuntimeConfig.fromMap(
        mapOf(
            "GOOGLE_CLIENT_ID" to "c",
            "GOOGLE_CLIENT_SECRET" to "s",
            "GOOGLE_REFRESH_TOKEN" to "r",
            "TELEGRAM_BOT_TOKEN" to "b",
            "TELEGRAM_CHAT_ID" to "1",
            "GEMINI_API_KEY" to "g",
            "GEMINI_ENABLED" to geminiEnabled.toString(),
            "CEREBRAS_ENABLED" to cerebrasEnabled.toString(),
            "CEREBRAS_API_KEY" to cerebras.orEmpty(),
            "GROQ_API_KEY" to groq.orEmpty(),
        ),
    )

    @Test
    fun `gemini only when no fallback keys`() {
        assertEquals(1, buildClassifierChain(config(), noopHttp).size)
    }

    @Test
    fun `skips cerebras by default even when its exhausted key is present`() {
        assertEquals(1, buildClassifierChain(config(cerebras = "ck"), noopHttp).size)
    }

    @Test
    fun `adds cerebras only when explicitly enabled`() {
        assertEquals(2, buildClassifierChain(config(cerebrasEnabled = true, cerebras = "ck"), noopHttp).size)
    }

    @Test
    fun `adds groq while leaving disabled cerebras out`() {
        assertEquals(2, buildClassifierChain(config(cerebras = "ck", groq = "qk"), noopHttp).size)
    }

    @Test
    fun `skips disabled gemini and keeps configured fallbacks`() {
        assertEquals(
            1,
            buildClassifierChain(config(geminiEnabled = false, cerebras = "ck", groq = "qk"), noopHttp).size,
        )
    }
}
