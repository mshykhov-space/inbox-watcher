package io.github.mshykhov.inboxwatcher.config

import java.nio.file.Files
import java.nio.file.Path

/**
 * Merges configuration, lowest precedence first: `.env.example` -> `.env` -> process environment.
 * Local runs keep secrets in `.env` (git-ignored); process environment values take precedence.
 */
object EnvFiles {
    private val KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun load(
        root: String = ".",
        environment: Map<String, String> = System.getenv(),
    ): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        merged.putAll(parseFile(Path.of(root, ".env.example")))
        merged.putAll(parseFile(Path.of(root, ".env")))
        merged.putAll(environment)
        return merged
    }

    private fun parseFile(path: Path): Map<String, String> {
        if (!Files.exists(path)) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (raw in Files.readAllLines(path)) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val body = line.removePrefix("export ").trim()
            val separator = body.indexOf('=')
            if (separator <= 0) continue
            val key = body.substring(0, separator).trim()
            if (!KEY.matches(key)) continue
            result[key] = unquote(body.substring(separator + 1).trim())
        }
        return result
    }

    private fun unquote(value: String): String {
        val quoted =
            value.length >= 2 &&
                ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))
        return if (quoted) value.substring(1, value.length - 1) else value
    }
}
