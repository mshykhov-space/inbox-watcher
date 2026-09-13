package io.github.mshykhov.inboxwatcher.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals

class SilenceCanaryTest {
    private val hour = 3_600_000L

    private class Recording {
        val alerts = mutableListOf<String>()

        fun alert(text: String) {
            alerts += text
        }
    }

    private fun canary(
        recording: Recording,
        thresholdHours: Long = 12,
        lastProcessedAt: () -> Long? = { null },
        startedAt: Long = 0L,
        now: () -> Long,
    ) = SilenceCanary(
        thresholdHours = thresholdHours,
        lastProcessedAt = lastProcessedAt,
        startedAtMillis = startedAt,
        alert = recording::alert,
        now = now,
    )

    @Test
    fun `stays quiet while the silence is under the threshold`() {
        val recording = Recording()
        val canary = canary(recording, now = { 11 * hour })

        canary.check()

        assertEquals(emptyList(), recording.alerts)
    }

    @Test
    fun `alerts once the inbox has been silent past the threshold`() {
        val recording = Recording()
        val canary = canary(recording, now = { 13 * hour })

        canary.check()

        assertEquals(1, recording.alerts.size)
    }

    @Test
    fun `does not repeat the alert on every poll`() {
        val recording = Recording()
        var now = 13 * hour
        val canary = canary(recording, now = { now })

        canary.check()
        now += hour
        canary.check()

        assertEquals(1, recording.alerts.size, "one alert per silence period, polls run every 90s")
    }

    @Test
    fun `alerts again when the silence lasts another full threshold`() {
        val recording = Recording()
        var now = 13 * hour
        val canary = canary(recording, now = { now })

        canary.check()
        now = 26 * hour
        canary.check()

        assertEquals(2, recording.alerts.size, "day-long silence must keep nagging")
    }

    @Test
    fun `fresh email resets the silence window`() {
        val recording = Recording()
        var lastSeen: Long? = null
        var now = 13 * hour
        val canary = canary(recording, lastProcessedAt = { lastSeen }, now = { now })

        canary.check()
        lastSeen = now
        now += 13 * hour
        canary.check()

        assertEquals(2, recording.alerts.size, "silence is measured from the newest processed email")
        lastSeen = now
        now += hour
        canary.check()
        assertEquals(2, recording.alerts.size, "no alert within the threshold after fresh activity")
    }

    @Test
    fun `threshold zero disables the canary`() {
        val recording = Recording()
        val canary = canary(recording, thresholdHours = 0, now = { 1_000 * hour })

        canary.check()

        assertEquals(emptyList(), recording.alerts)
    }

    @Test
    fun `empty database measures silence from service start`() {
        val recording = Recording()
        val canary = canary(recording, startedAt = 100 * hour, now = { 111 * hour })

        canary.check()

        assertEquals(emptyList(), recording.alerts, "11h since start is under the 12h threshold")
    }
}
