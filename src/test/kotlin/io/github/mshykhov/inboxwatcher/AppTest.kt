package io.github.mshykhov.inboxwatcher

import io.github.mshykhov.inboxwatcher.http.PipelineHealth
import io.github.mshykhov.inboxwatcher.http.healthApp
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Status.Companion.SERVICE_UNAVAILABLE
import kotlin.test.Test
import kotlin.test.assertEquals

class AppTest {
    private val minute = 60_000L

    @Test
    fun `health is ok while the poll loop runs on schedule`() {
        var now = 0L
        val health = PipelineHealth(staleAfterMillis = 10 * minute, now = { now })
        val app = healthApp(health)

        now = 9 * minute
        assertEquals(OK, app(Request(GET, "/health")).status)

        health.pollCompleted()
        now = 18 * minute
        assertEquals(OK, app(Request(GET, "/health")).status, "a completed poll resets the staleness window")
    }

    @Test
    fun `metrics endpoint exposes prometheus text format`() {
        val registry =
            io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
                io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT,
            )
        registry.counter("emails_processed_total", "category", "other", "notified", "false").increment()
        val app = healthApp(PipelineHealth(staleAfterMillis = Long.MAX_VALUE), registry)

        val response = app(Request(GET, "/metrics"))

        assertEquals(OK, response.status)
        val body = response.bodyString()
        kotlin.test.assertTrue(body.contains("emails_processed_total"), "scrape must expose registered counters: $body")
    }

    @Test
    fun `health turns unavailable when the poll loop stalls`() {
        var now = 0L
        val health = PipelineHealth(staleAfterMillis = 10 * minute, now = { now })
        val app = healthApp(health)

        now = 11 * minute
        assertEquals(
            SERVICE_UNAVAILABLE,
            app(Request(GET, "/health")).status,
            "a stalled poll loop must fail liveness so k8s restarts the pod",
        )
    }
}
