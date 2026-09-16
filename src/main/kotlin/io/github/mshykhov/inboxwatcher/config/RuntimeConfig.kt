package io.github.mshykhov.inboxwatcher.config

data class RuntimeConfig(
    val googleClientId: String,
    val googleClientSecret: String,
    val googleRefreshToken: String,
    val aiProviders: List<AiProviderConfig>,
    val telegramBotToken: String,
    val telegramChatId: String,
    val stateDbPath: String,
    val httpPort: Int,
    val pollIntervalSeconds: Long,
    /** Alert when no email arrived for this many hours; 0 disables the canary. */
    val silenceAlertHours: Long,
    /** Public base of this service (e.g. https://mail.example.test); enables the app redirect link. */
    val publicBaseUrl: String?,
    val aiRequestTimeoutSeconds: Long = 30L,
) {
    companion object {
        fun fromEnvironment(
            root: String = ".",
            environment: Map<String, String> = System.getenv(),
        ): RuntimeConfig = fromMap(EnvFiles.load(root, environment))

        fun fromMap(values: Map<String, String>): RuntimeConfig {
            val required =
                listOf(
                    "GOOGLE_CLIENT_ID",
                    "GOOGLE_CLIENT_SECRET",
                    "GOOGLE_REFRESH_TOKEN",
                    "TELEGRAM_BOT_TOKEN",
                    "TELEGRAM_CHAT_ID",
                )
            val missing = required.filter { values[it].isNullOrBlank() }
            if (missing.isNotEmpty()) {
                throw ConfigException("Missing required env vars: ${missing.joinToString(", ")}")
            }
            return RuntimeConfig(
                googleClientId = values.getValue("GOOGLE_CLIENT_ID"),
                googleClientSecret = values.getValue("GOOGLE_CLIENT_SECRET"),
                googleRefreshToken = values.getValue("GOOGLE_REFRESH_TOKEN"),
                aiProviders = AiProviders.fromMap(values),
                telegramBotToken = values.getValue("TELEGRAM_BOT_TOKEN"),
                telegramChatId = values.getValue("TELEGRAM_CHAT_ID"),
                stateDbPath = values["STATE_DB_PATH"]?.ifBlank { null } ?: "/state/inbox-watcher.db",
                httpPort = intOf(values, "HTTP_PORT", 8080),
                pollIntervalSeconds = longOf(values, "POLL_INTERVAL_SECONDS", 60L),
                silenceAlertHours = longOf(values, "SILENCE_ALERT_HOURS", 12L),
                publicBaseUrl = values["PUBLIC_BASE_URL"]?.ifBlank { null }?.trimEnd('/'),
                aiRequestTimeoutSeconds =
                    longOf(values, "AI_REQUEST_TIMEOUT_SECONDS", 30L).also {
                        if (it !in 1L..60L) throw ConfigException("AI_REQUEST_TIMEOUT_SECONDS must be between 1 and 60")
                    },
            )
        }

        private fun intOf(
            values: Map<String, String>,
            key: String,
            default: Int,
        ): Int {
            val raw = values[key]?.ifBlank { null } ?: return default
            return raw.toIntOrNull() ?: throw ConfigException("$key must be an integer, got: $raw")
        }

        private fun longOf(
            values: Map<String, String>,
            key: String,
            default: Long,
        ): Long {
            val raw = values[key]?.ifBlank { null } ?: return default
            return raw.toLongOrNull() ?: throw ConfigException("$key must be a number, got: $raw")
        }
    }
}
