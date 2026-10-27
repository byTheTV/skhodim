# ИС «Сходим» — курсовая работа

Этапы 1–2 (предметная область, прецеденты, ER-модель, БД PostgreSQL) описаны в
`Skhodim_Etap_2_DB_full.docx` и каталоге `sql/`. Этап 3 (уровень хранения и
уровень бизнес-логики) реализован на Spring Boot в `src/main/java`, отчёт —
`Skhodim_Etap_3_Report.docx` (генерируется скриптом `docs/generate_report.py`).

## Структура

- `sql/` — схема БД, pl/pgsql-функции/процедуры, индексы, сиды, тесты этапа 2.
- `src/main/java/ru/ifmo/skhodim/domain` — доменные record-классы (22 сущности + DTO функций БД).
- `src/main/java/ru/ifmo/skhodim/repository` — уровень хранения: JdbcTemplate поверх функций БД.
- `src/main/java/ru/ifmo/skhodim/service` — уровень бизнес-логики (сервисы, планировщик).
- `src/main/java/ru/ifmo/skhodim/scheduler` — вызов `expire_requests()` по расписанию.
- `src/main/java/ru/ifmo/skhodim/exception` — единый словарь ошибок бизнес-логики.
- `docs/architecture.puml` / `architecture.png` — диаграмма классов общей архитектуры.
- `src/test/java/.../service` — интеграционные тесты уровня хранения/бизнес-логики (локальная БД).
- `src/test/java/.../SkhodimDatabaseTest.java` — проверки БД этапа 2 на сервере helios (JUnit + SSH).

## Запуск локально

1. Поднять отдельную учебную БД (см. `sql/README.md`):
   ```
   psql -d postgres -v db_name=skhodim_course -f sql/00_create_database.sql
   psql -d skhodim_course -v app_schema=skhodim -f sql/01_schema.sql
   psql -d skhodim_course -v app_schema=skhodim -f sql/02_logic.sql
   psql -d skhodim_course -v app_schema=skhodim -f sql/03_queries_indexes.sql
   psql -d skhodim_course -v app_schema=skhodim -f sql/04_seed.sql
   ```
2. Запустить приложение:
   ```
   set SKHODIM_DB_PASSWORD=postgres
   mvn spring-boot:run -Dspring-boot.run.profiles=local
   ```
3. Интеграционные тесты сервисного слоя (та же локальная БД):
   ```
   mvn test -Dtest=SkhodimServiceIntegrationTest
   ```

## Запуск на helios

Профиль `helios` ожидает переменные окружения `SKHODIM_SCHEMA` и
`SKHODIM_DB_PASSWORD` (личная схема в базе `studs`, хост PostgreSQL — `pg`):
```
SKHODIM_SCHEMA=sXXXXXX SKHODIM_DB_PASSWORD=*** java -jar target/skhodim.jar --spring.profiles.active=helios
```
Параметры SSH-подключения для `SkhodimDatabaseTest` задаются в
`src/test/resources/helios.local.properties` (файл не коммитится).

## Генерация отчёта этапа 3

```
docs\generate_report.py  # требует python-docx: pip install python-docx
```
Результат — `Skhodim_Etap_3_Report.docx` в корне проекта; включает диаграмму
классов из `docs/architecture.png`.
