package io.github.mshykhov.inboxwatcher.core

/**
 * Turns an email into a [Classification]. Implementations call an LLM and throw
 * [ClassifierException] on any failure (transport, non-2xx, refusal, unparseable output)
 * so the caller can fall back - to the next provider, or to an "unclassified" notification.
 */
interface Classifier {
    fun classify(email: EmailMessage): Classification
}

class ClassifierException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
