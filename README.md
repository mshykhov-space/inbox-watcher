# Inbox Watcher

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

The service listens on port 8080. `GET /health` reports the poll-loop state, and `GET /metrics` exposes Prometheus metrics.

## Architecture

`GmailClient` reads recent message IDs and content. `EmailProcessor` deduplicates them through `SqliteEmailStore`, runs the classifier chain, and hands classified messages to `TelegramNotifier`. The HTTP server runs beside the polling loop and reports health and metrics. No test uses a live Gmail or Telegram service.

## Development

```sh
./gradlew check
```

## License

MIT. See [LICENSE](LICENSE).
