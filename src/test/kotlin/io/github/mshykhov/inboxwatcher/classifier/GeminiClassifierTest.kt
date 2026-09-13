package io.github.mshykhov.inboxwatcher.classifier

import io.github.mshykhov.inboxwatcher.core.Category
import io.github.mshykhov.inboxwatcher.core.ClassifierException
import io.github.mshykhov.inboxwatcher.core.EmailMessage
import io.github.mshykhov.inboxwatcher.core.Importance
import io.github.mshykhov.inboxwatcher.core.Urgency
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

class GeminiClassifierTest {
    private val email =
        EmailMessage(
            id = "gmail-1",
            subject = "Interview with Example Company",
            from = "talent@example.com",
            body = "We'd like to schedule an interview next week.",
        )

    private fun geminiBody(text: String): String {
        val escaped =
            kotlinx.serialization.json
                .JsonPrimitive(text)
                .toString()
        return """{"candidates":[{"content":{"parts":[{"text":$escaped}]}}]}"""
    }

    private fun okHandler(classificationJson: String): HttpHandler = { Response(Status.OK).body(geminiBody(classificationJson)) }

    @Test
    fun `maps a successful classification`() {
        val json =
            """{"importance":"important","urgency":"urgent","category":"recruiter-interview-request",""" +
                """"action":"Выберите время интервью",""" +
                """"reason":"Named recruiter proposes a concrete interview slot","summary":"Interview invite"}"""
        val classifier = GeminiClassifier(apiKey = "k", http = okHandler(json))

        val result = classifier.classify(email)

        assertEquals(Importance.IMPORTANT, result.importance)
        assertEquals(Urgency.URGENT, result.urgency)
        assertEquals(Category.RECRUITER_INTERVIEW_REQUEST, result.category)
        assertEquals("Interview invite", result.summary)
        assertEquals("Named recruiter proposes a concrete interview slot", result.reason)
        assertEquals("Выберите время интервью", result.action)
    }

    @Test
    fun `maps a transactional classification`() {
        val json =
            """{"importance":"important","urgency":"urgent","category":"transactional",""" +
                """"reason":"Example Travel cancelled the user's own booking","company":"Example Travel","summary":"x"}"""
        val classifier = GeminiClassifier(apiKey = "k", http = okHandler(json))

        val result = classifier.classify(email)

        assertEquals(Category.TRANSACTIONAL, result.category)
        assertEquals(Importance.IMPORTANT, result.importance)
    }

    @Test
    fun `maps an ai-news classification`() {
        val json =
            """{"importance":"important","urgency":"not_urgent","category":"ai-news",""" +
                """"reason":"Example AI Service itself announces a new model","company":"Example AI Service","summary":"x"}"""
        val classifier = GeminiClassifier(apiKey = "k", http = okHandler(json))

        val result = classifier.classify(email)

        assertEquals(Category.AI_NEWS, result.category)
        assertEquals(Importance.IMPORTANT, result.importance)
    }

    @Test
    fun `sends a POST with api key header and email content`() {
        var captured: Request? = null
        val handler: HttpHandler = { req ->
            captured = req
            Response(Status.OK).body(
                geminiBody("""{"importance":"not_important","urgency":"not_urgent","category":"other","summary":"x"}"""),
            )
        }
        GeminiClassifier(apiKey = "secret-key", http = handler, model = "gemini-2.5-flash-lite").classify(email)

        val request = requireNotNull(captured)
        assertEquals(Method.POST, request.method)
        assertTrue(request.uri.toString().contains("gemini-2.5-flash-lite:generateContent"))
        assertEquals("secret-key", request.header("x-goog-api-key"))
        val body = request.bodyString()
        assertTrue(body.contains("Interview with Example Company"), "request must include the subject")
        assertTrue(body.contains("schedule an interview"), "request must include the body")
        assertTrue(body.contains("talent@example.com"), "request must include the sender as a human-vs-automation signal")
        assertTrue(
            body.contains("application acknowledgement stays other even when it is signed by a named recruiter"),
            "named recruiter signatures must not promote automated application receipts",
        )
        assertTrue(
            body.contains("asking whether the user is interested, available, or ready to proceed"),
            "a recruiter waiting for a go-ahead must be marked urgent",
        )
        assertTrue(body.contains("recruiter-interview-request"), "request must constrain output via responseSchema")
        assertTrue(body.contains("ai-news"), "schema enum must allow the ai-news category")
        assertTrue(
            body.contains("\"propertyOrdering\":[\"category\",\"importance\",\"urgency\",\"action\",\"reason\",\"company\",\"summary\"]"),
            "gemini schema must decide category first and generate reason only after the verdict fields",
        )
        assertTrue(body.contains("Return an empty action when the user has nothing to do"))
    }

    @Test
    fun `records gemini token usage without email content labels`() {
        val registry = SimpleMeterRegistry()
        val response =
            geminiBody(
                """{"importance":"not_important","urgency":"not_urgent","category":"other","action":"","summary":"x"}""",
            ).dropLast(1) +
                ""","usageMetadata":{"promptTokenCount":123,"candidatesTokenCount":17,"totalTokenCount":140}}"""
        val classifier =
            GeminiClassifier(
                apiKey = "k",
                http = { Response(Status.OK).body(response) },
                metrics = ClassifierMetrics(registry),
            )

        classifier.classify(email)

        assertEquals(
            1.0,
            registry
                .counter(
                    "classifier_api_requests_total",
                    "provider",
                    "gemini",
                    "model",
                    "gemini-2.5-flash",
                    "outcome",
                    "success",
                    "status",
                    "200",
                ).count(),
        )
        assertEquals(
            123.0,
            registry
                .counter(
                    "classifier_api_tokens_total",
                    "provider",
                    "gemini",
                    "model",
                    "gemini-2.5-flash",
                    "direction",
                    "input",
                ).count(),
        )
        assertEquals(
            17.0,
            registry
                .counter(
                    "classifier_api_tokens_total",
                    "provider",
                    "gemini",
                    "model",
                    "gemini-2.5-flash",
                    "direction",
                    "output",
                ).count(),
        )
    }

    @Test
    fun `truncates a long body`() {
        var captured: Request? = null
        val handler: HttpHandler = { req ->
            captured = req
            Response(Status.OK).body(
                geminiBody("""{"importance":"not_important","urgency":"not_urgent","category":"other","summary":"x"}"""),
            )
        }
        val longBody = "A".repeat(50_000)
        GeminiClassifier(apiKey = "k", http = handler, maxBodyChars = 4000)
            .classify(email.copy(body = longBody))

        val sentBody = requireNotNull(captured).bodyString()
        assertTrue(sentBody.length < 20_000, "the 50k-char email body must be truncated before sending")
    }

    @Test
    fun `throws on non-2xx response`() {
        val classifier = GeminiClassifier(apiKey = "k", http = { Response(Status.TOO_MANY_REQUESTS) })

        assertFailsWith<ClassifierException> { classifier.classify(email) }
    }

    @Test
    fun `throws on empty candidates`() {
        val classifier = GeminiClassifier(apiKey = "k", http = { Response(Status.OK).body("""{"candidates":[]}""") })

        assertFailsWith<ClassifierException> { classifier.classify(email) }
    }

    @Test
    fun `wraps a transport failure so the fallback chain can catch it`() {
        val classifier = GeminiClassifier(apiKey = "k", http = { throw RuntimeException("connection reset") })

        assertFailsWith<ClassifierException> { classifier.classify(email) }
    }
}
