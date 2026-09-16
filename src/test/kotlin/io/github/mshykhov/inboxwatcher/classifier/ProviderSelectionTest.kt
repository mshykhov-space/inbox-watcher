package io.github.mshykhov.inboxwatcher.classifier

import io.github.mshykhov.inboxwatcher.config.RuntimeConfig
import io.github.mshykhov.inboxwatcher.core.ClassifierException
import io.github.mshykhov.inboxwatcher.core.EmailMessage
import io.github.mshykhov.inboxwatcher.metrics.ClassifierMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.core.Response
import org.http4k.core.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProviderSelectionTest {
    private val required =
        mapOf(
            "GOOGLE_CLIENT_ID" to "c",
            "GOOGLE_CLIENT_SECRET" to "s",
            "GOOGLE_REFRESH_TOKEN" to "r",
            "TELEGRAM_BOT_TOKEN" to "b",
            "TELEGRAM_CHAT_ID" to "1",
        )
    private val email = EmailMessage("id", "Test", "sender@example.test", "body")
    private val classification = """{"importance":"important","urgency":"urgent","category":"other","summary":"Result"}"""

    @Test
    fun `configured order controls actual requests and stops after successful fallback`() {
        val config =
            RuntimeConfig.fromMap(
                required +
                    mapOf(
                        "AI_PROVIDERS" to "first,second,gemini",
                        "FIRST_BASE_URL" to "https://first.example/v1/",
                        "FIRST_MODEL" to "first-model",
                        "FIRST_API_KEY" to "first-key",
                        "SECOND_API_TYPE" to "anthropic",
                        "SECOND_BASE_URL" to "https://second.example/v1",
                        "SECOND_MODEL" to "second-model",
                        "SECOND_API_KEY" to "second-key",
                        "SECOND_MAX_TOKENS" to "2048",
                        "GEMINI_API_KEY" to "unused",
                    ),
            )
        val calls = mutableListOf<String>()
        val registry = SimpleMeterRegistry()
        val chain =
            buildClassifierChain(config, { request ->
                calls += request.uri.toString()
                if (calls.size == 1) {
                    assertEquals("Bearer first-key", request.header("Authorization"))
                    Response(Status.TOO_MANY_REQUESTS)
                } else {
                    assertEquals("second-key", request.header("x-api-key"))
                    assertEquals("2023-06-01", request.header("anthropic-version"))
                    assertEquals(null, request.header("Authorization"))
                    val body = Json.parseToJsonElement(request.bodyString()).jsonObject
                    assertEquals("second-model", body.getValue("model").jsonPrimitive.content)
                    assertEquals("2048", body.getValue("max_tokens").jsonPrimitive.content)
                    assertTrue(
                        body
                            .getValue("system")
                            .jsonPrimitive.content
                            .contains("JSON"),
                    )
                    Response(Status.OK).body(anthropicResponse())
                }
            }, ClassifierMetrics(registry))
        assertEquals("Result", FallbackClassifier(chain).classify(email).summary)
        assertEquals(listOf("https://first.example/v1/chat/completions", "https://second.example/v1/messages"), calls)
        assertEquals(
            12.0,
            registry.counter("classifier_api_tokens_total", "provider", "second", "model", "second-model", "direction", "input").count(),
        )
        assertEquals(
            7.0,
            registry.counter("classifier_api_tokens_total", "provider", "second", "model", "second-model", "direction", "output").count(),
        )
    }

    @Test
    fun `custom Gemini endpoint and model are used without requiring built in credentials`() {
        val config =
            RuntimeConfig.fromMap(
                required +
                    mapOf(
                        "AI_PROVIDERS" to "google",
                        "GOOGLE_API_TYPE" to "gemini",
                        "GOOGLE_MODEL" to "custom-model",
                        "GOOGLE_BASE_URL" to "https://proxy.example/v1beta/",
                        "GOOGLE_API_KEY" to "google-key",
                    ),
            )
        val chain =
            buildClassifierChain(config, { request ->
                assertEquals("https://proxy.example/v1beta/models/custom-model:generateContent", request.uri.toString())
                assertEquals("google-key", request.header("x-goog-api-key"))
                Response(Status.OK).body("""{"candidates":[{"content":{"parts":[{"text":${JsonPrimitive(classification)}}]}}]}""")
            })
        assertEquals("Result", chain.single().classify(email).summary)
    }

    @Test
    fun `local ollama sends selected model without authentication`() {
        val config = RuntimeConfig.fromMap(required + mapOf("AI_PROVIDERS" to "ollama", "OLLAMA_MODEL" to "local-model"))
        val chain =
            buildClassifierChain(config, { request ->
                assertEquals("http://localhost:11434/v1/chat/completions", request.uri.toString())
                assertEquals(null, request.header("Authorization"))
                assertTrue(request.bodyString().contains("local-model"))
                Response(Status.OK).body("""{"choices":[{"message":{"content":${JsonPrimitive(classification)}}}]}""")
            })
        assertEquals("Result", chain.single().classify(email).summary)
    }

    @Test
    fun `anthropic errors remain visible when all providers fail`() {
        val config =
            RuntimeConfig.fromMap(
                required +
                    mapOf(
                        "AI_PROVIDERS" to "anthropic",
                        "ANTHROPIC_API_KEY" to "key",
                        "ANTHROPIC_MODEL" to "test-model",
                    ),
            )
        val responses =
            listOf(
                Response(Status.UNAUTHORIZED),
                Response(Status.OK).body("not JSON"),
                Response(Status.OK).body(anthropicResponse("max_tokens")),
                Response(Status.OK).body("""{"content":[],"stop_reason":"end_turn"}"""),
            )
        responses.forEach { response ->
            val chain = buildClassifierChain(config, { response })
            val error = assertFailsWith<ClassifierException> { FallbackClassifier(chain).classify(email) }
            assertTrue(error.message.orEmpty().contains("anthropic"))
        }
        val chain = buildClassifierChain(config, { throw IllegalStateException("transport") })
        assertFailsWith<ClassifierException> { FallbackClassifier(chain).classify(email) }
    }

    private fun anthropicResponse(stopReason: String = "end_turn"): String =
        """{"content":[{"type":"thinking","thinking":"ignored"},{"type":"text","text":${JsonPrimitive(
            classification,
        )}}],"stop_reason":"$stopReason","usage":{"input_tokens":12,"output_tokens":7}}"""
}
