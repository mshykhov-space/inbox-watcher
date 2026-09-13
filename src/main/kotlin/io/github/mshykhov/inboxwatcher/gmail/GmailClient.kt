package io.github.mshykhov.inboxwatcher.gmail

import io.github.mshykhov.inboxwatcher.core.EmailMessage
import io.github.mshykhov.inboxwatcher.core.GmailException
import io.github.mshykhov.inboxwatcher.core.GmailGateway
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Base64

/**
 * Gmail polling client. Refreshes the OAuth access token from a long-lived refresh token and
 * caches it until near expiry. All calls throw [GmailException] on failure - the poll loop alerts
 * rather than swallowing (a silently dead Gmail auth is the top zero-miss risk).
 */
class GmailClient(
    private val clientId: String,
    private val clientSecret: String,
    private val refreshToken: String,
    private val http: HttpHandler,
    // -in:draft и -from:me: каждый автосейв черновика получает новый message id и проходит дедуп,
    // а тело драфта/реплая цитирует входящее письмо - классификатор принимает его за новое входящее.
    // Свою почту фильтруем на стороне Gmail, до LLM она доходить не должна.
    private val query: String = "newer_than:1d -in:draft -from:me",
) : GmailGateway {
    private val json = Json { ignoreUnknownKeys = true }
    private var accessToken: String? = null
    private var expiresAtMillis = 0L

    override fun listRecentMessageIds(): List<String> {
        val body = gmailGet("$GMAIL_BASE/messages?q=${encode(query)}")
        return decode<MessageListDto>(body, "message list").messages.map { it.id }
    }

    override fun fetchMessage(id: String): EmailMessage {
        val message = decode<GmailMessageDto>(gmailGet("$GMAIL_BASE/messages/$id?format=full"), "message")
        val payload = message.payload
        return EmailMessage(
            id = id,
            subject = header(payload, "Subject").orEmpty(),
            from = header(payload, "From").orEmpty(),
            body = extractBody(payload),
            threadId = message.threadId,
        )
    }

    override fun profileEmail(): String = decode<ProfileDto>(gmailGet("$GMAIL_BASE/profile"), "profile").emailAddress

    private fun gmailGet(url: String): String {
        val response = send(Request(Method.GET, url).header("Authorization", "Bearer ${accessToken()}"))
        if (!response.status.successful) {
            throw GmailException("gmail GET $url returned ${response.status}")
        }
        return response.bodyString()
    }

    private fun send(request: Request) =
        try {
            http(request)
        } catch (failure: Exception) {
            throw GmailException("gmail transport failure: ${request.uri}", failure)
        }

    @Synchronized
    private fun accessToken(): String {
        val now = System.currentTimeMillis()
        accessToken?.let { if (now < expiresAtMillis) return it }
        val form =
            "client_id=${encode(clientId)}&client_secret=${encode(clientSecret)}" +
                "&refresh_token=${encode(refreshToken)}&grant_type=refresh_token"
        val response =
            send(
                Request(Method.POST, TOKEN_URL)
                    .header("content-type", "application/x-www-form-urlencoded")
                    .body(form),
            )
        if (!response.status.successful) {
            throw GmailException("token refresh returned ${response.status}")
        }
        val token = decode<TokenResponse>(response.bodyString(), "token response")
        accessToken = token.accessToken
        expiresAtMillis = now + token.expiresIn * 1000 - TOKEN_SKEW_MILLIS
        return token.accessToken
    }

    private fun header(
        payload: PayloadDto?,
        name: String,
    ): String? = payload?.headers?.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

    private fun extractBody(payload: PayloadDto?): String {
        if (payload == null) return ""
        findPartData(payload, "text/plain")?.let { return decodeData(it) }
        // HTML-only письма (маркетинг, квитанции): сырой HTML замусоривает и цитату
        // в Telegram, и промпт классификатора - сводим к плоскому тексту.
        val fallback = findPartData(payload, "text/html") ?: payload.body?.data ?: return ""
        return htmlToText(decodeData(fallback))
    }

    private fun decodeData(data: String): String = String(Base64.getUrlDecoder().decode(data))

    private fun htmlToText(html: String): String = Jsoup.parse(html).text()

    private fun findPartData(
        payload: PayloadDto,
        mimeType: String,
    ): String? {
        if (payload.mimeType == mimeType && payload.body?.data != null) return payload.body.data
        for (part in payload.parts) {
            findPartData(part, mimeType)?.let { return it }
        }
        return null
    }

    private inline fun <reified T> decode(
        body: String,
        what: String,
    ): T =
        try {
            json.decodeFromString<T>(body)
        } catch (e: Exception) {
            throw GmailException("unparseable $what", e)
        }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val GMAIL_BASE = "https://gmail.googleapis.com/gmail/v1/users/me"
        const val TOKEN_SKEW_MILLIS = 60_000L
    }
}

@Serializable
private data class ProfileDto(
    val emailAddress: String,
)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long = 3600,
)

@Serializable
private data class MessageListDto(
    val messages: List<MessageRefDto> = emptyList(),
)

@Serializable
private data class MessageRefDto(
    val id: String,
)

@Serializable
private data class GmailMessageDto(
    val id: String = "",
    val threadId: String? = null,
    val payload: PayloadDto? = null,
)

@Serializable
private data class PayloadDto(
    val mimeType: String? = null,
    val headers: List<HeaderDto> = emptyList(),
    val body: BodyDto? = null,
    val parts: List<PayloadDto> = emptyList(),
)

@Serializable
private data class HeaderDto(
    val name: String,
    val value: String = "",
)

@Serializable
private data class BodyDto(
    val data: String? = null,
)
