package io.github.mshykhov.inboxwatcher.metrics

import io.github.mshykhov.inboxwatcher.core.Category
import io.github.mshykhov.inboxwatcher.core.Classification
import io.github.mshykhov.inboxwatcher.core.Decision
import io.github.mshykhov.inboxwatcher.core.EmailStore
import io.github.mshykhov.inboxwatcher.core.Importance
import io.github.mshykhov.inboxwatcher.core.Urgency
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeteredEmailStoreTest {
    private class InMemoryStore : EmailStore {
        val recorded = mutableMapOf<String, Decision>()

        override fun isProcessed(messageId: String): Boolean = messageId in recorded

        override fun recordProcessed(
            messageId: String,
            decision: Decision,
        ) {
            recorded[messageId] = decision
        }

        override fun lastProcessedAtMillis(): Long? = null
    }

    private fun classified(
        category: Category,
        notified: Boolean,
    ) = Decision(
        classification =
            Classification(
                importance = Importance.NOT_IMPORTANT,
                urgency = Urgency.NOT_URGENT,
                category = category,
                summary = "s",
                reason = "r",
            ),
        notified = notified,
        reason = "r",
    )

    @Test
    fun `counts decisions by category and notified`() {
        val registry = SimpleMeterRegistry()
        val store = MeteredEmailStore(InMemoryStore(), registry)

        store.recordProcessed("1", classified(Category.OTHER, notified = false))
        store.recordProcessed("2", classified(Category.OTHER, notified = false))
        store.recordProcessed("3", classified(Category.RECRUITER_GENERIC, notified = true))

        assertEquals(
            2.0,
            registry.counter("emails_processed_total", "category", "other", "notified", "false").count(),
        )
        assertEquals(
            1.0,
            registry.counter("emails_processed_total", "category", "recruiter-generic", "notified", "true").count(),
        )
    }

    @Test
    fun `counts an unclassified decision under its own category tag`() {
        val registry = SimpleMeterRegistry()
        val store = MeteredEmailStore(InMemoryStore(), registry)

        store.recordProcessed("1", Decision(classification = null, notified = true, reason = "all failed"))

        assertEquals(
            1.0,
            registry.counter("emails_processed_total", "category", "unclassified", "notified", "true").count(),
        )
    }

    @Test
    fun `delegates reads to the wrapped store`() {
        val inner = InMemoryStore()
        val store = MeteredEmailStore(inner, SimpleMeterRegistry())

        store.recordProcessed("1", classified(Category.OTHER, notified = false))

        assertTrue(store.isProcessed("1"), "reads must hit the same wrapped store")
        assertTrue(inner.recorded.containsKey("1"), "writes must reach the wrapped store")
    }
}
