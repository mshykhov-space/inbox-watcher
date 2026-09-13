package io.github.mshykhov.inboxwatcher.classifier

import io.github.mshykhov.inboxwatcher.core.Classification
import io.github.mshykhov.inboxwatcher.core.Classifier
import io.github.mshykhov.inboxwatcher.core.ClassifierException
import io.github.mshykhov.inboxwatcher.core.EmailMessage
import io.github.mshykhov.inboxwatcher.metrics.ClassifierMetrics
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request

/** Classifier backed by Google Gemini (AI Studio free tier) with native constrained JSON output. */
class GeminiClassifier(
    private val apiKey: String,
    private val http: HttpHandler,
    private val model: String = "gemini-2.5-flash",
    private val maxBodyChars: Int = 4000,
    private val metrics: ClassifierMetrics? = null,
) : Classifier {
    override fun classify(email: EmailMessage): Classification {
        val startedNanos = System.nanoTime()
        val response =
            try {
                http(buildRequest(email))
            } catch (failure: Exception) {
                metrics?.record("gemini", model, "transport_error", "transport", startedNanos)
                throw ClassifierException("gemini transport failure", failure)
            }
        if (!response.status.successful) {
            metrics?.record("gemini", model, "http_error", response.status.code.toString(), startedNanos)
            throw ClassifierException("gemini returned ${response.status}")
        }
        return try {
            val parsed = parseResponse(response.bodyString())
            val classification = parseClassification(extractText(parsed))
            metrics?.record(
                provider = "gemini",
                model = model,
                outcome = "success",
                status = response.status.code.toString(),
                startedNanos = startedNanos,
                inputTokens = parsed.usageMetadata?.promptTokenCount,
                outputTokens = parsed.usageMetadata?.candidatesTokenCount,
            )
            classification
        } catch (failure: ClassifierException) {
            metrics?.record("gemini", model, "invalid_response", response.status.code.toString(), startedNanos)
            throw failure
        }
    }

    private fun buildRequest(email: EmailMessage): Request {
        val userText = classifierInput(email, maxBodyChars)
        val geminiSchema =
            JsonObject(
                RESPONSE_SCHEMA +
                    ("propertyOrdering" to JsonArray(PROPERTY_ORDER.map { JsonPrimitive(it) })),
            )
        val body =
            buildJsonObject {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", SYSTEM_INSTRUCTION) })
                    }
                }
                putJsonArray("contents") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            putJsonArray("parts") {
                                add(buildJsonObject { put("text", userText) })
                            }
                        },
                    )
                }
                putJsonObject("generationConfig") {
                    put("responseMimeType", "application/json")
                    put("responseSchema", geminiSchema)
                }
            }
        return Request(Method.POST, "$BASE_URL/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .header("content-type", "application/json")
            .body(body.toString())
    }

    private fun parseResponse(responseBody: String): GeminiResponse =
        try {
            classifierJson.decodeFromString<GeminiResponse>(responseBody)
        } catch (e: Exception) {
            throw ClassifierException("unparseable gemini response", e)
        }

    private fun extractText(response: GeminiResponse): String =
        response.candidates
            .firstOrNull()
            ?.content
            ?.parts
            ?.firstOrNull()
            ?.text
            ?: throw ClassifierException("gemini returned no content")

    private companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    }
}

@Serializable
private data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    val usageMetadata: GeminiUsage? = null,
)

@Serializable
private data class GeminiUsage(
    val promptTokenCount: Long? = null,
    val candidatesTokenCount: Long? = null,
)

@Serializable
private data class GeminiCandidate(
    val content: GeminiContent? = null,
)

@Serializable
private data class GeminiContent(
    val parts: List<GeminiPart> = emptyList(),
)

@Serializable
private data class GeminiPart(
    val text: String? = null,
)
