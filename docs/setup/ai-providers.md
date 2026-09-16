# AI-провайдеры и получение ключей

Провайдер выбирается в `.env`, код менять не нужно. Поддерживаются три HTTP-контракта:
OpenAI Chat Completions, Gemini generateContent и Anthropic Messages. Для нового сервиса
с одним из этих контрактов достаточно указать адрес API, модель и ключ.
Произвольный несовместимый API, AWS Bedrock, Vertex IAM и Azure API с отдельными заголовками
требуют совместимого шлюза или отдельного адаптера.

## Быстрый выбор

Примеры ниже показывают только AI-настройки. Gmail OAuth и Telegram заполняются отдельно
по [инструкции](gmail.md). После изменения `.env` перезапусти сервис.

Только Groq, без ключа Gemini:

```dotenv
AI_PROVIDERS=groq
GROQ_API_KEY=your-groq-key
```

Gemini с резервным Groq:

```dotenv
AI_PROVIDERS=gemini,groq
GEMINI_API_KEY=your-google-key
GROQ_API_KEY=your-groq-key
```

Независимый резерв для Groq через Z.ai:

```dotenv
AI_PROVIDERS=groq,zai
GROQ_API_KEY=your-groq-key
ZAI_API_KEY=your-zai-key
ZAI_BASE_URL=https://api.z.ai/api/paas/v4
ZAI_MODEL=glm-4.5-flash
ZAI_THINKING=disabled
AI_REQUEST_TIMEOUT_SECONDS=60
```

