package io.github.mshykhov.inboxwatcher.metrics

import io.github.mshykhov.inboxwatcher.core.Decision
import io.github.mshykhov.inboxwatcher.core.EmailStore
import io.micrometer.core.instrument.MeterRegistry

/**
 * Counts every persisted decision by category and push outcome. A store decorator sees the
 * full [Decision] at the single point where it becomes final - the pipeline stays metrics-free.
 */
class MeteredEmailStore(
    private val inner: EmailStore,
    private val registry: MeterRegistry,
) : EmailStore by inner {
    override fun recordProcessed(
        messageId: String,
        decision: Decision,
    ) {
        inner.recordProcessed(messageId, decision)
        registry
            .counter(
                "emails_processed_total",
                "category",
                decision.classification?.category?.wire ?: "unclassified",
                "notified",
                decision.notified.toString(),
            ).increment()
    }
}
