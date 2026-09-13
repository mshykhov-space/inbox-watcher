package io.github.mshykhov.inboxwatcher.pipeline

/**
 * Alerts when the inbox has produced no email for suspiciously long - the one failure mode
 * fail-loud error handling cannot see: everything "works", but nothing arrives (dead OAuth
 * grant, revoked scope). Silence is measured from the newest recorded email (survives
 * restarts via the store), or from service start on an empty store. Re-alerts once per
 * threshold period, not on every poll.
 */
class SilenceCanary(
    private val thresholdHours: Long,
    private val lastProcessedAt: () -> Long?,
    private val startedAtMillis: Long,
    private val alert: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var lastAlertAtMillis: Long? = null

    fun check() {
        if (thresholdHours <= 0) return
        val thresholdMillis = thresholdHours * 3_600_000
        val current = now()
        val silentSince = lastProcessedAt() ?: startedAtMillis
        if (current - silentSince < thresholdMillis) return
        val alreadyAlerted = lastAlertAtMillis
        if (alreadyAlerted != null && current - alreadyAlerted < thresholdMillis) return
        lastAlertAtMillis = current
        val silentHours = (current - silentSince) / 3_600_000
        alert("тишина ${silentHours}ч: ни одного нового письма - проверь OAuth/квоты Gmail")
    }
}
