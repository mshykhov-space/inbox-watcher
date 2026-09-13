package io.github.mshykhov.inboxwatcher.pipeline

import io.github.mshykhov.inboxwatcher.core.Category
import io.github.mshykhov.inboxwatcher.core.Classification
import io.github.mshykhov.inboxwatcher.core.Classifier
import io.github.mshykhov.inboxwatcher.core.ClassifierException
import io.github.mshykhov.inboxwatcher.core.Decision
import io.github.mshykhov.inboxwatcher.core.EmailMessage
import io.github.mshykhov.inboxwatcher.core.EmailStore
import io.github.mshykhov.inboxwatcher.core.GmailException
import io.github.mshykhov.inboxwatcher.core.GmailGateway
import io.github.mshykhov.inboxwatcher.core.Importance
import io.github.mshykhov.inboxwatcher.core.Notifier
import io.github.mshykhov.inboxwatcher.core.NotifierException
import io.github.mshykhov.inboxwatcher.core.Urgency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmailProcessorTest {
    private fun email(id: String) = EmailMessage(id = id, subject = "s-$id", from = "f", body = "b")

    private fun classification(
        importance: Importance,
        reason: String = "stub reason",
    ) = Classification(importance, Urgency.NOT_URGENT, Category.OTHER, "summary", reason = reason)

    private class FakeGateway(
        var ids: List<String>,
        val messages: Map<String, EmailMessage>,
        val fetchFailures: Set<String> = emptySet(),
        val listFails: Boolean = false,
    ) : GmailGateway {
        override fun listRecentMessageIds(): List<String> = if (listFails) throw GmailException("list boom") else ids

        override fun fetchMessage(id: String): EmailMessage =
            if (id in fetchFailures) throw GmailException("fetch $id") else messages.getValue(id)

        override fun profileEmail(): String = "watched@example.com"
    }

    private class InMemoryStore(
        initial: Set<String> = emptySet(),
        startEmpty: Boolean = false,
    ) : EmailStore {
        val processed = initial.toMutableSet()
        val decisions = mutableMapOf<String, Decision>()
        private var lastProcessedAt: Long? = if (startEmpty) null else 1L

        override fun isProcessed(messageId: String): Boolean = messageId in processed

        override fun recordProcessed(
            messageId: String,
            decision: Decision,
        ) {
            processed += messageId
            decisions[messageId] = decision
            lastProcessedAt = 2L
        }

        override fun lastProcessedAtMillis(): Long? = lastProcessedAt
    }

    private class StubClassifier(
        val byId: Map<String, Classification>,
        val failIds: Set<String> = emptySet(),
    ) : Classifier {
        override fun classify(email: EmailMessage): Classification =
            if (email.id in failIds) throw ClassifierException("classify boom") else byId.getValue(email.id)
    }

    private class RecordingNotifier(
        val failIds: Set<String> = emptySet(),
    ) : Notifier {
        val sent = mutableListOf<Pair<String, Classification?>>()

        override fun notify(
            email: EmailMessage,
            classification: Classification?,
        ) {
            if (email.id in failIds) throw NotifierException("notify boom")
            sent += email.id to classification
        }
    }

    private fun processor(
        gateway: FakeGateway,
        store: InMemoryStore,
        classifier: StubClassifier,
        notifier: RecordingNotifier,
        errors: MutableList<String> = mutableListOf(),
        seeds: MutableList<Int> = mutableListOf(),
    ) = EmailProcessor(
        gmail = gateway,
        store = store,
        classifier = classifier,
        notifier = notifier,
        onError = { message, _ -> errors += message },
        onSeed = { count -> seeds += count },
    )

    @Test
    fun `skips already processed messages`() {
        val gateway = FakeGateway(ids = listOf("a"), messages = mapOf("a" to email("a")))
        val store = InMemoryStore(initial = setOf("a"))
        val notifier = RecordingNotifier()

        processor(gateway, store, StubClassifier(emptyMap()), notifier).pollOnce()

        assertTrue(notifier.sent.isEmpty())
    }

    @Test
    fun `notifies and records an important email`() {
        val gateway = FakeGateway(ids = listOf("a"), messages = mapOf("a" to email("a")))
        val store = InMemoryStore()
        val notifier = RecordingNotifier()
        val classifier = StubClassifier(mapOf("a" to classification(Importance.IMPORTANT)))

        processor(gateway, store, classifier, notifier).pollOnce()

        assertEquals(listOf("a"), notifier.sent.map { it.first })
        assertTrue(store.isProcessed("a"))
        assertTrue(store.decisions.getValue("a").notified)
    }

    @Test
    fun `records but does not notify an unimportant email`() {
        val gateway = FakeGateway(ids = listOf("a"), messages = mapOf("a" to email("a")))
        val store = InMemoryStore()
        val notifier = RecordingNotifier()
        val classifier = StubClassifier(mapOf("a" to classification(Importance.NOT_IMPORTANT)))

        processor(gateway, store, classifier, notifier).pollOnce()

        assertTrue(notifier.sent.isEmpty())
        assertTrue(store.isProcessed("a"))
        assertFalse(store.decisions.getValue("a").notified)
    }

    @Test
    fun `records the classifier reason with the decision`() {
        val gateway = FakeGateway(ids = listOf("a"), messages = mapOf("a" to email("a")))
        val store = InMemoryStore()
        val classifier =
            StubClassifier(mapOf("a" to classification(Importance.NOT_IMPORTANT, reason = "Automated job digest, no human author")))

        processor(gateway, store, classifier, RecordingNotifier()).pollOnce()

        assertEquals("Automated job digest, no human author", store.decisions.getValue("a").reason)
    }

    @Test
    fun `records the chain failure text as the reason of an unclassified decision`() {
        val gateway = FakeGateway(ids = listOf("a"), messages = mapOf("a" to email("a")))
        val store = InMemoryStore()
        val classifier = StubClassifier(byId = emptyMap(), failIds = setOf("a"))

        processor(gateway, store, classifier, RecordingNotifier()).pollOnce()

        val reason = store.decisions.getValue("a").reason
        assertTrue(
            reason.orEmpty().contains("classify boom"),
            "the chain failure text must survive into the stored reason, got: $reason",
        )
    }

    @Test
    fun `degrades to unclassified notification when the classifier fails`() {
        val gateway = FakeGateway(ids = listOf("a"), messages = mapOf("a" to email("a")))
        val store = InMemoryStore()
        val notifier = RecordingNotifier()
        val classifier = StubClassifier(byId = emptyMap(), failIds = setOf("a"))

        processor(gateway, store, classifier, notifier).pollOnce()

        assertEquals(1, notifier.sent.size)
        assertNull(notifier.sent.single().second, "unclassified notification carries no classification")
        assertTrue(store.isProcessed("a"))
    }

    @Test
    fun `does not record when notification fails so it retries`() {
        val gateway = FakeGateway(ids = listOf("a", "b"), messages = mapOf("a" to email("a"), "b" to email("b")))
        val store = InMemoryStore()
        val notifier = RecordingNotifier(failIds = setOf("a"))
        val classifier =
            StubClassifier(mapOf("a" to classification(Importance.IMPORTANT), "b" to classification(Importance.IMPORTANT)))
        val errors = mutableListOf<String>()

        processor(gateway, store, classifier, notifier, errors).pollOnce()

        assertFalse(store.isProcessed("a"), "unsent email must stay unrecorded for retry")
        assertTrue(store.isProcessed("b"), "a failure on one email must not block the others")
        assertTrue(errors.any { it.contains("a") })
    }

    @Test
    fun `first poll on an empty store seeds the backlog silently and alerts once`() {
        val gateway = FakeGateway(ids = listOf("a", "b"), messages = emptyMap())
        val store = InMemoryStore(startEmpty = true)
        val notifier = RecordingNotifier()
        val seeds = mutableListOf<Int>()

        processor(gateway, store, StubClassifier(emptyMap()), notifier, seeds = seeds).pollOnce()

        assertTrue(notifier.sent.isEmpty(), "the day-old backlog must not flood Telegram")
        assertTrue(store.isProcessed("a") && store.isProcessed("b"))
        assertEquals("first-run seed", store.decisions.getValue("a").reason)
        assertEquals(listOf(2), seeds, "one informational alert carries the seeded count")
    }

    @Test
    fun `mail arriving after the seed is classified normally`() {
        val gateway = FakeGateway(ids = listOf("a"), messages = mapOf("c" to email("c")))
        val store = InMemoryStore(startEmpty = true)
        val notifier = RecordingNotifier()
        val classifier = StubClassifier(mapOf("c" to classification(Importance.IMPORTANT)))
        val emailProcessor = processor(gateway, store, classifier, notifier)

        emailProcessor.pollOnce()
        gateway.ids = listOf("a", "c")
        emailProcessor.pollOnce()

        assertEquals(listOf("c"), notifier.sent.map { it.first }, "post-seed mail goes through the normal pipeline")
    }

    @Test
    fun `an empty first inbox seeds nothing and later mail is still delivered`() {
        val gateway = FakeGateway(ids = emptyList(), messages = mapOf("a" to email("a")))
        val store = InMemoryStore(startEmpty = true)
        val notifier = RecordingNotifier()
        val classifier = StubClassifier(mapOf("a" to classification(Importance.IMPORTANT)))
        val seeds = mutableListOf<Int>()
        val emailProcessor = processor(gateway, store, classifier, notifier, seeds = seeds)

        emailProcessor.pollOnce()
        gateway.ids = listOf("a")
        emailProcessor.pollOnce()

        assertTrue(seeds.isEmpty(), "seeding zero mail needs no alert")
        assertEquals(listOf("a"), notifier.sent.map { it.first }, "seeding happens once per process - mail after it must be delivered")
    }

    @Test
    fun `reports an error when listing fails`() {
        val gateway = FakeGateway(ids = emptyList(), messages = emptyMap(), listFails = true)
        val errors = mutableListOf<String>()

        processor(gateway, InMemoryStore(), StubClassifier(emptyMap()), RecordingNotifier(), errors).pollOnce()

        assertTrue(errors.isNotEmpty(), "a dead Gmail list must be alerted, not swallowed")
    }
}
