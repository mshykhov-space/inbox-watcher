package io.github.mshykhov.inboxwatcher.core

/** Reads new inbox mail from Gmail. Throws [GmailException] on any failure so the poll loop can alert. */
interface GmailGateway {
    /** Gmail message ids of recent inbox mail (server-side `newer_than` window). */
    fun listRecentMessageIds(): List<String>

    fun fetchMessage(id: String): EmailMessage

    /** Address of the watched mailbox - pins deep-links to the right account in multi-login browsers. */
    fun profileEmail(): String
}

class GmailException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
