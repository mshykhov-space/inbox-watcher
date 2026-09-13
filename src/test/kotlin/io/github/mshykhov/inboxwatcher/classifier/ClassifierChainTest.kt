package io.github.mshykhov.inboxwatcher.classifier

import io.github.mshykhov.inboxwatcher.config.RuntimeConfig
import org.http4k.core.Response
import org.http4k.core.Status
import kotlin.test.Test
import kotlin.test.assertEquals

class ClassifierChainTest {
    private val noopHttp: org.http4k.core.HttpHandler = { Response(Status.OK) }

    private fun config(
        geminiEnabled: Boolean = true,
        cerebrasEnabled: Boolean = false,
        cerebras: String? = null,
        groq: String? = null,
    ) = RuntimeConfig(
        googleClientId = "c",
        googleClientSecret = "s",
        googleRefreshToken = "r",
        geminiApiKey = "g",
        geminiEnabled = geminiEnabled,
        cerebrasEnabled = cerebrasEnabled,
        cerebrasApiKey = cerebras,
        groqApiKey = groq,
        telegramBotToken = "b",
        telegramChatId = "1",
        stateDbPath = "/tmp/x.db",
        httpPort = 8080,
        pollIntervalSeconds = 60,
        silenceAlertHours = 12,
        publicBaseUrl = null,
    )

    @Test
    fun `gemini only when no fallback keys`() {
        assertEquals(1, buildClassifierChain(config(), noopHttp).size)
    }

    @Test
    fun `skips cerebras by default even when its exhausted key is present`() {
        assertEquals(1, buildClassifierChain(config(cerebras = "ck"), noopHttp).size)
    }

    @Test
    fun `adds cerebras only when explicitly enabled`() {
        assertEquals(2, buildClassifierChain(config(cerebrasEnabled = true, cerebras = "ck"), noopHttp).size)
    }

    @Test
    fun `adds groq while leaving disabled cerebras out`() {
        assertEquals(2, buildClassifierChain(config(cerebras = "ck", groq = "qk"), noopHttp).size)
    }

    @Test
    fun `skips disabled gemini and keeps configured fallbacks`() {
        assertEquals(
            1,
            buildClassifierChain(config(geminiEnabled = false, cerebras = "ck", groq = "qk"), noopHttp).size,
        )
    }
}
