package io.github.mshykhov.inboxwatcher.telegram

import io.github.mshykhov.inboxwatcher.core.Category
import io.github.mshykhov.inboxwatcher.core.Classification
import io.github.mshykhov.inboxwatcher.core.EmailMessage
import io.github.mshykhov.inboxwatcher.core.GmailLinks
import io.github.mshykhov.inboxwatcher.core.Notifier
import io.github.mshykhov.inboxwatcher.core.NotifierException
import io.github.mshykhov.inboxwatcher.core.Urgency
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import java.net.URLEncoder

/** Message canon: docs/telegram-notifications.md (typed headline, expandable body quote, HTML). */
class TelegramNotifier(
    private val botToken: String,
    private val chatId: String,
    private val http: HttpHandler,
    /** Watched mailbox; pins Gmail web links to the right account (см. GmailLinks). */
    private val accountEmail: String? = null,
    /** Public base of this service; adds the redirect link that can jump into the Gmail app. */
    private val publicBaseUrl: String? = null,
) : Notifier {
    override fun notify(
        email: EmailMessage,
        classification: Classification?,
    ) {
        val request =
            Request(Method.POST, "$BASE_URL/bot$botToken/sendMessage")
                .header("content-type", "application/json")
                .body(
                    buildJsonObject {
                        put("chat_id", chatId)
                        put("text", format(email, classification))
                        put("parse_mode", "HTML")
                        putJsonObject("link_preview_options") { put("is_disabled", true) }
                    }.toString(),
                )
        val response =
            try {
                http(request)
            } catch (failure: Exception) {
                throw NotifierException("telegram transport failure", failure)
            }
        if (!response.status.successful) {
            throw NotifierException("telegram returned ${response.status}")
        }
    }

    private fun format(
        email: EmailMessage,
        classification: Classification?,
    ): String {
        val header =
            if (classification == null) {
                "⚠️ <b>Письмо не классифицировано</b>\n${escape(email.from)}"
            } else {
                val (icon, label) = headline(classification.category)
                val title = classification.company?.let { "$label · ${escape(it)}" } ?: label
                val urgent = if (classification.urgency == Urgency.URGENT) "❗" else ""
                val action = actionLine(classification)
                "$urgent$icon <b>$title</b>$action\n${escape(classification.summary)}"
            }
        // The subject opens the quote (bold) instead of sitting under the headline - it blended in.
        val quoteBody = excerpt(email.body)?.let { "\n$it" }.orEmpty()
        val quote = "<blockquote expandable><b>${escape(email.subject)}</b>$quoteBody</blockquote>"
        return "$header\n$quote\n${links(email)}"
    }

    private fun headline(category: Category): Pair<String, String> =
        when (category) {
            Category.RECRUITER_INTERVIEW_REQUEST -> "🗓" to "Приглашение на интервью"
            Category.RECRUITER_REJECTION -> "❌" to "Отказ"
            Category.RECRUITER_GENERIC -> "✉️" to "Письмо рекрутёра"
            Category.AI_NEWS -> "🤖" to "AI-анонс"
            Category.TRANSACTIONAL -> "🧾" to "Транзакция"
            Category.OTHER -> "📌" to "Требуется действие"
        }

    private fun actionLine(classification: Classification): String =
        classification.action
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { "\n➡️ <b>${escape(it)}</b>" }
            .orEmpty()

    private fun links(email: EmailMessage): String {
        val threadId = email.threadId ?: email.id
        // Страница /open сама выбирает приложение или веб по устройству, пушу хватает одной
        // ссылки; прямой веб-линк - только когда публичного redirect-хоста нет.
        val openUrl =
            publicBaseUrl?.let { "$it/open/$threadId" }
                ?: GmailLinks.thread(accountEmail, threadId)
        val open = "<a href=\"$openUrl\">Open</a>"
        // Поиск по отправителю, не rfc822msgid: телефон теряет #-фрагмент и открывает голый
        // инбокс, оттуда письмо находят руками - адрес отправителя ищется, Message-ID нет.
        val similar =
            senderAddress(email.from)
                ?.let { " · <a href=\"${GmailLinks.base(accountEmail)}#search/from%3A${URLEncoder.encode(it, "UTF-8")}\">Similar</a>" }
                .orEmpty()
        return open + similar
    }

    private fun senderAddress(from: String): String? {
        val bracketed = ADDRESS_IN_BRACKETS.find(from)?.groupValues?.get(1)
        return (bracketed ?: from).trim().takeIf { it.contains("@") }
    }

    /**
     * Collapsed preview of the email itself, so the push is readable without opening Gmail.
     * Cuts the quoted reply trail and inline `<url>` duplicates - real recruiter mail buries
     * two useful sentences under a signature and the quoted original.
     */
    private fun excerpt(body: String): String? {
        val ownText = body.split(REPLY_TRAIL).first()
        val cleaned =
            ownText
                .replace(BRACKETED_URL, "")
                .replace(HTML_TAG, " ")
                .replace(WHITESPACE, " ")
                .trim()
        if (cleaned.isEmpty()) return null
        val cut = cleaned.take(QUOTE_LIMIT)
        val suffix = if (cleaned.length > QUOTE_LIMIT) "…" else ""
        return escape(cut) + suffix
    }

    /** Telegram HTML mode rejects unescaped <>& - email From routinely contains "Name <addr>". */
    private fun escape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private companion object {
        const val BASE_URL = "https://api.telegram.org"
        const val QUOTE_LIMIT = 500
        val REPLY_TRAIL =
            Regex(
                "(_{8,}|-{4,} ?Original Message ?-{4,}|^-- $|^(Від|От|From|Sent|Надіслано|Отправлено):" +
                    "|^On .{5,80} wrote:)",
                setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE),
            )
        val BRACKETED_URL = Regex("<https?://[^>]*>")
        val ADDRESS_IN_BRACKETS = Regex("<([^>]+)>")
        val HTML_TAG = Regex("</?[a-zA-Z][^>]{0,200}>")
        val WHITESPACE = Regex("\\s+")
    }
}
