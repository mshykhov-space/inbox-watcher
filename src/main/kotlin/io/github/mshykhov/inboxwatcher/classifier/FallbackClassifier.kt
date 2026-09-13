package io.github.mshykhov.inboxwatcher.classifier

import io.github.mshykhov.inboxwatcher.core.Classification
import io.github.mshykhov.inboxwatcher.core.Classifier
import io.github.mshykhov.inboxwatcher.core.ClassifierException
import io.github.mshykhov.inboxwatcher.core.EmailMessage
import org.slf4j.LoggerFactory

/** Tries each classifier in order; first success wins. Throws only when the whole chain fails. */
class FallbackClassifier(
    private val chain: List<Classifier>,
) : Classifier {
    override fun classify(email: EmailMessage): Classification {
        val failures = mutableListOf<Throwable>()
        for (classifier in chain) {
            try {
                return classifier.classify(email)
            } catch (failure: RuntimeException) {
                failures += failure
                logger.warn("classifier {} failed, trying next", classifier::class.simpleName, failure)
            }
        }
        // Every provider's message survives into the stored decision reason for debugging.
        val details = failures.joinToString("; ") { it.message ?: it::class.simpleName ?: "unknown failure" }
        throw ClassifierException("all ${chain.size} classifiers failed: $details", failures.lastOrNull())
    }

    private companion object {
        val logger = LoggerFactory.getLogger(FallbackClassifier::class.java)
    }
}
