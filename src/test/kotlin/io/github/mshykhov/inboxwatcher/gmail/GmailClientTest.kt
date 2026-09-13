package io.github.mshykhov.inboxwatcher.gmail

import io.github.mshykhov.inboxwatcher.core.GmailException
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GmailClientTest {
    private fun base64Url(text: String): String = Base64.getUrlEncoder().encodeToString(text.toByteArray())

    private fun messageJson(bodyText: String): String {
        val data = base64Url(bodyText)
        return """
            {
              "id": "gmail-1",
              "threadId": "thread-9",
              "payload": {
                "mimeType": "multipart/alternative",
                "headers": [
                  {"name": "Subject", "value": "Interview with Example Company"},
                  {"name": "From", "value": "Recruiter <talent@example.com>"},
                  {"name": "Message-ID", "value": "<abc@example.com>"}
                ],
                "parts": [
                  {"mimeType": "text/plain", "body": {"data": "$data"}}
                ]
              }
            }
            """.trimIndent()
    }

    /** Routes token/list/get by URL; records requests for assertions. */
    private class FakeGmail(
        private val listJson: String,
        private val getJson: String,
    ) {
        val requests = mutableListOf<Request>()
        var tokenCalls = 0

        val handler: HttpHandler = { req ->
            requests += req
            val uri = req.uri.toString()
            when {
                uri.contains("oauth2.googleapis.com/token") -> {
                    tokenCalls++
                    Response(Status.OK).body("""{"access_token":"AT-123","expires_in":3600}""")
                }
                uri.contains("/messages/") -> Response(Status.OK).body(getJson)
                uri.contains("/messages") -> Response(Status.OK).body(listJson)
                else -> Response(Status.NOT_FOUND)
            }
        }
    }

    private fun client(fake: FakeGmail) =
        GmailClient(
            clientId = "cid",
            clientSecret = "csecret",
            refreshToken = "rtoken",
            http = fake.handler,
        )

    @Test
    fun `lists recent message ids`() {
        val fake = FakeGmail(listJson = """{"messages":[{"id":"gmail-1"},{"id":"gmail-2"}]}""", getJson = "{}")

        val ids = client(fake).listRecentMessageIds()

        assertEquals(listOf("gmail-1", "gmail-2"), ids)
    }

    @Test
    fun `empty inbox yields no ids`() {
        val fake = FakeGmail(listJson = "{}", getJson = "{}")

        assertTrue(client(fake).listRecentMessageIds().isEmpty())
    }

    @Test
    fun `list uses a recency query and a bearer token`() {
        val fake = FakeGmail(listJson = """{"messages":[]}""", getJson = "{}")

        client(fake).listRecentMessageIds()

        val listRequest = fake.requests.first { it.uri.toString().contains("/messages") && !it.uri.toString().contains("token") }
        assertTrue(listRequest.uri.toString().contains("newer_than"), "must scope the list query by recency")
        assertEquals("Bearer AT-123", listRequest.header("Authorization"))
    }

    @Test
    fun `list query excludes drafts and user-sent mail`() {
        val fake = FakeGmail(listJson = """{"messages":[]}""", getJson = "{}")

        client(fake).listRecentMessageIds()

        val listRequest = fake.requests.first { it.uri.toString().contains("/messages") && !it.uri.toString().contains("token") }
        val query = listRequest.uri.toString()
        assertTrue(
            query.contains(encode("-in:draft")),
            "draft autosaves get fresh message ids and dodge dedup - must be excluded server-side",
        )
        assertTrue(query.contains(encode("-from:me")), "user-sent mail must never reach the classifier")
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    @Test
    fun `fetches and decodes a message`() {
        val fake = FakeGmail(listJson = "{}", getJson = messageJson("We would like to schedule an interview."))

        val email = client(fake).fetchMessage("gmail-1")

        assertEquals("gmail-1", email.id)
        assertEquals("thread-9", email.threadId, "threadId drives the Gmail deep-link - replies live in another thread")
        assertEquals("Interview with Example Company", email.subject)
        assertEquals("Recruiter <talent@example.com>", email.from)
        assertTrue(email.body.contains("schedule an interview"), "must base64url-decode the body")
    }

    @Test
    fun `html-only message body is stripped to readable text`() {
        val html =
            """
            <!doctype html><!--[if !mso]><!--><!--<![endif]-->
            <html><head><style>#outlook a { padding:0; } body { margin:0;padding:0; }</style></head>
            <body><!--[if mso]> 96 <![endif]-->
            <p>Your Max subscription is <b>confirmed</b>.</p>
            <p>Thanks &amp; welcome!</p>
            </body></html>
            """.trimIndent()
        val getJson =
            """
            {
              "id": "gmail-1",
              "threadId": "thread-9",
              "payload": {
                "mimeType": "text/html",
                "headers": [
                  {"name": "Subject", "value": "Receipt"},
                  {"name": "From", "value": "Example Video <receipts@example.com>"}
                ],
                "body": {"data": "${base64Url(html)}"}
              }
            }
            """.trimIndent()
        val fake = FakeGmail(listJson = "{}", getJson = getJson)

        val body = client(fake).fetchMessage("gmail-1").body

        assertTrue(body.contains("Your Max subscription is confirmed."), "keeps the readable text: $body")
        assertTrue(body.contains("Thanks & welcome!"), "decodes html entities: $body")
        assertTrue(!body.contains("<") && !body.contains("padding"), "no tags, comments or css: $body")
    }

    @Test
    fun `refreshes the access token only once across calls`() {
        val fake = FakeGmail(listJson = """{"messages":[{"id":"gmail-1"}]}""", getJson = messageJson("body"))
        val gmail = client(fake)

        gmail.listRecentMessageIds()
        gmail.fetchMessage("gmail-1")

        assertEquals(1, fake.tokenCalls)
    }

    @Test
    fun `fetches the profile email address`() {
        val handler: HttpHandler = { req ->
            when {
                req.uri.toString().contains("token") ->
                    Response(Status.OK).body("""{"access_token":"AT","expires_in":3600}""")
                req.uri.toString().contains("/profile") ->
                    Response(Status.OK).body("""{"emailAddress":"watched@example.com"}""")
                else -> Response(Status.NOT_FOUND)
            }
        }
        val gmail = GmailClient(clientId = "c", clientSecret = "s", refreshToken = "r", http = handler)

        assertEquals("watched@example.com", gmail.profileEmail())
    }

    @Test
    fun `throws on a gmail error response`() {
        val handler: HttpHandler = { req ->
            if (req.uri.toString().contains("token")) {
                Response(Status.OK).body("""{"access_token":"AT","expires_in":3600}""")
            } else {
                Response(Status.UNAUTHORIZED)
            }
        }
        val gmail = GmailClient(clientId = "c", clientSecret = "s", refreshToken = "r", http = handler)

        assertFailsWith<GmailException> { gmail.listRecentMessageIds() }
    }

    @Test
    fun `throws on transport failure`() {
        val handler: HttpHandler = { throw RuntimeException("connection reset") }
        val gmail = GmailClient(clientId = "c", clientSecret = "s", refreshToken = "r", http = handler)

        assertFailsWith<GmailException> { gmail.listRecentMessageIds() }
    }
}
