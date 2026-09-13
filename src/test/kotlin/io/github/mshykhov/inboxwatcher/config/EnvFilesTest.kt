package io.github.mshykhov.inboxwatcher.config

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnvFilesTest {
    @Test
    fun `process env overrides dotenv overrides example`() {
        val dir = createTempDirectory("inbox-watcher-env")
        dir.resolve(".env.example").writeText("A=from-example\nB=from-example\nC=from-example\n")
        dir.resolve(".env").writeText("B=from-dotenv\nC=from-dotenv\n")

        val merged = EnvFiles.load(dir.toString(), environment = mapOf("C" to "from-process"))

        assertEquals("from-example", merged["A"])
        assertEquals("from-dotenv", merged["B"])
        assertEquals("from-process", merged["C"])
    }

    @Test
    fun `ignores blanks comments and strips quotes and export`() {
        val dir = createTempDirectory("inbox-watcher-env")
        dir.resolve(".env").writeText(
            """
            # a comment

            export TOKEN="secret value"
            NAME='alice'
            """.trimIndent(),
        )

        val merged = EnvFiles.load(dir.toString(), environment = emptyMap())

        assertEquals("secret value", merged["TOKEN"])
        assertEquals("alice", merged["NAME"])
        assertNull(merged["# a comment"])
    }

    @Test
    fun `works when no dotenv files exist`() {
        val dir = createTempDirectory("inbox-watcher-env-empty")

        val merged = EnvFiles.load(dir.toString(), environment = mapOf("X" to "y"))

        assertEquals("y", merged["X"])
    }
}
