# Документация inbox-watcher

Карта живых технических контрактов сервиса. Существующая структура сохранена:
архитектура и справочники находятся в корне `docs/`, внешняя настройка - в `setup/`.
Правила структуры, именования и приоритета источников: [SCHEMA.md](SCHEMA.md).

## Карта

| Документ | Содержание | Тип |
|----------|------------|-----|
| [SCHEMA.md](SCHEMA.md) | Контракт ведения документации | Живой |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Компоненты, поток, состояние, инварианты и выбор polling | Живой |
| [setup/README.md](setup/README.md) | Навигация по настройке внешних интеграций | Живой |
| [setup/gmail.md](setup/gmail.md) | GCP, OAuth, Telegram и секреты | Живой runbook |
| [setup/ai-providers.md](setup/ai-providers.md) | Выбор AI, fallback, ключи, бесплатные каталоги и диагностика | Живой runbook |
| [telegram-notifications.md](telegram-notifications.md) | Формат пуша, HTML и deep-links | Живой справочник |
| [db-debug.md](db-debug.md) | SQLite: доступ, схема и диагностические запросы | Живой runbook |
| [Корневой README](../README.md) | Сборка, env, доставка и общая конфигурация агентов | Живой |
Живые документы обновляются вместе с соответствующим кодом.

## Изменение кода -> обновление документации

Пути Kotlin ниже относятся к `src/main/kotlin/io/github/mshykhov/inboxwatcher/`.

| Изменённый код или контракт | Обновить |
|----------------------------|----------|
| `Main.kt`, `InboxWatcher.kt`, `core/`, `pipeline/` или границы компонентов | [ARCHITECTURE.md](ARCHITECTURE.md) |
| `classifier/ClassificationOutput.kt`, `classifier/ClassifierChain.kt` | [ARCHITECTURE.md](ARCHITECTURE.md), при изменении action/summary также [telegram-notifications.md](telegram-notifications.md) |
| `telegram/TelegramNotifier.kt`, `core/GmailLinks.kt`, `http/OpenInGmail.kt` | [telegram-notifications.md](telegram-notifications.md) и раздел deep-link в [ARCHITECTURE.md](ARCHITECTURE.md) |
| `state/SqliteEmailStore.kt`, retention в `InboxWatcher.kt` | [db-debug.md](db-debug.md) и состояние в [ARCHITECTURE.md](ARCHITECTURE.md) |
| `http/HealthHandler.kt`, `metrics/`, `pipeline/SilenceCanary.kt` | [ARCHITECTURE.md](ARCHITECTURE.md) |
| `config/RuntimeConfig.kt`, `config/AiProviders.kt`, `config/EnvFiles.kt`, `.env.example` | Env и запуск в [корневом README](../README.md), [AI-провайдеры](setup/ai-providers.md) |
| Gmail OAuth, scopes, `scripts/get-refresh-token.py` или внешние секреты | [setup/gmail.md](setup/gmail.md) |
| `build.gradle.kts`, `Dockerfile`, `.github/workflows/` | Команды и доставка в [корневом README](../README.md) |
| `.rulesync/`, `rulesync.jsonc`, `package.json` | Конфигурация агентов в [корневом README](../README.md) и generated outputs |

## Поддержка

- Технический контракт описывается здесь, поведение агента - в `.rulesync/rules/overview.md`.
- При изменении документа добавляй или исправляй относительные ссылки в карте и проверяй их наличие.
- Новые темы добавляй по реальной потребности; новую категорию документов сначала опиши в SCHEMA.md.
- Историю решений и результаты разовых проверок отделяй от текущего поведения сервиса.
