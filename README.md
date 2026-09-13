# Inbox Watcher

[![CI](https://github.com/mshykhov/inbox-watcher/actions/workflows/ci.yml/badge.svg)](https://github.com/mshykhov/inbox-watcher/actions/workflows/ci.yml)
[![Release](https://github.com/mshykhov/inbox-watcher/actions/workflows/release.yml/badge.svg)](https://github.com/mshykhov/inbox-watcher/actions/workflows/release.yml)
[![Rulesync](https://img.shields.io/badge/agent%20config-Rulesync-6A5ACD)](https://github.com/dyoshikawa/rulesync)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A self-hosted Gmail inbox watcher that sends concise Telegram alerts for messages that need attention. It polls Gmail, stores processed message IDs in SQLite, classifies mail with Gemini and optional OpenAI-compatible fallbacks, and exposes `/health` and `/metrics`.

The built-in classifier is tuned for job-search, transactional, and AI-product mail. The pipeline and classifier prompt are ordinary Kotlin code, so categories can be adapted for another inbox workflow.

## Run locally

Requires JDK 21. Copy `.env.example` to `.env`, fill in the required values, then run:

```sh
./gradlew run
```

The first run marks the recent mailbox backlog as processed without sending alerts. Subsequent polls send Telegram notifications for new messages. Local state defaults to `/state/inbox-watcher.db`; set `STATE_DB_PATH=./data/inbox-watcher.db` for a project-local database.

## Gmail OAuth

1. Create an OAuth client in a Google Cloud project and enable the Gmail API.
2. Grant the client the `gmail.readonly` scope and obtain a refresh token for the mailbox to watch.
3. Put the client ID, client secret, and refresh token in `.env` as `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, and `GOOGLE_REFRESH_TOKEN`.

See [the Gmail setup note](docs/setup/gmail.md) for the required configuration values. Keep `.env` private.

## Configuration

`GEMINI_API_KEY`, `TELEGRAM_BOT_TOKEN`, and `TELEGRAM_CHAT_ID` are required alongside the Gmail OAuth values. `GROQ_API_KEY` and `CEREBRAS_API_KEY` are optional OpenAI-compatible classifier fallbacks. `.env.example` lists polling, retention, and HTTP settings.

## Container

```sh
docker build -t inbox-watcher .
docker run --env-file .env -p 8080:8080 -v inbox-state:/state inbox-watcher
```

Published releases are available as a Linux container image and a portable Gradle distribution:

```sh
docker pull ghcr.io/mshykhov/inbox-watcher:latest
docker run --env-file .env -p 8080:8080 -v inbox-state:/state ghcr.io/mshykhov/inbox-watcher:latest
```

Each `vX.Y.Z` tag publishes `ghcr.io/mshykhov/inbox-watcher:<version>` and `:latest`, plus a
`inbox-watcher-<version>.tar.gz` distribution attached to the GitHub release.

The service listens on port 8080. `GET /health` reports the poll-loop state, and `GET /metrics` exposes Prometheus metrics.

## Architecture

`GmailClient` reads recent message IDs and content. `EmailProcessor` deduplicates them through `SqliteEmailStore`, runs the classifier chain, and hands classified messages to `TelegramNotifier`. The HTTP server runs beside the polling loop and reports health and metrics. No test uses a live Gmail or Telegram service.

## Development

```sh
./gradlew check
```

`check` runs unit tests and ktlint. Tests use local fakes and never contact Gmail or Telegram.

## Agent configuration

The canonical repository instructions are in [.rulesync/rules/overview.md](.rulesync/rules/overview.md).
The committed `AGENTS.md` and `CLAUDE.md` files are generated from that source.

```sh
npm ci
npm run rulesync:install
npm run rulesync:generate -- --dry-run
npm run rulesync:generate
npm run rulesync:check
```

Commit the source, generated files, `package.json`, and lockfile together. CI checks both the
application and generated instructions.

## Migration from Email Watcher

`Inbox Watcher` is the public continuation of the former private deployment. Existing SQLite state
remains compatible: mount the same state volume and set
`STATE_DB_PATH=/state/inbox-watcher.db` if the database was already renamed. If the old filename is
still `email-watcher.db`, keep that exact path for the first run; the database schema and message IDs
are preserved. Rename the file only while the service is stopped.

## License

MIT. See [LICENSE](LICENSE).
