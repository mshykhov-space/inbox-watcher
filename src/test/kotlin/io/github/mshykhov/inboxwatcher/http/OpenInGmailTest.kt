package io.github.mshykhov.inboxwatcher.http

import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.core.Status.Companion.OK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenInGmailTest {
    private val app = openInGmailRoute(accountEmail = "watched@example.com")

    @Test
    fun `serves a page with the app scheme and a pinned web fallback`() {
        val response = app(Request(GET, "/open/19f511371118af4e"))

        assertEquals(OK, response.status)
        assertTrue(response.header("content-type").orEmpty().startsWith("text/html"))
        val body = response.bodyString()
        assertTrue(body.contains("googlegmail:///cv=19f511371118af4e"), "gmail app scheme link: $body")
        assertTrue(
            body.contains("https://mail.google.com/mail/?authuser=watched%40example.com#all/19f511371118af4e"),
            "web fallback pinned to the watched account: $body",
        )
    }

    @Test
    fun `falls back to u0 web link when the account is unknown`() {
        val response = openInGmailRoute(accountEmail = null)(Request(GET, "/open/abc123"))

        assertTrue(response.bodyString().contains("https://mail.google.com/mail/u/0/#all/abc123"))
    }

    @Test
    fun `rejects a thread id that could inject markup`() {
        assertEquals(NOT_FOUND, app(Request(GET, "/open/<script>")).status)
    }

    @Test
    fun `rejects an empty thread id`() {
        assertEquals(NOT_FOUND, app(Request(GET, "/open/")).status)
    }
}
