# Runbook: настройка Gmail API + секреты

Все внешние шаги для запуска inbox-watcher.

## 1. GCP-проект и Gmail API

1. Создать GCP-проект (например `inbox-watcher`).
2. Включить Gmail API (APIs & Services -> Enable).
3. Квоты (для справки): 1M quota units/день на проект; `messages.list` = 5 units, `messages.get` = 5. Polling раз в минуту - на порядки ниже лимита (~1.5% при 30-секундном интервале).

## 2. OAuth

1. OAuth consent screen: тип External, добавить свой аккаунт.
2. КРИТИЧНО: перевести приложение в статус **In production** (не "Testing") - иначе refresh token истекает через 7 дней и пайплайн молча умирает. Для scope `gmail.readonly` верификация Google не обязательна для личного использования (будет warning-экран - ок).
3. Scope: `https://www.googleapis.com/auth/gmail.readonly` (минимально достаточный).
4. Создать OAuth client id (тип Desktop app) -> `client_id` + `client_secret`.
5. Одноразово пройти consent flow локально -> получить `refresh_token`. Сервис дальше живёт на refresh token.

## 3. Telegram

1. Бот уже есть или создать у @BotFather -> `TELEGRAM_BOT_TOKEN`.
2. `TELEGRAM_CHAT_ID` - идентификатор разрешённого чата.
3. Allowlist по chat id в коде - бот отвечает только владельцу.

Pub/Sub push (`users.watch()` + webhook) сознательно НЕ используется - см. решение в `docs/ARCHITECTURE.md`.

## 4. Секреты

Храни значения в локальном `.env` или в secret store своей платформы. Для Gmail нужны
`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REFRESH_TOKEN`, для Telegram -
`TELEGRAM_BOT_TOKEN` и `TELEGRAM_CHAT_ID`. Для AI выбери хотя бы одного провайдера:
[выбор сервиса, ключи, бесплатные каталоги и примеры](ai-providers.md).
Ключ Gemini обязателен только если Gemini включён в цепочку. Полный шаблон - `.env.example`.

Локальный запуск использует `.env` (файл в `.gitignore`, шаблон `.env.example`). Для контейнерной
платформы передай те же переменные из её secret store, подключи постоянный volume к `/state` и
используй `/health` для liveness probe.
