# Skhodim — Этап 2. SQL-скрипты

Схема соответствует отчёту: 22 таблицы. Модерация без журнала: `hidden_at` и `is_banned`.

## Какие скрипты для чего

- Рабочая база: `01, 02, 03, 08, 11, 09`, события приходят только через импорт, затем при желании `10`.
- Автотесты: `01, 02, 03, 04, 08, 11` на отдельной базе `skhodim_test`. Синтетический `04_seed.sql` с 1000 событий нужен теперь только им.
- `08_stage4.sql` и `11_sources.sql` можно запускать повторно: они только добавляют объекты.
- `11_sources.sql`: несколько источников у одного события (`event_link`) и склейка дублей (`event_duplicate`, `merge_events`, `resolve_duplicates`).

## Порядок запуска (отдельная БД)

```bash
psql -d postgres -v db_name=skhodim_course -f sql/00_create_database.sql
psql -d skhodim_course -v app_schema=skhodim -f sql/01_schema.sql
psql -d skhodim_course -v app_schema=skhodim -f sql/02_logic.sql
psql -d skhodim_course -v app_schema=skhodim -f sql/03_queries_indexes.sql
psql -d skhodim_course -v app_schema=skhodim -f sql/04_seed.sql
psql -d skhodim_course -v app_schema=skhodim -f sql/05_tests.sql
psql -d skhodim_course -v app_schema=skhodim -f sql/06_explain.sql
```

## На helios (личная схема в базе studs)

`00_create_database.sql` не запускать: права CREATEDB нет. `90_drop_schema.sql` на личной схеме не запускать: он удалит всю схему, включая чужие лабораторные.

```bash
psql -d studs -v app_schema=s123456 -f sql/01_schema.sql
# 02, 03, 04, 05, 06 с тем же app_schema
```

Повторный прогон на личной схеме: `psql -h pg -d studs -v app_schema=s123456 -f sql/91_reset_objects.sql`, затем скрипты 01–06. Файл удаляет только 22 таблицы проекта.

## Состав

- `01_schema.sql` — 22 таблицы, ключи, CHECK, уникальность
- `02_logic.sql` — триггеры, функции и процедура `expire_requests`
- `03_queries_indexes.sql` — индексы, `search_catalog`, `recommend_events`
- `04_seed.sql` — набор из раздела 9 отчёта (100 пользователей, 1000 событий, 569 реакций, 8 намерений, 4 заявки, 1 диалог, 2 сообщения, 21 комментарий, 4 жалобы, 1 импорт)
- `05_tests.sql` — проверки целостности, откатываются
- `06_explain.sql` — планы с индексом и без него

Удаление отдельной учебной схемы: `psql ... -v app_schema=skhodim -v allow_drop=true -f sql/90_drop_schema.sql`

## JUnit

`mvn test -Dtest=SkhodimDatabaseTest` заново заливает на helios скрипты 91 и 01–04, затем проверяет пользователей, объёмы отчёта, сценарий диалога и отказ некорректных действий. Параметры подключения лежат в `src/test/resources/helios.local.properties`, этот файл не коммитится.
