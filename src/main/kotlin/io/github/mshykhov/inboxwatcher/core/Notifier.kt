package io.github.mshykhov.inboxwatcher.core

/**
 * Pushes an email to the owner. A null [classification] means the classifier failed and the
 * email is delivered as "unclassified" - degradation over silence. Throws [NotifierException]
 * on delivery failure so the caller can leave the email unrecorded and retry.
 */
interface Notifier {
    fun notify(
        email: EmailMessage,
        classification: Classification?,
    )
}

class NotifierException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
