package io.github.mshykhov.inboxwatcher.telegram

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.slf4j.LoggerFactory

/** Best-effort plain-text alert channel for pipeline failures. Never throws - it is the last resort. */
class TelegramAlerter(
    private val botToken: String,
    private val chatId: String,
    private val http: HttpHandler,
) {
    fun alert(text: String) {
        try {
            http(
                Request(Method.POST, "https://api.telegram.org/bot$botToken/sendMessage")
                    .header("content-type", "application/json")
                    .body(
                        buildJsonObject {
                            put("chat_id", chatId)
                            put("text", "⚠️ inbox-watcher: $text")
                        }.toString(),
                    ),
            )
        } catch (failure: Exception) {
            logger.error("failed to send telegram alert: {}", text, failure)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(TelegramAlerter::class.java)
    }
}
