package io.github.mshykhov.inboxwatcher.core

import java.net.URLEncoder

/**
 * Gmail web URL builders. The account pin MUST be the `?authuser=` query param:
 * an email address in the `/u/` path slot (numeric index only) renders Gmail's
 * "Temporary Error" page instead of the mailbox.
 */
object GmailLinks {
    fun base(accountEmail: String?): String =
        accountEmail
            ?.let { "https://mail.google.com/mail/?authuser=${URLEncoder.encode(it, "UTF-8")}" }
            ?: "https://mail.google.com/mail/u/0/"

    fun thread(
        accountEmail: String?,
        threadId: String,
    ): String = "${base(accountEmail)}#all/$threadId"
}
