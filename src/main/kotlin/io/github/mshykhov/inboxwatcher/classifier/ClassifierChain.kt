package io.github.mshykhov.inboxwatcher.classifier

import io.github.mshykhov.inboxwatcher.config.RuntimeConfig
import io.github.mshykhov.inboxwatcher.core.Classifier
import io.github.mshykhov.inboxwatcher.metrics.ClassifierMetrics
import org.http4k.core.HttpHandler

private const val CEREBRAS_URL = "https://api.cerebras.ai/v1/chat/completions"
private const val CEREBRAS_MODEL = "gpt-oss-120b"
private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
private const val GROQ_MODEL = "openai/gpt-oss-20b"

/** Builds the ordered fallback chain from whichever provider keys are configured (Gemini first). */
fun buildClassifierChain(
    config: RuntimeConfig,
    http: HttpHandler,
    metrics: ClassifierMetrics? = null,
): List<Classifier> =
    buildList {
        if (config.geminiEnabled) add(GeminiClassifier(config.geminiApiKey, http, metrics = metrics))
        if (config.cerebrasEnabled) {
            config.cerebrasApiKey?.let {
                add(
                    OpenAiCompatibleClassifier(
                        apiKey = it,
                        baseUri = CEREBRAS_URL,
                        model = CEREBRAS_MODEL,
                        http = http,
                        provider = "cerebras",
                        metrics = metrics,
                    ),
                )
            }
        }
        config.groqApiKey?.let {
            add(
                OpenAiCompatibleClassifier(
                    apiKey = it,
                    baseUri = GROQ_URL,
                    model = GROQ_MODEL,
                    http = http,
                    provider = "groq",
                    metrics = metrics,
                ),
            )
        }
    }
