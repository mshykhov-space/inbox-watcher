package io.github.mshykhov.inboxwatcher.state

import io.github.mshykhov.inboxwatcher.core.Decision
import io.github.mshykhov.inboxwatcher.core.EmailStore
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class SqliteEmailStore(
    dbPath: Path,
) : EmailStore,
    AutoCloseable {
    private val connection: Connection

    init {
        val absolute = dbPath.toAbsolutePath()
        absolute.parent?.let { Files.createDirectories(it) }
        connection = DriverManager.getConnection("jdbc:sqlite:$absolute")
        initializeSchema()
    }

    private fun initializeSchema() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                create table if not exists processed_emails (
                    message_id text primary key,
                    importance text,
                    urgency text,
                    category text,
                    summary text,
                    company text,
                    action text,
                    reason text not null,
                    notified integer not null,
                    processed_at_epoch_millis integer not null
                )
                """.trimIndent(),
            )
        }
        ensureColumn("action", "text")
    }

    private fun ensureColumn(
        name: String,
        type: String,
    ) {
        val exists =
            connection.createStatement().use { statement ->
                statement.executeQuery("pragma table_info(processed_emails)").use { rows ->
                    var found = false
                    while (rows.next()) {
                        if (rows.getString("name") == name) found = true
                    }
                    found
                }
            }
        if (!exists) {
            connection.createStatement().use { it.executeUpdate("alter table processed_emails add column $name $type") }
        }
    }

    @Synchronized
    override fun isProcessed(messageId: String): Boolean {
        connection.prepareStatement("select 1 from processed_emails where message_id = ? limit 1").use { statement ->
            statement.setString(1, messageId)
            statement.executeQuery().use { rows -> return rows.next() }
        }
    }

    @Synchronized
    override fun recordProcessed(
        messageId: String,
        decision: Decision,
    ) {
        connection
            .prepareStatement(
                """
                insert or ignore into processed_emails
                    (message_id, importance, urgency, category, summary, company, action, reason, notified, processed_at_epoch_millis)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                val classification = decision.classification
                statement.setString(1, messageId)
                statement.setString(2, classification?.importance?.name)
                statement.setString(3, classification?.urgency?.name)
                statement.setString(4, classification?.category?.name)
                statement.setString(5, classification?.summary)
                statement.setString(6, classification?.company)
                statement.setString(7, classification?.action)
                statement.setString(8, decision.reason)
                statement.setInt(9, if (decision.notified) 1 else 0)
                statement.setLong(10, System.currentTimeMillis())
                statement.executeUpdate()
            }
    }

    /** Dedup only needs the Gmail list window (1d); retain a short history for troubleshooting. */
    @Synchronized
    fun pruneOlderThanDays(days: Long) {
        connection.prepareStatement("delete from processed_emails where processed_at_epoch_millis < ?").use { statement ->
            statement.setLong(1, System.currentTimeMillis() - days * 24 * 3_600_000)
            val pruned = statement.executeUpdate()
            if (pruned > 0) logger.info("pruned {} processed_emails rows older than {}d", pruned, days)
        }
    }

    @Synchronized
    override fun lastProcessedAtMillis(): Long? {
        connection.createStatement().use { statement ->
            statement.executeQuery("select max(processed_at_epoch_millis) as latest from processed_emails").use { rows ->
                if (!rows.next()) return null
                val latest = rows.getLong("latest")
                return if (rows.wasNull()) null else latest
            }
        }
    }

    @Synchronized
    override fun close() {
        connection.close()
    }

    private companion object {
        val logger = LoggerFactory.getLogger(SqliteEmailStore::class.java)
    }
}
