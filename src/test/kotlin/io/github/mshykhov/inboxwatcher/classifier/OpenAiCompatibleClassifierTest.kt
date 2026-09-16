package io.github.mshykhov.inboxwatcher.classifier

import io.github.mshykhov.inboxwatcher.config.AiResponseFormat
import io.github.mshykhov.inboxwatcher.core.Category
import io.github.mshykhov.inboxwatcher.core.ClassifierException
import io.github.mshykhov.inboxwatcher.core.EmailMessage
import io.github.mshykhov.inboxwatcher.core.Importance
import io.github.mshykhov.inboxwatcher.metrics.ClassifierMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenAiCompatibleClassifierTest {
    private val email =
        EmailMessage(
            id = "gmail-1",
            subject = "Rejection from Example Company",
            from = "talent@example.com",
            body = "Unfortunately we decided not to move forward.",
        )

    private fun completion(
        content: String,
        usage: String = "",
    ): String {
        val escaped =
            kotlinx.serialization.json
                .JsonPrimitive(content)
                .toString()
        return """{"choices":[{"message":{"content":$escaped}}]$usage}"""
    }

    private fun okHandler(classificationJson: String): HttpHandler = { Response(Status.OK).body(completion(classificationJson)) }

    @Test
    fun `maps a successful classification`() {
        val json =
            """{"importance":"not_important","urgency":"not_urgent","category":"recruiter-rejection",""" +
                """"reason":"ATS explicitly declines the application","summary":"Rejected"}"""
        val classifier =
            OpenAiCompatibleClassifier(
                apiKey = "k",
                baseUri = "https://api.cerebras.ai/v1/chat/completions",
                model = "gpt-oss-120b",
                http = okHandler(json),
            )

        val result = classifier.classify(email)

        assertEquals(Importance.NOT_IMPORTANT, result.importance)
        assertEquals(Category.RECRUITER_REJECTION, result.category)
        assertEquals("Rejected", result.summary)
        assertEquals("ATS explicitly declines the application", result.reason)
        assertEquals(null, result.action)
    }

    @Test
    fun `records openai compatible token usage and provider`() {
        val registry = SimpleMeterRegistry()
        val json =
            """{"importance":"important","urgency":"urgent","category":"recruiter-generic",""" +
                """"action":"Ответьте на вопросы рекрутёра","summary":"x"}"""
        val handler: HttpHandler = {
            Response(Status.OK).body(
                completion(json, ""","usage":{"prompt_tokens":321,"completion_tokens":29,"total_tokens":350}"""),
            )
        }
        val classifier =
            OpenAiCompatibleClassifier(
                apiKey = "k",
                baseUri = "https://api.groq.com/openai/v1/chat/completions",
                model = "openai/gpt-oss-20b",
                provider = "groq",
                http = handler,
                metrics = ClassifierMetrics(registry),
            )

        classifier.classify(email)

        assertEquals(
            1.0,
            registry
                .counter(
                    "classifier_api_requests_total",
                    "provider",
                    "groq",
                    "model",
                    "openai/gpt-oss-20b",
                    "outcome",
                    "success",
                    "status",
                    "200",
                ).count(),
        )
        assertEquals(
            321.0,
            registry
                .counter(
                    "classifier_api_tokens_total",
                    "provider",
                    "groq",
                    "model",
                    "openai/gpt-oss-20b",
                    "direction",
                    "input",
                ).count(),
        )
        assertEquals(
            29.0,
            registry
                .counter(
                    "classifier_api_tokens_total",
                    "provider",
                    "groq",
                    "model",
                    "openai/gpt-oss-20b",
                    "direction",
                    "output",
                ).count(),
        )
    }

    @Test
    fun `sends a bearer POST with model and strict json schema`() {
        var captured: Request? = null
        val handler: HttpHandler = { req ->
            captured = req
            Response(Status.OK).body(
                completion("""{"importance":"important","urgency":"urgent","category":"other","summary":"x"}"""),
            )
        }
        OpenAiCompatibleClassifier(
            apiKey = "secret-key",
            responseFormat = AiResponseFormat.JSON_SCHEMA,
            reasoningEffort = "low",
            baseUri = "https://api.groq.com/openai/v1/chat/completions",
            model = "openai/gpt-oss-20b",
            http = handler,
        ).classify(email)

        val request = requireNotNull(captured)
        assertEquals(Method.POST, request.method)
        assertEquals("https://api.groq.com/openai/v1/chat/completions", request.uri.toString())
        assertEquals("Bearer secret-key", request.header("Authorization"))
        val body = request.bodyString()
        assertTrue(body.contains("openai/gpt-oss-20b"), "request must name the model")
        assertTrue(body.contains("Rejection from Example Company"), "request must include the subject")
        assertTrue(body.contains("talent@example.com"), "request must include the sender - primary human-vs-automation signal")
        assertTrue(body.contains("json_schema"), "request must use structured output")
        assertTrue(body.contains("additionalProperties"), "strict schema requires additionalProperties:false")
        assertTrue(
            body.contains("\"reasoning_effort\":\"low\""),
            "gpt-oss must run at low reasoning effort - bounded classification, avoids burning the completion budget",
        )
        assertTrue(body.contains("\"max_tokens\":1024"), "completion length must be bounded")
    }

    @Test
    fun `disables NVIDIA chat template thinking and bounds completion`() {
        OpenAiCompatibleClassifier(
            apiKey = "key",
            baseUri = "https://integrate.api.nvidia.com/v1/chat/completions",
            model = "nvidia/nemotron-3.5-lightning-30b-a3b",
            provider = "nvidia",
            maxTokens = 500,
            thinking = "disabled",
            http = { request ->
                val body = request.bodyString()
                assertTrue(body.contains("\"max_tokens\":500"))
                assertTrue(body.contains("\"chat_template_kwargs\":{\"enable_thinking\":false}"))
                assertTrue(!body.contains("\"thinking\""))
                Response(Status.OK).body(
                    completion("""{"importance":"important","urgency":"urgent","category":"other","summary":"x"}"""),
                )
            },
        ).classify(email)
    }

    @Test
    fun `omits optional parameters and authorization for local prompt only models`() {
        val classifier =
            OpenAiCompatibleClassifier(
                apiKey = null,
                baseUri = "http://localhost:1234/v1/chat/completions",
                model = "local",
                responseFormat = AiResponseFormat.NONE,
                http = { request ->
                    assertEquals(null, request.header("Authorization"))
                    assertTrue(!request.bodyString().contains("response_format"))
                    assertTrue(!request.bodyString().contains("reasoning_effort"))
                    Response(Status.OK).body(
                        completion(
                            "```json\n" +
                                """{"importance":"important","urgency":"urgent","category":"other","summary":"x"}""" + "\n```",
                        ),
                    )
                },
            )
        assertEquals("x", classifier.classify(email).summary)
    }

    @Test
    fun `uses portable JSON mode by default without reasoning parameters`() {
        OpenAiCompatibleClassifier(
            apiKey = "key",
            baseUri = "https://example.test/v1/chat/completions",
            model = "generic",
            http = { request ->
                assertTrue(request.bodyString().contains("json_object"))
                assertTrue(!request.bodyString().contains("json_schema"))
                assertTrue(!request.bodyString().contains("reasoning_effort"))
                assertTrue(!request.bodyString().contains("\"thinking\""))
                Response(Status.OK).body(
                    completion(
                        """{"importance":"important","urgency":"urgent","category":"other","summary":"x"}""",
                    ),
                )
            },
        ).classify(email)
    }

    @Test
    fun `throws on non-2xx response`() {
        val registry = SimpleMeterRegistry()
        val classifier =
            OpenAiCompatibleClassifier(
                apiKey = "k",
                baseUri = "https://api.cerebras.ai/v1/chat/completions",
                model = "gpt-oss-120b",
                provider = "cerebras",
                http = { Response(Status.SERVICE_UNAVAILABLE) },
                metrics = ClassifierMetrics(registry),
            )

        assertFailsWith<ClassifierException> { classifier.classify(email) }
        assertEquals(
            1.0,
            registry
                .counter(
                    "classifier_api_requests_total",
                    "provider",
                    "cerebras",
                    "model",
                    "gpt-oss-120b",
                    "outcome",
                    "http_error",
                    "status",
                    "503",
                ).count(),
        )
        assertEquals(
            1L,
            registry
                .timer(
                    "classifier_api_request_duration",
                    "provider",
                    "cerebras",
                    "model",
                    "gpt-oss-120b",
                    "outcome",
                    "http_error",
                    "status",
                    "503",
                ).count(),
        )
    }

    @Test
    fun `throws on missing choices`() {
        val classifier =
            OpenAiCompatibleClassifier(
                apiKey = "k",
                baseUri = "https://api.cerebras.ai/v1/chat/completions",
                model = "gpt-oss-120b",
                http = { Response(Status.OK).body("""{"choices":[]}""") },
            )

        assertFailsWith<ClassifierException> { classifier.classify(email) }
    }

    @Test
    fun `wraps a transport failure so the fallback chain can catch it`() {
        val classifier =
            OpenAiCompatibleClassifier(
                apiKey = "k",
                baseUri = "https://api.cerebras.ai/v1/chat/completions",
                model = "gpt-oss-120b",
                http = { throw RuntimeException("connection reset") },
            )

        assertFailsWith<ClassifierException> { classifier.classify(email) }
    }
}
