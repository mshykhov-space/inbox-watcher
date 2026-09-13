package io.github.mshykhov.inboxwatcher.classifier

import io.github.mshykhov.inboxwatcher.core.Category
import io.github.mshykhov.inboxwatcher.core.Classification
import io.github.mshykhov.inboxwatcher.core.Classifier
import io.github.mshykhov.inboxwatcher.core.ClassifierException
import io.github.mshykhov.inboxwatcher.core.EmailMessage
import io.github.mshykhov.inboxwatcher.core.Importance
import io.github.mshykhov.inboxwatcher.core.Urgency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FallbackClassifierTest {
    private val email = EmailMessage(id = "1", subject = "s", from = "f", body = "b")

    private fun classification(summary: String) =
        Classification(Importance.IMPORTANT, Urgency.URGENT, Category.OTHER, summary, reason = "stub reason")

    private class StubClassifier(
        private val result: Classification? = null,
        private val failureMessage: String = "stub failure",
    ) : Classifier {
        var calls = 0

        override fun classify(email: EmailMessage): Classification {
            calls++
            return result ?: throw ClassifierException(failureMessage)
        }
    }

    @Test
    fun `returns first success without calling later providers`() {
        val first = StubClassifier(classification("from-primary"))
        val second = StubClassifier(classification("from-fallback"))

        val result = FallbackClassifier(listOf(first, second)).classify(email)

        assertEquals("from-primary", result.summary)
        assertEquals(0, second.calls)
    }

    @Test
    fun `falls through to the next provider on failure`() {
        val first = StubClassifier(result = null)
        val second = StubClassifier(classification("from-fallback"))

        val result = FallbackClassifier(listOf(first, second)).classify(email)

        assertEquals("from-fallback", result.summary)
        assertEquals(1, first.calls)
    }

    @Test
    fun `throws when every provider fails`() {
        val chain = listOf(StubClassifier(result = null), StubClassifier(result = null))

        assertFailsWith<ClassifierException> { FallbackClassifier(chain).classify(email) }
    }

    @Test
    fun `aggregates every provider failure into the thrown message`() {
        val chain =
            listOf(
                StubClassifier(failureMessage = "gemini returned 429"),
                StubClassifier(failureMessage = "gpt-oss-120b returned 503"),
            )

        val failure = assertFailsWith<ClassifierException> { FallbackClassifier(chain).classify(email) }

        val message = requireNotNull(failure.message)
        assertTrue(message.contains("gemini returned 429"), "message must name the first provider failure")
        assertTrue(message.contains("gpt-oss-120b returned 503"), "message must name the last provider failure")
    }

    @Test
    fun `throws on an empty chain`() {
        assertFailsWith<ClassifierException> { FallbackClassifier(emptyList()).classify(email) }
    }
}
