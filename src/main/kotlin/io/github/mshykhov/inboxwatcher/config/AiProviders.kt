package io.github.mshykhov.inboxwatcher.config

import java.net.URI
import java.util.Locale

internal object AiProviders {
    private val defaults =
        mapOf(
            "gemini" to ("https://generativelanguage.googleapis.com/v1beta" to "gemini-2.5-flash"),
            "groq" to ("https://api.groq.com/openai/v1" to "openai/gpt-oss-20b"),
            "nvidia" to ("https://integrate.api.nvidia.com/v1" to "nvidia/nemotron-3.5-lightning-30b-a3b"),
            "cerebras" to ("https://api.cerebras.ai/v1" to "gpt-oss-120b"),
            "openai" to ("https://api.openai.com/v1" to null),
            "openrouter" to ("https://openrouter.ai/api/v1" to null),
            "anthropic" to ("https://api.anthropic.com/v1" to null),
            "ollama" to ("http://localhost:11434/v1" to null),
        )

    fun fromMap(values: Map<String, String>): List<AiProviderConfig> {
        val explicit = values["AI_PROVIDERS"]?.trim()?.takeIf { it.isNotEmpty() }
        val names =
            explicit?.split(',')?.map { it.trim().lowercase(Locale.ROOT) }
                ?: buildList {
                    if (booleanOf(values, "GEMINI_ENABLED", !values["GEMINI_API_KEY"].isNullOrBlank())) add("gemini")
                    if (booleanOf(values, "CEREBRAS_ENABLED", false)) add("cerebras")
                    if (!values["GROQ_API_KEY"].isNullOrBlank()) add("groq")
                }
        if (names.isEmpty()) throw ConfigException("No AI providers configured: set AI_PROVIDERS and provider settings")
        if (names.any { !it.matches(Regex("[a-z][a-z0-9_]*")) } || names.distinct().size != names.size) {
            throw ConfigException("AI_PROVIDERS must contain unique comma-separated names using letters, digits and underscores")
        }
        return names.map { provider(it, values) }
    }

    private fun provider(
        name: String,
        values: Map<String, String>,
    ): AiProviderConfig {
        val prefix = name.uppercase(Locale.ROOT)

        fun setting(suffix: String): String? = values["${prefix}_$suffix"]?.trim()?.takeIf { it.isNotEmpty() }

        fun required(suffix: String): String = setting(suffix) ?: throw ConfigException("${prefix}_$suffix is required")

        val protocol =
            setting("API_TYPE")?.let { raw ->
                AiProtocol.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                    ?: throw ConfigException("${prefix}_API_TYPE must be openai, gemini or anthropic")
            } ?: when (name) {
                "gemini" -> AiProtocol.GEMINI
                "anthropic" -> AiProtocol.ANTHROPIC
                else -> AiProtocol.OPENAI
            }
        val baseUrl = (setting("BASE_URL") ?: defaults[name]?.first ?: required("BASE_URL")).trimEnd('/')
        val uri = runCatching { URI(baseUrl) }.getOrNull()
        if (uri == null ||
            uri.scheme !in setOf("http", "https") ||
            uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null ||
            uri.rawQuery != null ||
            uri.rawFragment != null
        ) {
            throw ConfigException("${prefix}_BASE_URL must be an absolute HTTP(S) API base URL without credentials, query or fragment")
        }
        val model = setting("MODEL") ?: defaults[name]?.second ?: required("MODEL")
        if (protocol == AiProtocol.GEMINI && !model.matches(Regex("[A-Za-z0-9._-]+"))) {
            throw ConfigException("${prefix}_MODEL must be a Gemini model ID without a path")
        }
        val apiKey = setting("API_KEY")
        val authRequired = booleanOf(values, "${prefix}_AUTH_REQUIRED", name != "ollama")
        if (apiKey == null && authRequired) required("API_KEY")
        val legacyReasoning = name in setOf("groq", "cerebras") && model == defaults[name]?.second
        val strictJson = legacyReasoning || (name == "nvidia" && model == defaults[name]?.second)
        val format =
            setting("RESPONSE_FORMAT")?.let { raw ->
                AiResponseFormat.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                    ?: throw ConfigException("${prefix}_RESPONSE_FORMAT must be json_schema, json_object or none")
            } ?: if (strictJson) AiResponseFormat.JSON_SCHEMA else AiResponseFormat.JSON_OBJECT
        val effort = setting("REASONING_EFFORT") ?: if (legacyReasoning) "low" else null
        val maxTokens = setting("MAX_TOKENS")?.let { it.toIntOrNull() ?: 0 } ?: 1024
        if (maxTokens <= 0) throw ConfigException("${prefix}_MAX_TOKENS must be a positive integer")
        val thinking =
            (
                setting("THINKING") ?: if (name == "nvidia" &&
                    model == defaults[name]?.second
                ) {
                    "disabled"
                } else {
                    null
                }
            )?.lowercase(Locale.ROOT)
        if (thinking != null && (protocol != AiProtocol.OPENAI || thinking !in setOf("enabled", "disabled"))) {
            throw ConfigException("${prefix}_THINKING must be enabled or disabled and requires the openai API type")
        }
        return AiProviderConfig(
            name,
            protocol,
            apiKey,
            baseUrl,
            model,
            format,
            effort?.takeUnless { it == "none" },
            maxTokens,
            thinking,
        )
    }

    private fun booleanOf(
        values: Map<String, String>,
        key: String,
        default: Boolean,
    ): Boolean =
        values[key]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            it.toBooleanStrictOrNull() ?: throw ConfigException("$key must be true or false")
        } ?: default
}
