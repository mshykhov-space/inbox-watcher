package io.github.mshykhov.inboxwatcher

import io.github.mshykhov.inboxwatcher.classifier.FallbackClassifier
import io.github.mshykhov.inboxwatcher.classifier.buildClassifierChain
import io.github.mshykhov.inboxwatcher.config.RuntimeConfig
import io.github.mshykhov.inboxwatcher.gmail.GmailClient
import io.github.mshykhov.inboxwatcher.http.PipelineHealth
import io.github.mshykhov.inboxwatcher.http.defaultHttpHandler
import io.github.mshykhov.inboxwatcher.http.healthApp
import io.github.mshykhov.inboxwatcher.metrics.ClassifierMetrics
import io.github.mshykhov.inboxwatcher.metrics.MeteredEmailStore
import io.github.mshykhov.inboxwatcher.pipeline.EmailProcessor
import io.github.mshykhov.inboxwatcher.pipeline.SilenceCanary
import io.github.mshykhov.inboxwatcher.state.SqliteEmailStore
import io.github.mshykhov.inboxwatcher.telegram.TelegramAlerter
import io.github.mshykhov.inboxwatcher.telegram.TelegramNotifier
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.http4k.core.HttpHandler
import org.http4k.server.Undertow
import org.http4k.server.asServer
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Composition root: wires the polling pipeline + health server and owns their lifecycle. */
class InboxWatcher(
    private val config: RuntimeConfig,
    private val http: HttpHandler = defaultHttpHandler(),
) : AutoCloseable {
    private val registry =
        PrometheusMeterRegistry(PrometheusConfig.DEFAULT).also {
            JvmMemoryMetrics().bindTo(it)
            ProcessorMetrics().bindTo(it)
        }
    private val sqlite = SqliteEmailStore(Path.of(config.stateDbPath))
    private val store = MeteredEmailStore(sqlite, registry)
    private val alerter = TelegramAlerter(config.telegramBotToken, config.telegramChatId, http)
    private val classifiers = buildClassifierChain(config, http, ClassifierMetrics(registry))
    private val gmail = GmailClient(config.googleClientId, config.googleClientSecret, config.googleRefreshToken, http)

    // Best-effort: pins deep-links to the watched mailbox; a boot without network falls back to /u/0.
    private val accountEmail: String? =
        runCatching { gmail.profileEmail() }
            .onFailure { logger.warn("could not resolve the watched mailbox address, links fall back to /u/0", it) }
            .getOrNull()
    private val processor =
        EmailProcessor(
            gmail = gmail,
            store = store,
            classifier = FallbackClassifier(classifiers),
            notifier = TelegramNotifier(config.telegramBotToken, config.telegramChatId, http, accountEmail, config.publicBaseUrl),
            onError = { message, cause ->
                logger.error(message, cause)
                alerter.alert("$message: ${cause.message}")
            },
            onSeed = { count ->
                logger.info("first run: seeded {} backlog emails without notifications", count)
                alerter.alert("первый запуск: $count писем за сутки помечены обработанными без уведомлений - глянь инбокс глазами")
            },
        )
    private val canary =
        SilenceCanary(
            thresholdHours = config.silenceAlertHours,
            lastProcessedAt = store::lastProcessedAtMillis,
            startedAtMillis = System.currentTimeMillis(),
            alert = alerter::alert,
        )

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val health = PipelineHealth(staleAfterMillis = config.pollIntervalSeconds * 5 * 1000)
    private val server = healthApp(health, registry, accountEmail).asServer(Undertow(config.httpPort))

    fun start(): InboxWatcher {
        server.start()
        scheduler.scheduleWithFixedDelay(::pollSafely, 0, config.pollIntervalSeconds, TimeUnit.SECONDS)
        scheduler.scheduleWithFixedDelay({ sqlite.pruneOlderThanDays(RETENTION_DAYS) }, 0, 24, TimeUnit.HOURS)
        logger.info(
            "inbox-watcher started: port={} pollEvery={}s classifiers={}",
            config.httpPort,
            config.pollIntervalSeconds,
            classifiers.size,
        )
        return this
    }

    private fun pollSafely() {
        try {
            val result = processor.pollOnce()
            if (result.processed > 0 || result.failed > 0) {
                logger.info("poll complete: {}", result)
            }
            registry.counter("polls_total").increment()
            if (result.failed > 0) registry.counter("poll_failures_total").increment(result.failed.toDouble())
            health.pollCompleted()
            canary.check()
        } catch (failure: Exception) {
            logger.error("poll cycle crashed", failure)
        }
    }

    override fun close() {
        scheduler.shutdownNow()
        server.stop()
        sqlite.close()
    }

    private companion object {
        val logger = LoggerFactory.getLogger(InboxWatcher::class.java)

        // Dedup needs the Gmail list window (1d); 7d keeps a week of decisions for debugging.
        const val RETENTION_DAYS = 7L
    }
}
