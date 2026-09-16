package io.github.mshykhov.inboxwatcher.http

import org.http4k.client.JavaHttpClient
import org.http4k.core.HttpHandler
import java.net.http.HttpClient
import java.time.Duration

/**
 * Outbound HTTP client with connect + per-request timeouts, so a hung external call cannot
 * freeze the single-threaded poll loop (zero-miss). The http4k client maps a timeout to 504,
 * which adapters handle like other unsuccessful responses.
 */
fun defaultHttpHandler(requestTimeoutSeconds: Long = 30L): HttpHandler =
    JavaHttpClient(
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
        requestModifier = { it.timeout(Duration.ofSeconds(requestTimeoutSeconds)) },
    )
