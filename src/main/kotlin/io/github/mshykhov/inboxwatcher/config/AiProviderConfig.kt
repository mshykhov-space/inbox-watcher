package io.github.mshykhov.inboxwatcher.config

data class AiProviderConfig(
    val name: String,
    val protocol: AiProtocol,
    val apiKey: String?,
    val baseUrl: String,
    val model: String,
    val responseFormat: AiResponseFormat = AiResponseFormat.JSON_OBJECT,
    val reasoningEffort: String? = null,
    val maxTokens: Int = 1024,
    val thinking: String? = null,
) {
    override fun toString(): String = "AiProviderConfig(name=$name, protocol=$protocol, model=$model)"
}
