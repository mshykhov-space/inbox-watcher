package io.github.mshykhov.inboxwatcher.classifier

import io.github.mshykhov.inboxwatcher.config.AiProtocol
import io.github.mshykhov.inboxwatcher.config.RuntimeConfig
import io.github.mshykhov.inboxwatcher.core.Classifier
import io.github.mshykhov.inboxwatcher.metrics.ClassifierMetrics
import org.http4k.core.HttpHandler

fun buildClassifierChain(
    config: RuntimeConfig,
    http: HttpHandler,
    metrics: ClassifierMetrics? = null,
): List<Classifier> =
    config.aiProviders.map { provider ->
        when (provider.protocol) {
            AiProtocol.GEMINI ->
                GeminiClassifier(
                    apiKey = provider.apiKey,
                    http = http,
                    model = provider.model,
                    metrics = metrics,
                    baseUrl = provider.baseUrl,
                    provider = provider.name,
                )
            AiProtocol.ANTHROPIC -> AnthropicClassifier(provider, http, metrics)
            AiProtocol.OPENAI ->
                OpenAiCompatibleClassifier(
                    apiKey = provider.apiKey,
                    baseUri = "${provider.baseUrl}/chat/completions",
                    model = provider.model,
                    http = http,
                    provider = provider.name,
                    metrics = metrics,
                    responseFormat = provider.responseFormat,
                    reasoningEffort = provider.reasoningEffort,
                    thinking = provider.thinking,
                )
        }
    }
