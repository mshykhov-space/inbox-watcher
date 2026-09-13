package io.github.mshykhov.inboxwatcher.core

/**
 * Dedup + decision log. A message is recorded only after it has been fully handled
 * (notified if needed), so a crash before delivery leaves it unprocessed and it is
 * retried on the next poll - zero-miss over dedup.
 */
interface EmailStore {
    fun isProcessed(messageId: String): Boolean

    fun recordProcessed(
        messageId: String,
        decision: Decision,
    )

    /** Timestamp of the newest recorded email; null on an empty store. Feeds the silence canary. */
    fun lastProcessedAtMillis(): Long?
}

/**
 * Outcome of handling one email. [classification] is null when the classifier failed
 * and the email was sent as "unclassified" (degradation over silence).
 * [reason] is the LLM's justification of the verdict, or the classifier-chain failure
 * text when unclassified - the debugging trail for "why was this (not) pushed".
 */
data class Decision(
    val classification: Classification?,
    val notified: Boolean,
    val reason: String,
)
