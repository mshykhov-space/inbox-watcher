---
root: true
description: "Inbox Watcher repository context and reliability contracts"
---
# Inbox Watcher

Generated file. Edit `.rulesync/rules/overview.md` and run `npm run rulesync:generate`.
Commit canonical source and generated root instructions together; `rulesync.jsonc` selects targets.

This self-hosted Kotlin service polls Gmail, classifies mail, and sends important messages to Telegram.
Read [docs/README.md](docs/README.md) for the documentation map and code-to-document update triggers.
The current design is described in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Reliability

- Preserve deduplication by message ID before fetching and processing a message.
- Record a message requiring notification only after successful delivery, so failures can retry next poll.
- When every classifier fails, deliver the message as unclassified and retain the failure reason.
- Keep processing failures visible through logs and the best-effort alert callback.
- Preserve first-run backlog seeding, silence detection, and poll-completion health tracking.
- Bound outbound HTTP calls with timeouts; existing recovery uses later polls and classifier fallback.
- Keep credentials out of source, images, logs, documentation, and commits; local secrets belong in ignored `.env`.
- Keep email content and personal data out of metric labels.

## Project boundaries

- Use the existing JDK 21, Kotlin, http4k/Undertow and Gradle setup in `build.gradle.kts`.
- Kotlin packages start at `src/main/kotlin/io/github/mshykhov/inboxwatcher/`.
- `Main` loads config; `InboxWatcher` owns composition, scheduling, HTTP, metrics, and shutdown.
- Reuse the interfaces in `core/`, orchestration in `pipeline/`, and adapters in their existing packages.
- `SqliteEmailStore` owns schema creation and incremental migration; preserve compatibility with existing databases.
- Keep the polling model and ordered classifier fallback unless the task explicitly changes that design.
- Provider flags, defaults and environment precedence are documented in [README.md](README.md) and `.env.example`.
- Check official dependency documentation before introducing a new API or library.

## Commands

- Local run: `./gradlew run`; set `STATE_DB_PATH=./data/inbox-watcher.db` for project-local state.
- One test class: `./gradlew test --tests 'io.github.mshykhov.inboxwatcher.pipeline.EmailProcessorTest'`.
- Application verification: `./gradlew check`; Kotlin formatting: `./gradlew ktlintFormat`.
- CI runs `./gradlew --no-daemon check build`; tests use local fakes rather than live mail or messaging services.
- Agent tooling setup: `npm ci` then `npm run rulesync:install` (frozen source installation).
- Preview generation: `npm run rulesync:generate -- --dry-run`; write outputs: `npm run rulesync:generate`.
- Check generated instructions: `npm run rulesync:check`.
- Match verification to the changed behavior; documentation edits do not need live integration calls.

## Documentation and maintenance

- Follow [docs/SCHEMA.md](docs/SCHEMA.md); keep repository documentation in its established Russian language.
- Document current behavior and known limits; keep historical decisions and reviews as dated snapshots.
- Comments explain the present contract and reason; use git history for dates, phases, and session chronology.
- Before changing notification formatting, read [docs/telegram-notifications.md](docs/telegram-notifications.md).
- When changing environment settings, update the configuration reference and `.env.example` together.
- Keep architecture details in the architecture docs and operational commands in runbooks.

## Runtime and delivery

- Container instructions are in [README.md](README.md).
- The Docker image uses a non-root user and persists SQLite under `/state`.
- A release tag runs `.github/workflows/release.yml`, which verifies the project and publishes the public image and distribution.
- A successful commit or CI run does not establish the health of a user's running instance.
