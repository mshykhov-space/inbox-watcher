package io.github.mshykhov.inboxwatcher.core

/**
 * A fetched inbox email. The deep-link routes by [threadId] (Gmail web navigates threads;
 * a reply's message id points at the wrong view), falling back to [id].
 */
data class EmailMessage(
    val id: String,
    val subject: String,
    val from: String,
    val body: String,
    val threadId: String? = null,
)
