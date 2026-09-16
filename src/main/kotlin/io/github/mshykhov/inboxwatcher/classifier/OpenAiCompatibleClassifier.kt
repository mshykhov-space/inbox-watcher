package io.github.mshykhov.inboxwatcher.classifier

import io.github.mshykhov.inboxwatcher.config.AiResponseFormat
import io.github.mshykhov.inboxwatcher.core.Classification
import io.github.mshykhov.inboxwatcher.core.Classifier
import io.github.mshykhov.inboxwatcher.core.ClassifierException
import io.github.mshykhov.inboxwatcher.core.EmailMessage
import io.github.mshykhov.inboxwatcher.metrics.ClassifierMetrics
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request

/** Classifier for any OpenAI-compatible /chat/completions endpoint with configurable structured output. */
class OpenAiCompatibleClassifier(
    private val apiKey: String?,
    private val baseUri: String,
    private val model: String,
    private val http: HttpHandler,
    private val maxBodyChars: Int = 4000,
    private val provider: String = "openai-compatible",
    private val metrics: ClassifierMetrics? = null,
    private val responseFormat: AiResponseFormat = AiResponseFormat.JSON_OBJECT,
    private val reasoningEffort: String? = null,
    private val maxTokens: Int = 1024,
    private val thinking: String? = null,
) : Classifier {
    override fun classify(email: EmailMessage): Classification {
        val startedNanos = System.nanoTime()
        val response =
            try {
                http(buildRequest(email))
            } catch (failure: Exception) {
                metrics?.record(provider, model, "transport_error", "transport", startedNanos)
                throw ClassifierException("$model transport failure", failure)
            }
        if (!response.status.successful) {
            metrics?.record(provider, model, "http_error", response.status.code.toString(), startedNanos)
            throw ClassifierException("$model returned ${response.status}")
        }
        return try {
            val parsed = parseResponse(response.bodyString())
            val classification = parseClassification(extractContent(parsed))
            metrics?.record(
                provider = provider,
                model = model,
                outcome = "success",
                status = response.status.code.toString(),
                startedNanos = startedNanos,
                inputTokens = parsed.usage?.promptTokens,
                outputTokens = parsed.usage?.completionTokens,
            )
            classification
        } catch (failure: ClassifierException) {
            metrics?.record(provider, model, "invalid_response", response.status.code.toString(), startedNanos)
            throw failure
        }
    }

    private fun buildRequest(email: EmailMessage): Request {
        val userText = classifierInput(email, maxBodyChars)
        val strictSchema = JsonObject(RESPONSE_SCHEMA + ("additionalProperties" to JsonPrimitive(false)))
        val body =
            buildJsonObject {
                put("model", model)
                put("max_tokens", maxTokens)
                reasoningEffort?.let { put("reasoning_effort", it) }
                thinking?.let { mode ->
                    if (provider == "nvidia") {
                        putJsonObject("chat_template_kwargs") { put("enable_thinking", mode == "enabled") }
                    } else {
                        putJsonObject("thinking") { put("type", mode) }
                    }
                }
                putJsonArray("messages") {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", SYSTEM_INSTRUCTION)
                        },
                    )
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", userText)
                        },
                    )
                }
                if (responseFormat != AiResponseFormat.NONE) {
                    putJsonObject("response_format") {
                        put("type", if (responseFormat == AiResponseFormat.JSON_SCHEMA) "json_schema" else "json_object")
                        if (responseFormat == AiResponseFormat.JSON_SCHEMA) {
                            putJsonObject("json_schema") {
                                put("name", "email_classification")
                                put("strict", true)
                                put("schema", strictSchema)
                            }
                        }
                    }
                }
            }
        return Request(Method.POST, baseUri)
            .let { request -> apiKey?.takeIf { it.isNotBlank() }?.let { request.header("Authorization", "Bearer $it") } ?: request }
            .header("content-type", "application/json")
            .body(body.toString())
    }

    private fun parseResponse(responseBody: String): OpenAiResponse =
        try {
            classifierJson.decodeFromString<OpenAiResponse>(responseBody)
        } catch (e: Exception) {
            throw ClassifierException("unparseable openai-compatible response", e)
        }

    private fun extractContent(response: OpenAiResponse): String =
        response.choices
            .firstOrNull()
            ?.message
            ?.content
            ?: throw ClassifierException("$model returned no choices")
}

@Serializable
private data class OpenAiResponse(
    val choices: List<OpenAiChoice> = emptyList(),
    val usage: OpenAiUsage? = null,
)

@Serializable
private data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Long? = null,
    @SerialName("completion_tokens") val completionTokens: Long? = null,
)

@Serializable
private data class OpenAiChoice(
    val message: OpenAiMessage? = null,
)

@Serializable
private data class OpenAiMessage(
    val content: String? = null,
)
