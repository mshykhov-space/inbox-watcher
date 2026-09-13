package io.github.mshykhov.inboxwatcher.state

import io.github.mshykhov.inboxwatcher.core.Category
import io.github.mshykhov.inboxwatcher.core.Classification
import io.github.mshykhov.inboxwatcher.core.Decision
import io.github.mshykhov.inboxwatcher.core.Importance
import io.github.mshykhov.inboxwatcher.core.Urgency
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqliteEmailStoreTest {
    private fun tempDb(): Path = createTempDirectory("inbox-watcher-state").resolve("state.db")

    private val decision =
        Decision(
            classification =
                Classification(
                    importance = Importance.IMPORTANT,
                    urgency = Urgency.URGENT,
                    category = Category.RECRUITER_INTERVIEW_REQUEST,
                    summary = "Interview invite from Example Company",
                    reason = "Human recruiter proposes an interview",
                    action = "Выберите время интервью",
                ),
            notified = true,
            reason = "Human recruiter proposes an interview",
        )

    @Test
    fun `unknown message is not processed`() {
        SqliteEmailStore(tempDb()).use { store ->
            assertFalse(store.isProcessed("msg-1"))
        }
    }

    @Test
    fun `recorded message is processed`() {
        SqliteEmailStore(tempDb()).use { store ->
            store.recordProcessed("msg-1", decision)

            assertTrue(store.isProcessed("msg-1"))
            assertFalse(store.isProcessed("msg-2"))
        }
    }

    @Test
    fun `processed state survives reopen`() {
        val db = tempDb()
        SqliteEmailStore(db).use { it.recordProcessed("msg-1", decision) }

        SqliteEmailStore(db).use { store ->
            assertTrue(store.isProcessed("msg-1"))
        }
    }

    @Test
    fun `recording the same message twice is idempotent`() {
        SqliteEmailStore(tempDb()).use { store ->
            store.recordProcessed("msg-1", decision)
            store.recordProcessed("msg-1", decision.copy(notified = false))

            assertTrue(store.isProcessed("msg-1"))
        }
    }

    @Test
    fun `records an unclassified decision`() {
        SqliteEmailStore(tempDb()).use { store ->
            store.recordProcessed("msg-1", Decision(classification = null, notified = true, reason = "all classifiers failed"))

            assertTrue(store.isProcessed("msg-1"))
        }
    }

    @Test
    fun `last processed timestamp is null on an empty database`() {
        SqliteEmailStore(tempDb()).use { store ->
            assertEquals(null, store.lastProcessedAtMillis())
        }
    }

    @Test
    fun `last processed timestamp grows with each recorded email`() {
        SqliteEmailStore(tempDb()).use { store ->
            store.recordProcessed("msg-1", decision)
            val afterFirst = store.lastProcessedAtMillis()
            store.recordProcessed("msg-2", decision)
            val afterSecond = store.lastProcessedAtMillis()

            assertTrue(afterFirst != null && afterFirst > 0, "timestamp must be set after a record")
            assertTrue(afterSecond != null && afterSecond >= afterFirst, "newest record wins")
        }
    }

    @Test
    fun `persists company action and reason for debugging`() {
        val db = tempDb()
        val classified =
            decision.copy(
                classification =
                    decision.classification?.copy(
                        company = "Example Company",
                        reason = "Named recruiter proposes a concrete interview slot",
                    ),
                reason = "Named recruiter proposes a concrete interview slot",
            )
        SqliteEmailStore(db).use { it.recordProcessed("msg-1", classified) }

        val row = selectRow(db, "msg-1")
        assertEquals("Example Company", row.getValue("company"))
        assertEquals("Выберите время интервью", row.getValue("action"))
        assertEquals("Named recruiter proposes a concrete interview slot", row.getValue("reason"))
    }

    @Test
    fun `adds action column to an existing database`() {
        val db = tempDb()
        DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { connection ->
            connection.createStatement().use {
                it.executeUpdate(
                    """
                    create table processed_emails (
                        message_id text primary key,
                        importance text,
                        urgency text,
                        category text,
                        summary text,
                        company text,
                        reason text not null,
                        notified integer not null,
                        processed_at_epoch_millis integer not null
                    )
                    """.trimIndent(),
                )
            }
        }

        SqliteEmailStore(db).use { it.recordProcessed("msg-1", decision) }

        assertEquals("Выберите время интервью", selectRow(db, "msg-1").getValue("action"))
    }

    @Test
    fun `persists the chain failure text as the reason of an unclassified decision`() {
        val db = tempDb()
        SqliteEmailStore(db).use {
            it.recordProcessed(
                "msg-1",
                Decision(classification = null, notified = true, reason = "all 3 classifiers failed: gemini returned 429"),
            )
        }

        assertEquals("all 3 classifiers failed: gemini returned 429", selectRow(db, "msg-1").getValue("reason"))
    }

    @Test
    fun `prune removes rows older than the retention window and keeps fresh ones`() {
        val db = tempDb()
        SqliteEmailStore(db).use { store ->
            store.recordProcessed("fresh", decision)
            insertRowWithTimestamp(db, "stale", System.currentTimeMillis() - 8L * 24 * 3_600_000)

            store.pruneOlderThanDays(7)

            assertTrue(store.isProcessed("fresh"), "rows inside the retention window must survive")
            assertFalse(store.isProcessed("stale"), "rows past the retention window must be pruned")
        }
    }

    private fun insertRowWithTimestamp(
        db: Path,
        messageId: String,
        epochMillis: Long,
    ) {
        DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { connection ->
            connection
                .prepareStatement(
                    "insert into processed_emails (message_id, reason, notified, processed_at_epoch_millis) values (?, '', 0, ?)",
                ).use { statement ->
                    statement.setString(1, messageId)
                    statement.setLong(2, epochMillis)
                    statement.executeUpdate()
                }
        }
    }

    private fun selectRow(
        db: Path,
        messageId: String,
    ): Map<String, String?> =
        DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { connection ->
            connection.prepareStatement("select company, action, reason from processed_emails where message_id = ?").use { statement ->
                statement.setString(1, messageId)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next(), "expected a row for $messageId")
                    mapOf(
                        "company" to rows.getString("company"),
                        "action" to rows.getString("action"),
                        "reason" to rows.getString("reason"),
                    )
                }
            }
        }
}
