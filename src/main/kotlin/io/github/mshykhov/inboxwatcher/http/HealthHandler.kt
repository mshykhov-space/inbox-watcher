package io.github.mshykhov.inboxwatcher.http

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Status.Companion.SERVICE_UNAVAILABLE
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.bind
import org.http4k.routing.routes

/**
 * Liveness signal of the poll loop. A dead or deadlocked scheduler thread produces no errors
 * at all - the only observable symptom is that polls stop completing, so /health reports
 * staleness and a process supervisor can restart the service.
 */
class PipelineHealth(
    private val staleAfterMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var lastPollAtMillis: Long = now()

    fun pollCompleted() {
        lastPollAtMillis = now()
    }

    fun isAlive(): Boolean = now() - lastPollAtMillis < staleAfterMillis
}

internal fun healthRoute(health: PipelineHealth): RoutingHttpHandler =
    "/health" bind GET to {
        if (health.isAlive()) Response(OK).body("ok") else Response(SERVICE_UNAVAILABLE).body("poll loop stalled")
    }

fun healthApp(
    health: PipelineHealth,
    registry: PrometheusMeterRegistry? = null,
    accountEmail: String? = null,
): HttpHandler =
    routes(
        listOfNotNull(
            healthRoute(health),
            registry?.let { meters -> "/metrics" bind GET to { Response(OK).body(meters.scrape()) } },
            openInGmailRoute(accountEmail),
        ),
    )