GLM-4.5-Flash указана как бесплатная по входным и выходным токенам в
[тарифах Z.ai](https://docs.z.ai/guides/overview/pricing). Не путай её с платными FlashX.
`THINKING=disabled` отключает рассуждения через
[`thinking.type`](https://docs.z.ai/api-reference/llm/chat-completion), для сокращения времени ответа. Для медленного резерва пример задаёт 60 секунд ожидания
каждого AI-запроса вместо стандартных 30. Проверяй доступность модели и конкурентный лимит
в своём кабинете; бесплатный тариф не гарантирует отсутствие перегрузок или неизменные квоты.
Две модели одного провайдера не дают независимого резерва при общей квоте или сбое сервиса.

Бесплатный маршрутизатор OpenRouter:

```dotenv
AI_PROVIDERS=openrouter
OPENROUTER_API_KEY=your-openrouter-key
OPENROUTER_MODEL=openrouter/free
OPENROUTER_RESPONSE_FORMAT=json_object
```

`openrouter/free` выбирает доступную бесплатную модель. Для воспроизводимых результатов выбери
конкретную модель с суффиксом `:free` из каталога. Доступность и квоты меняются:
[документация free router](https://openrouter.ai/docs/cookbook/get-started/free-models-router-playground),
[бесплатные варианты](https://openrouter.ai/docs/guides/routing/model-variants/free).

OpenAI или Anthropic: укажи ID модели, доступной твоему API-аккаунту.

```dotenv
AI_PROVIDERS=openai
OPENAI_API_KEY=your-openai-key
OPENAI_MODEL=your-model-id
```

```dotenv
AI_PROVIDERS=anthropic
ANTHROPIC_API_KEY=your-anthropic-key
ANTHROPIC_MODEL=your-model-id
ANTHROPIC_MAX_TOKENS=1024
```

Для Anthropic используется native Messages API с JSON-инструкцией в промпте.
Обрезанный ответ или невалидный JSON вызывает переход к следующему провайдеру.

## Локальные модели и другие API

Ollama: установи и запусти сервер, заранее загрузи модель через `ollama pull <model-id>`.
В `OLLAMA_MODEL` укажи точное имя из `ollama list`:

```dotenv
AI_PROVIDERS=ollama
OLLAMA_MODEL=your-installed-model
OLLAMA_BASE_URL=http://localhost:11434/v1
```

Для локальной Ollama ключ не нужен. Используется её
[OpenAI-совместимый endpoint](https://docs.ollama.com/api/openai-compatibility).
В Docker `localhost` означает сам контейнер. Для сервера на хосте в Docker Desktop задай
`http://host.docker.internal:11434/v1`; на Linux добавь
`--add-host=host.docker.internal:host-gateway` и обеспечь доступность сервера с контейнерной сети.
Выбирай модель, которая отвечает в пределах существующего HTTP timeout 30 секунд.

LM Studio или другой локальный OpenAI-совместимый сервер:

```dotenv
AI_PROVIDERS=local
LOCAL_BASE_URL=http://localhost:1234/v1
LOCAL_MODEL=your-loaded-model
LOCAL_AUTH_REQUIRED=false
LOCAL_RESPONSE_FORMAT=none
```

Любой совместимый облачный API, включая несколько моделей одного сервиса:

```dotenv
AI_PROVIDERS=primary,backup
PRIMARY_BASE_URL=https://your-provider.example/v1
PRIMARY_API_KEY=your-primary-key
PRIMARY_MODEL=your-primary-model
PRIMARY_RESPONSE_FORMAT=json_object
BACKUP_BASE_URL=https://your-provider.example/v1
BACKUP_API_KEY=your-backup-key
BACKUP_MODEL=your-backup-model
BACKUP_RESPONSE_FORMAT=none
```

Имена `primary`, `backup`, `local` произвольные. Для каждого имени используются переменные
с соответствующим префиксом в верхнем регистре. Можно задать `PRIMARY_API_TYPE=anthropic`
или `gemini`, если шлюз поддерживает такой протокол. Ключи разных провайдеров не подставляются
друг за друга.

## Справочник настроек

`AI_PROVIDERS` - список уникальных имён через запятую в порядке попыток, например
`groq,gemini,local`. Пробелы и регистр имён нормализуются. Разрешены латинские буквы,
цифры и `_`, имя начинается с буквы. Первый успешный ответ завершает цепочку.
Если все провайдеры недоступны или вернули некорректный ответ, письмо отправляется в Telegram
как `unclassified`, а причины ошибок сохраняются в журнале решений.

`AI_REQUEST_TIMEOUT_SECONDS` ограничивает каждый запрос к AI: по умолчанию `30`,
допустимо от `1` до `60` секунд. Gmail и Telegram используют отдельный клиент с timeout 30 секунд.
Если резерв тоже не ответил вовремя, действует доставка `unclassified`.

Для каждого `<NAME>`:

| Переменная | Значение |
|------------|----------|
| `<NAME>_API_TYPE` | `openai`, `gemini`, `anthropic`; по умолчанию `openai`, кроме встроенных `gemini` и `anthropic` |
| `<NAME>_API_KEY` | Ключ выбранного провайдера; обязателен, если не отключена проверка авторизации |
| `<NAME>_AUTH_REQUIRED` | `true` по умолчанию, `false` для встроенного `ollama`; при `false` ключ можно не задавать и заголовок авторизации не отправляется. Если ключ задан, он отправляется |
| `<NAME>_BASE_URL` | База API с версией, например `https://host/v1`, без `/chat/completions`, `/messages` или `/models/...:generateContent`. Завершающий `/` удаляется. Query, fragment и credentials в URL запрещены |
| `<NAME>_MODEL` | ID модели; для Gemini без префикса `models/`. Обязателен, кроме трёх прежних провайдеров из таблицы ниже |
| `<NAME>_RESPONSE_FORMAT` | Только OpenAI-совместимый API: `json_schema`, `json_object`, `none`. По умолчанию `json_object`; `none` не отправляет `response_format`, но JSON по-прежнему требуется промптом и проверяется парсером |
| `<NAME>_REASONING_EFFORT` | Только OpenAI-совместимый API: значение, поддерживаемое моделью. По умолчанию параметр не отправляется; `none` явно отключает отправку |
| `<NAME>_THINKING` | Только OpenAI-совместимые API с объектом `thinking`: `enabled` или `disabled`, регистр не важен. Передаётся как `thinking.type`; по умолчанию отсутствует. Несовместимый API может отклонить запрос |
| `<NAME>_MAX_TOKENS` | Только Anthropic: положительный лимит ответа, по умолчанию `1024` |

Встроенные значения:

| Имя | База API | Модель по умолчанию |
|-----|----------|--------------------|
| `gemini` | `https://generativelanguage.googleapis.com/v1beta` | `gemini-2.5-flash` |
| `groq` | `https://api.groq.com/openai/v1` | `openai/gpt-oss-20b` |
| `cerebras` | `https://api.cerebras.ai/v1` | `gpt-oss-120b` |
| `openai` | `https://api.openai.com/v1` | Указать явно |
| `openrouter` | `https://openrouter.ai/api/v1` | Указать явно |
| `anthropic` | `https://api.anthropic.com/v1` | Указать явно |
| `ollama` | `http://localhost:11434/v1` | Указать установленную модель |

Для прежних моделей Groq/Cerebras сохранены `json_schema` и `reasoning_effort=low`.
При замене модели эти настройки автоматически перестают применяться; их можно задать явно.
Все ответы проверяются одной схемой классификации. Допускается JSON в Markdown-блоке;
произвольный текст, неизвестные категории и неполный JSON считаются ошибкой.

Если `AI_PROVIDERS` не задан или пуст, работает прежний режим: Gemini при наличии ключа
(или явном `GEMINI_ENABLED=true`), затем Cerebras при `CEREBRAS_ENABLED=true`, затем Groq
при наличии ключа. `GEMINI_ENABLED=false` отключает Gemini. У включённого провайдера
обязательно должен быть ключ. Явный `AI_PROVIDERS` полностью заменяет этот выбор и игнорирует
старые флаги. Пустая цепочка отклоняется при запуске с понятной ошибкой.

## Где взять ключи и найти бесплатные варианты

| Сервис | Получение ключа / документация |
|--------|-------------------------------|
| Google Gemini | [Google AI Studio](https://aistudio.google.com/apikey) |
| Groq | [Groq Console](https://console.groq.com/keys), [совместимость API](https://console.groq.com/docs/openai) |
| Z.ai | [API keys](https://z.ai/manage-apikey/apikey-list), [тарифы](https://docs.z.ai/guides/overview/pricing), [лимиты аккаунта](https://z.ai/manage-apikey/rate-limits) |
| Cerebras | [Cerebras Cloud](https://cloud.cerebras.ai/), [документация](https://inference-docs.cerebras.ai/) |
| OpenRouter | [API keys](https://openrouter.ai/settings/keys), [каталог моделей](https://openrouter.ai/models) |
| OpenAI | [API keys](https://platform.openai.com/api-keys) |
| Anthropic | [Claude Console](https://console.anthropic.com/), [официальный SDK и API](https://github.com/anthropics/anthropic-sdk-python) |

Каталоги для поиска:

- [Free AI Bible](https://github.com/abbosaliboev/free-ai-bible) - общий каталог AI API и инструментов.
- [Awesome Free LLM APIs](https://github.com/open-free-llm-api/awesome-freellm-apis) - отдельный англоязычный каталог LLM API, моделей, лимитов и ссылок на получение ключей; автоматически обновляется.
- [FLA](https://github.com/startupmini/FLA) - продолжаемый форк Free LLM API resources с автоматическим обновлением списка моделей; описание на индонезийском.

Каталог помогает найти сервис, но не гарантирует текущую бесплатность. Проверяй тариф,
доступную модель и лимиты в кабинете самого провайдера. Бесплатный веб-чат не означает
бесплатный API. Не все сервисы из каталогов поддерживают один из трёх контрактов выше.

Отправитель, тема и до 4000 символов тела письма передаются выбранному API; при fallback
их увидит и следующий провайдер. Условия хранения и использования данных зависят от сервиса.
Для обработки без передачи писем облачным AI используй только локальные провайдеры в цепочке.
Ключи храни в `.env` или secret store, а не в URL, документации и Git.

## Если не работает

- Ошибка запуска с именем env-переменной: исправь настройки выбранного провайдера.
- `401` / `403`: проверь ключ, права аккаунта и доступность API в регионе.
- `404`: проверь базовый URL и ID модели; не добавляй `/chat/completions` к `BASE_URL`.
- `400` о `response_format`: выбери `json_object`, а если и он не поддерживается - `none`.
- `400` о reasoning: задай `<NAME>_REASONING_EFFORT=none`.
- `429`: исчерпана квота; добавь резервный сервис в `AI_PROVIDERS` или измени настройки тарифа.
- `unclassified`: смотри ошибки цепочки в логах и [SQLite](../db-debug.md). `/health` показывает
  живость polling, а не гарантию доступности AI; результаты вызовов есть в `/metrics` с меткой `provider`.
