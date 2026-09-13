package io.github.mshykhov.inboxwatcher.pipeline

import io.github.mshykhov.inboxwatcher.core.Classifier
import io.github.mshykhov.inboxwatcher.core.ClassifierException
import io.github.mshykhov.inboxwatcher.core.Decision
import io.github.mshykhov.inboxwatcher.core.EmailStore
import io.github.mshykhov.inboxwatcher.core.GmailGateway
import io.github.mshykhov.inboxwatcher.core.Importance
import io.github.mshykhov.inboxwatcher.core.Notifier
import org.slf4j.LoggerFactory

data class PollResult(
    val processed: Int,
    val notified: Int,
    val failed: Int,
)

class EmailProcessor(
    private val gmail: GmailGateway,
    private val store: EmailStore,
    private val classifier: Classifier,
    private val notifier: Notifier,
    private val onError: (String, Throwable) -> Unit = { message, cause -> logger.error(message, cause) },
    private val onSeed: (Int) -> Unit = {},
) {
    private var seedChecked = false

    fun pollOnce(): PollResult {
        val ids =
            try {
                gmail.listRecentMessageIds()
            } catch (failure: RuntimeException) {
                onError("failed to list recent Gmail messages", failure)
                return PollResult(processed = 0, notified = 0, failed = 1)
            }
        if (!seedChecked) {
            seedChecked = true
            if (store.lastProcessedAtMillis() == null) return seedBacklog(ids)
        }

        var processed = 0
        var notified = 0
        var failed = 0
        for (id in ids) {
            if (store.isProcessed(id)) continue
            try {
                if (handle(id)) notified++
                processed++
            } catch (failure: RuntimeException) {
                // Not recorded -> retried next poll. Never swallow: alert loudly.
                failed++
                onError("failed to process Gmail message $id", failure)
            }
        }
        return PollResult(processed, notified, failed)
    }

    /**
     * A fresh store sees the whole `newer_than` backlog as new mail - classifying it would flood
     * Telegram with day-old notifications. Instead the backlog is marked processed without
     * classification, and one informational alert reports the count so the user can eyeball
     * the inbox. Runs once per process: a first poll over an empty inbox must not leave the
     * store empty for the next poll to mis-seed real mail.
     */
    private fun seedBacklog(ids: List<String>): PollResult {
        for (id in ids) {
            store.recordProcessed(id, Decision(classification = null, notified = false, reason = "first-run seed"))
        }
        if (ids.isNotEmpty()) onSeed(ids.size)
        return PollResult(processed = ids.size, notified = 0, failed = 0)
    }

    /** Fetch -> classify (degrade to unclassified on failure) -> notify if warranted -> record. */
    private fun handle(id: String): Boolean {
        val email = gmail.fetchMessage(id)
        var failureReason: String? = null
        val classification =
            try {
                classifier.classify(email)
            } catch (failure: ClassifierException) {
                onError("classification failed for $id, delivering as unclassified", failure)
                failureReason = failure.message ?: "classification failed"
                null
            }
        val shouldNotify = classification == null || classification.importance == Importance.IMPORTANT
        if (shouldNotify) {
            notifier.notify(email, classification)
        }
        val reason = classification?.reason ?: failureReason ?: "classification failed"
        store.recordProcessed(id, Decision(classification, notified = shouldNotify, reason = reason))
        return shouldNotify
    }

    private companion object {
        val logger = LoggerFactory.getLogger(EmailProcessor::class.java)
    }
}
