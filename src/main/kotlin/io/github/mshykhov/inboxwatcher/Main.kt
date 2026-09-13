package io.github.mshykhov.inboxwatcher

import io.github.mshykhov.inboxwatcher.config.RuntimeConfig
import java.util.concurrent.CountDownLatch

fun main() {
    val config = RuntimeConfig.fromEnvironment()
    val app =
        InboxWatcher(config).also { watcher ->
            Runtime.getRuntime().addShutdownHook(Thread(watcher::close))
        }
    app.start()
    CountDownLatch(1).await()
}
