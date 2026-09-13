package io.github.mshykhov.inboxwatcher.telegram

import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import kotlin.test.Test
import kotlin.test.assertTrue

class TelegramAlerterTest {
    @Test
    fun `posts a plain alert to the owner chat`() {
        var captured: Request? = null
        val handler: HttpHandler = { req ->
            captured = req
            Response(Status.OK)
        }
        TelegramAlerter(botToken = "t", chatId = "9001", http = handler).alert("gmail auth dead")

        val body = requireNotNull(captured).bodyString()
        assertTrue(body.contains("9001"))
        assertTrue(body.contains("gmail auth dead"))
    }

    @Test
    fun `never throws even when delivery fails`() {
        val handler: HttpHandler = { throw RuntimeException("network down") }

        // Must not propagate - this is the last-resort channel.
        TelegramAlerter(botToken = "t", chatId = "1", http = handler).alert("something broke")
    }
}
