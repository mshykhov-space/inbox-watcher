package io.github.mshykhov.inboxwatcher.http

import com.sun.net.httpserver.HttpServer
import org.http4k.core.Method
import org.http4k.core.Request
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpClientFactoryTest {
    @Test
    fun `configured timeout bounds a stalled response`() {
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            release.await(5, TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        try {
            val response = defaultHttpHandler(requestTimeoutSeconds = 1)(Request(Method.GET, "http://127.0.0.1:${server.address.port}/"))
            assertEquals(504, response.status.code)
        } finally {
            release.countDown()
            server.stop(0)
        }
    }
}
