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

Храни значения в локальном `.env` или в secret store своей платформы. Имена runtime-переменных
перечислены в `.env.example`: `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`,
`GOOGLE_REFRESH_TOKEN`, `GEMINI_API_KEY`, `CEREBRAS_API_KEY`, `CEREBRAS_ENABLED`,
`GROQ_API_KEY`, `TELEGRAM_BOT_TOKEN` и `TELEGRAM_CHAT_ID`.

Классификатор - бесплатные free-tier LLM. Рабочая цепочка по умолчанию: Gemini → Groq.
Cerebras добавляется между ними только при `CEREBRAS_ENABLED=true`: исчерпанный ключ не должен
добавлять гарантированно неуспешный запрос на каждое письмо. Если Google отвечает постоянным
project-level `403 PERMISSION_DENIED`, задай `GEMINI_ENABLED=false`: цепочка начнётся с Groq
(или Cerebras, если он явно включён). Приватность: Gemini free tier обучается на данных, если
аккаунт не в UK/CH/EEA/EU - учитывай для рекрутёрской почты.

Локальный запуск использует `.env` (файл в `.gitignore`, шаблон `.env.example`). Для контейнерной
платформы передай те же переменные из её secret store, подключи постоянный volume к `/state` и
используй `/health` для liveness probe.
