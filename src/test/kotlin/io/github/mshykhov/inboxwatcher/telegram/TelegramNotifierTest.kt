package io.github.mshykhov.inboxwatcher.telegram

import io.github.mshykhov.inboxwatcher.core.Category
import io.github.mshykhov.inboxwatcher.core.Classification
import io.github.mshykhov.inboxwatcher.core.EmailMessage
import io.github.mshykhov.inboxwatcher.core.Importance
import io.github.mshykhov.inboxwatcher.core.NotifierException
import io.github.mshykhov.inboxwatcher.core.Urgency
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramNotifierTest {
    private val email =
        EmailMessage(
            id = "gmail-42",
            subject = "Interview with Example Company",
            from = "talent@example.com",
            body = "We'd like to schedule a 45-minute technical interview next week.",
        )

    private val classification =
        Classification(
            importance = Importance.IMPORTANT,
            urgency = Urgency.URGENT,
            category = Category.RECRUITER_INTERVIEW_REQUEST,
            summary = "Example Company wants to schedule a first interview.",
            reason = "Human recruiter proposes an interview",
            company = "Example Company",
            action = "Выберите время интервью",
        )

    private fun capturing(status: Status = Status.OK): Pair<HttpHandler, () -> Request?> {
        var captured: Request? = null
        val handler: HttpHandler = { req ->
            captured = req
            Response(status).body("""{"ok":true}""")
        }
        return handler to { captured }
    }

    private fun sentText(captured: () -> Request?): String {
        val body = requireNotNull(captured()).bodyString()
        return Json
            .parseToJsonElement(body)
            .jsonObject
            .getValue("text")
            .jsonPrimitive.content
    }

    @Test
    fun `sends a POST to the owner chat in html mode`() {
        val (handler, captured) = capturing()
        TelegramNotifier(botToken = "BOT:TOKEN", chatId = "9001", http = handler)
            .notify(email, classification)

        val request = requireNotNull(captured())
        assertEquals(Method.POST, request.method)
        assertTrue(request.uri.toString().contains("/botBOT:TOKEN/sendMessage"))
        assertTrue(request.bodyString().contains("9001"), "must target the owner chat id")
        assertTrue(request.bodyString().contains("\"parse_mode\":\"HTML\""))
    }

    @Test
    fun `opens with a typed headline carrying the company`() {
        val (handler, captured) = capturing()
        TelegramNotifier(botToken = "t", chatId = "1", http = handler).notify(email, classification)

        val text = sentText(captured)
        assertTrue(
            text.contains("🗓 <b>Приглашение на интервью · Example Company</b>"),
            "headline = category label + company: $text",
        )
        assertTrue(
            text.indexOf("Приглашение на интервью") < text.indexOf("Example Company wants to schedule"),
            "headline precedes the summary",
        )
        assertTrue(
            text.contains("<blockquote expandable><b>Interview with Example Company</b>"),
            "subject opens the quote in bold - italic did not stand out: $text",
        )
    }

    @Test
    fun `puts the email body into an expandable quote`() {
        val (handler, captured) = capturing()
        TelegramNotifier(botToken = "t", chatId = "1", http = handler).notify(email, classification)

        val text = sentText(captured)
        assertTrue(text.contains("<blockquote expandable>"), text)
        assertTrue(text.contains("45-minute technical interview"), "quote carries the email content")
    }

    @Test
    fun `quote drops the reply trail url duplicates and ends with an ellipsis when cut`() {
        val noisyBody =
            "Hello! Thanks for your application. Please share your availability. Alex from " +
                "careers.example.com<https://careers.example.com/jobs>\n" +
                "________________________________\n" +
                "From: updates@example.com\n" +
                "Sent: 15 January 2026\n" +
                "To: Taylor Example\n" +
                "Original message " + "x".repeat(600)
        val (handler, captured) = capturing()
        TelegramNotifier(botToken = "t", chatId = "1", http = handler)
            .notify(email.copy(body = noisyBody), classification)

        val text = sentText(captured)
        assertTrue(text.contains("share your availability"), "message content stays: $text")
        assertFalse(text.contains("From: updates"), "quoted reply trail must be cut: $text")
        assertFalse(text.contains("https://careers.example.com/jobs"), "angle-bracketed url duplicates must go: $text")
        assertTrue(text.contains("careers.example.com"), "plain text around the url duplicate survives")
    }

    @Test
    fun `long quote is truncated with an ellipsis`() {
        val (handler, captured) = capturing()
        TelegramNotifier(botToken = "t", chatId = "1", http = handler)
            .notify(email.copy(body = "слово ".repeat(200)), classification)

        val text = sentText(captured)
        assertTrue(text.contains("…</blockquote>"), "truncated quote must end with an ellipsis: $text")
    }

    @Test
    fun `links to the thread and to a from-sender search`() {
        val (handler, captured) = capturing()
        TelegramNotifier(botToken = "t", chatId = "1", http = handler)
            .notify(email.copy(threadId = "thread-7"), classification)

        val text = sentText(captured)
        assertTrue(
            text.contains("""<a href="https://mail.google.com/mail/u/0/#all/thread-7">Open</a>"""),
            "gmail web routes by thread id - a reply's message id lands on the wrong view: $text",
        )
        assertTrue(
            text.contains("""<a href="https://mail.google.com/mail/u/0/#search/from%3Atalent%40example.com">Similar</a>"""),
            "sender search lets the phone find the mail when the direct link only opens the inbox: $text",
        )
    }

    @Test
    fun `open link goes through the device-aware redirect when the public base url is configured`() {
        val (handler, captured) = capturing()
        TelegramNotifier(
            botToken = "t",
            chatId = "1",
            http = handler,
            publicBaseUrl = "https://mail.example.test",
        ).notify(email.copy(threadId = "thread-7"), classification)

        val text = sentText(captured)
        assertTrue(
            text.contains("""<a href="https://mail.example.test/open/thread-7">Open</a>"""),
            "the redirect page picks app or web by device, so the push needs a single link: $text",
        )
        assertFalse(
            text.contains("#all/thread-7"),
            "the direct web link duplicates the redirect and clutters the push: $text",
        )
    }

    @Test
    fun `sender search uses the bare address from a display-name From`() {
        val (handler, captured) = capturing()
        TelegramNotifier(botToken = "t", chatId = "1", http = handler)
            .notify(email.copy(from = "Example Sender <updates@example.com>"), classification)

        val text = sentText(captured)
        assertTrue(
            text.contains("#search/from%3Aupdates%40example.com"),
            "display name must not leak into the from: query: $text",
        )
    }

    @Test
    fun `pins the watched account in links when known`() {
        val (handler, captured) = capturing()
        TelegramNotifier(botToken = "t", chatId = "1", http = handler, accountEmail = "watched@example.com")
            .notify(email, classification)

        val text = sentText(captured)
        assertTrue(
            text.contains("https://mail.google.com/mail/?authuser=watched%40example.com#all/gmail-42"),
            "u/0 opens whichever account the browser logged in first; an email in the /u/ slot " +
                "renders Gmail's Temporary Error page - authuser query pins the mailbox: $text",
        )
    }

    @Test
    fun `transactional gets its own headline`() {
        val (handler, captured) = capturing()
        TelegramNotifier(botToken = "t", chatId = "1", http = handler)
            .notify(
                email,
                classification.copy(
                    category = Category.TRANSACTIONAL,
                    urgency = Urgency.NOT_URGENT,
                    company = "Example Travel",
                ),
            )

        val text = sentText(captured)
        assertTrue(text.contains("🧾 <b>Транзакция · Example Travel</b>"), "own bookings/payments get their own headline: $text")
    }

    @Test
    fun `personal informational mail has its own headline without urgency or action`() {
        val (handler, captured) = capturing()
        TelegramNotifier(botToken = "t", chatId = "1", http = handler).notify(
            email,
            classification.copy(category = Category.PERSONAL, urgency = Urgency.NOT_URGENT, company = null, action = null),
        )
        val text = sentText(captured)
        assertTrue(text.contains("💬 <b>Личное письмо</b>"))
        assertFalse(text.contains("❗"))
        assertFalse(text.contains("➡️"))
    }

    @Test
    fun `ai-news gets its own headline`() {
        val (handler, captured) = capturing()
        TelegramNotifier(botToken = "t", chatId = "1", http = handler)
            .notify(
                email,
                classification.copy(
                    category = Category.AI_NEWS,
                    urgency = Urgency.NOT_URGENT,
                    company = "Example AI Service",
                ),
            )

        val text = sentText(captured)
        assertTrue(text.contains("🤖 <b>AI-анонс · Example AI Service</b>"), "ai-news must not masquerade as a recruiter push: $text")
    }

    @Test
    fun `carries no importance urgency or category noise`() {
        val (handler, captured) = capturing()
        TelegramNotifier(botToken = "t", chatId = "1", http = handler)
            .notify(email, classification.copy(urgency = Urgency.NOT_URGENT))

        val text = sentText(captured)
        assertFalse(text.contains("IMPORTANT"), text)
        assertFalse(text.contains("URGENT"), text)
        assertFalse(text.contains("recruiter-interview-request"), text)
    }

    @Test
    fun `marks an urgent email with a leading alert`() {
        val (handler, captured) = capturing()
        TelegramNotifier(botToken = "t", chatId = "1", http = handler).notify(email, classification)

        assertTrue(sentText(captured).startsWith("❗"), "urgent mail gets a single leading marker")
    }

    @Test
    fun `shows the concrete action extracted from recruiter mail`() {
        val (handler, captured) = capturing()
        TelegramNotifier(botToken = "t", chatId = "1", http = handler).notify(email, classification)

        val text = sentText(captured)
        assertTrue(text.contains("➡️ <b>Выберите время интервью</b>"), "push must say what to do: $text")
    }

    @Test
    fun `shows no action when the email requires nothing even if it is urgent`() {
        val (handler, captured) = capturing()
        TelegramNotifier(botToken = "t", chatId = "1", http = handler)
            .notify(email, classification.copy(action = null))

        assertFalse(sentText(captured).contains("➡️"))
    }

    @Test
    fun `shows a concrete action for urgent non-recruiter mail`() {
        val (handler, captured) = capturing()
        TelegramNotifier(botToken = "t", chatId = "1", http = handler)
            .notify(
                email,
                classification.copy(
                    category = Category.TRANSACTIONAL,
                    action = "Обновите способ оплаты",
                ),
            )

        val text = sentText(captured)
        assertTrue(text.contains("➡️ <b>Обновите способ оплаты</b>"), text)
    }

    @Test
    fun `escapes html-hostile metadata`() {
        val (handler, captured) = capturing()
        TelegramNotifier(botToken = "t", chatId = "1", http = handler)
            .notify(
                email.copy(from = "Example Sender <no-reply@example.com>", body = "1 < 2 & 3 > 2"),
                classification = null,
            )

        val text = sentText(captured)
        assertTrue(
            text.contains("Example Sender &lt;no-reply@example.com&gt;"),
            "angle brackets in From must be escaped or Telegram rejects the message: $text",
        )
        assertTrue(text.contains("1 &lt; 2 &amp; 3 &gt; 2"), "body excerpt must be escaped: $text")
    }

    @Test
    fun `unclassified notification keeps the sender and deep-link`() {
        val (handler, captured) = capturing()
        TelegramNotifier(botToken = "t", chatId = "1", http = handler)
            .notify(email, classification = null)

        val text = sentText(captured)
        assertTrue(text.contains("не классифицировано"), "must flag the degraded delivery: $text")
        assertTrue(text.contains("talent@example.com"), "sender is the only signal left when unclassified")
        assertTrue(text.contains("https://mail.google.com/mail/u/0/#all/gmail-42"))
    }

    @Test
    fun `throws on non-2xx response`() {
        val (handler, _) = capturing(status = Status.BAD_GATEWAY)

        assertFailsWith<NotifierException> {
            TelegramNotifier(botToken = "t", chatId = "1", http = handler).notify(email, classification)
        }
    }

    @Test
    fun `throws on transport failure`() {
        val handler: HttpHandler = { throw RuntimeException("connection reset") }

        assertFailsWith<NotifierException> {
            TelegramNotifier(botToken = "t", chatId = "1", http = handler).notify(email, classification)
        }
    }
}
