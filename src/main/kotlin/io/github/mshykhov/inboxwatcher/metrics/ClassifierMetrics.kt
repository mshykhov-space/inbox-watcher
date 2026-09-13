package io.github.mshykhov.inboxwatcher.metrics

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/** Provider-level API cost and reliability signals. Labels deliberately contain no email data. */
class ClassifierMetrics(
    private val registry: MeterRegistry,
) {
    fun record(
        provider: String,
        model: String,
        outcome: String,
        status: String,
        startedNanos: Long,
        inputTokens: Long? = null,
        outputTokens: Long? = null,
    ) {
        runCatching {
            val tags = arrayOf("provider", provider, "model", model, "outcome", outcome, "status", status)
            registry.counter("classifier_api_requests_total", *tags).increment()
            Timer
                .builder("classifier_api_request_duration")
                .tags(*tags)
                .register(registry)
                .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS)
            recordTokens(provider, model, "input", inputTokens)
            recordTokens(provider, model, "output", outputTokens)
        }.onFailure { logger.warn("could not record classifier API metrics", it) }
    }

    private fun recordTokens(
        provider: String,
        model: String,
        direction: String,
        tokens: Long?,
    ) {
        if (tokens == null || tokens < 0) return
        registry
            .counter(
                "classifier_api_tokens_total",
                "provider",
                provider,
                "model",
                model,
                "direction",
                direction,
            ).increment(tokens.toDouble())
    }

    private companion object {
        val logger = LoggerFactory.getLogger(ClassifierMetrics::class.java)
    }
}
