package io.github.mshykhov.inboxwatcher.classifier

import io.github.mshykhov.inboxwatcher.config.AiProviderConfig
import io.github.mshykhov.inboxwatcher.core.Classification
import io.github.mshykhov.inboxwatcher.core.Classifier
import io.github.mshykhov.inboxwatcher.core.ClassifierException
import io.github.mshykhov.inboxwatcher.core.EmailMessage
import io.github.mshykhov.inboxwatcher.metrics.ClassifierMetrics
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request

class AnthropicClassifier(
    private val config: AiProviderConfig,
    private val http: HttpHandler,
    private val metrics: ClassifierMetrics? = null,
) : Classifier {
    override fun classify(email: EmailMessage): Classification {
        val startedNanos = System.nanoTime()
        val body =
            buildJsonObject {
                put("model", config.model)
                put("max_tokens", config.maxTokens)
                put("system", SYSTEM_INSTRUCTION)
                putJsonArray("messages") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", classifierInput(email, 4000))
                        },
                    )
                }
            }
        val request =
            Request(Method.POST, "${config.baseUrl}/messages")
                .header("content-type", "application/json")
                .header("anthropic-version", "2023-06-01")
                .let { request -> config.apiKey?.let { request.header("x-api-key", it) } ?: request }
                .body(body.toString())
        val response =
            try {
                http(request)
            } catch (failure: Exception) {
                metrics?.record(config.name, config.model, "transport_error", "transport", startedNanos)
                throw ClassifierException("${config.name} transport failure", failure)
            }
        if (!response.status.successful) {
            metrics?.record(config.name, config.model, "http_error", response.status.code.toString(), startedNanos)
            throw ClassifierException("${config.name} returned ${response.status}")
        }
        return try {
            val parsed = classifierJson.decodeFromString<AnthropicResponse>(response.bodyString())
            if (parsed.stopReason != "end_turn") throw ClassifierException("${config.name} returned an incomplete response")
            val content = parsed.content.filter { it.type == "text" }.joinToString("") { it.text.orEmpty() }
            val classification = parseClassification(content)
            metrics?.record(
                config.name,
                config.model,
                "success",
                response.status.code.toString(),
                startedNanos,
                inputTokens = parsed.usage?.inputTokens,
                outputTokens = parsed.usage?.outputTokens,
            )
            classification
        } catch (failure: Exception) {
            metrics?.record(config.name, config.model, "invalid_response", response.status.code.toString(), startedNanos)
            throw ClassifierException("${config.name} returned invalid classification", failure)
        }
    }
}

@Serializable
private data class AnthropicResponse(
    val content: List<AnthropicContent> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
    val usage: AnthropicUsage? = null,
)

@Serializable
private data class AnthropicContent(
    val type: String,
    val text: String? = null,
)

@Serializable
private data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null,
)
