# Дебаг базы решений (SQLite)

Как посмотреть, почему письмо запушилось или было скипнуто. База - единственный
лог решений классификатора; Telegram показывает только то, что прошло гейт пуша.

## Где база

- Контейнер: примонтированный volume `/state`, файл задаёт `STATE_DB_PATH`.
- Локально: путь из `STATE_DB_PATH` (дефолт `/state/inbox-watcher.db`).
- `sqlite3` есть в образе - запросы прямо в поде.

## Запрос в контейнере

```bash
docker exec inbox-watcher \
  sqlite3 -header /state/inbox-watcher.db \
  "select category, importance, notified, action, reason from processed_emails order by processed_at_epoch_millis desc limit 10;"
```

Интерактивно: `docker exec -it inbox-watcher sqlite3 /state/inbox-watcher.db`.

Нужна локальная копия (тяжёлый анализ, старый образ без sqlite3):

```bash
docker cp inbox-watcher:/state/inbox-watcher.db /tmp/inbox-watcher.db
```

Копия содержит метаданные личной почты (темы, саммари) - не коммитить, удалять после дебага.
Читать живую базу безопасно: сервис пишет редко (раз в poll), читатели ему не мешают.

## Схема

Таблица `processed_emails` (одна строка = одно обработанное письмо, insert or ignore):

| Колонка | Что |
|---------|-----|
| `message_id` | Gmail API id (PK, дедуп) |
| `importance` / `urgency` / `category` | вердикт LLM; NULL = unclassified (вся цепочка упала) |
| `summary` | саммари из пуша (русский) |
| `company` | компания из письма (для заголовка пуша); NULL если LLM не определил |
| `action` | конкретный следующий шаг из пуша; NULL если пользователю ничего делать не надо |
| `reason` | почему такой вердикт: одно предложение LLM (английский); для unclassified - агрегированный текст ошибок всех классификаторов |
| `notified` | 1 = пуш отправлен |
| `processed_at_epoch_millis` | момент записи решения |

Колонки, добавленные после создания базы, доезжают идемпотентным ALTER при старте
(`SqliteEmailStore.initializeSchema`); в строках, записанных до апгрейда, они NULL.
Строки старше 7 дней удаляются ежесуточным prune - история решений в базе = последняя неделя.

## Типовые запросы

```sql
-- последние решения
select datetime(processed_at_epoch_millis/1000,'unixepoch') as at_utc,
       category, importance, notified, company, action, reason, summary
from processed_emails order by processed_at_epoch_millis desc limit 20;

-- почему скипнуто (не запушено)
select datetime(processed_at_epoch_millis/1000,'unixepoch') as at_utc, reason, summary
from processed_emails where notified = 0 order by processed_at_epoch_millis desc limit 20;

-- unclassified: что именно упало в цепочке
select datetime(processed_at_epoch_millis/1000,'unixepoch') as at_utc, reason
from processed_emails where importance is null;

-- распределение вердиктов
select category, importance, notified, count(*) from processed_emails group by 1,2,3;
```

`message_id` -> письмо в браузере: `https://mail.google.com/mail/u/<адрес ящика>/#all/<message_id>`.
