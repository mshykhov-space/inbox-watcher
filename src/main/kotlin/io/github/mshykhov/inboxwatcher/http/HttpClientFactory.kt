package io.github.mshykhov.inboxwatcher.http

import org.http4k.client.JavaHttpClient
import org.http4k.core.HttpHandler
import java.net.http.HttpClient
import java.time.Duration

/**
 * Outbound HTTP client with connect + per-request timeouts, so a hung external call cannot
 * freeze the single-threaded poll loop (zero-miss). Transport failures surface as exceptions,
 * which each client wraps into its domain exception.
 */
fun defaultHttpHandler(): HttpHandler =
    JavaHttpClient(
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
        requestModifier = { it.timeout(Duration.ofSeconds(30)) },
    )
