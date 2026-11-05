# Этап 3 на helios — готовность и запуск

## Что уже сделано (этап 3)

| Требование этапа 3 | Где в проекте |
|--------------------|---------------|
| Диаграмма классов общей архитектуры | `docs/architecture.puml`, `docs/architecture.png` |
| Уровень хранения поверх БД этапа 2 | `src/main/java/ru/ifmo/skhodim/repository/*` |
| Использование pl/pgsql функций/процедур этапа 2 | `set_reaction`, `set_intention`, `send_request`, `resolve_request`, `search_catalog`, `recommend_events`, `get_messages`, `get_participants`, `CALL expire_requests()` — см. репозитории |
| Уровень бизнес-логики по прецедентам UC-01..UC-16 | `src/main/java/ru/ifmo/skhodim/service/*`, `scheduler/RequestExpirationScheduler` |
| Отчёт | `Skhodim_Etap_3_Report.docx` (генерация: `docs/generate_report.py`) |

Уровень представления (REST/UI) **намеренно отсутствует** — это этап 4 (`architecture.puml`, блок «Web UI / REST»).

Проверки:

- **БД этапа 2 на helios:** `mvn test -Dtest=SkhodimDatabaseTest` (SSH + psql, см. `helios.local.properties`).
- **Сервисы этапа 3 локально:** `mvn test -Dtest=SkhodimServiceIntegrationTest` (нужна локальная `skhodim_course` со сидом, см. `sql/README.md`).

---

## Предварительно: база в личной схеме

На helios **не** создают отдельную БД. Рабочая схема: `s467667` (подставьте свой логин `sXXXXXX`).

```bash
ssh -p 2222 s467667@helios.se.ifmo.ru
cd ~/skhodim-etap2   # или скопируйте каталог sql/ с машины разработки
export PGCLIENTENCODING=UTF8
APP_SCHEMA=s467667

psql -h pg -d studs -v ON_ERROR_STOP=1 -v app_schema=$APP_SCHEMA -f sql/91_reset_objects.sql
psql -h pg -d studs -v ON_ERROR_STOP=1 -v app_schema=$APP_SCHEMA -f sql/01_schema.sql
psql -h pg -d studs -v ON_ERROR_STOP=1 -v app_schema=$APP_SCHEMA -f sql/02_logic.sql
psql -h pg -d studs -v ON_ERROR_STOP=1 -v app_schema=$APP_SCHEMA -f sql/03_queries_indexes.sql
psql -h pg -d studs -v ON_ERROR_STOP=1 -v app_schema=$APP_SCHEMA -f sql/04_seed.sql
```

Проверка объёмов (как в отчёте этапа 2):

```bash
psql -h pg -d studs -c "SET search_path TO s467667, public; SELECT count(*) FROM app_user;"
# ожидается 100
```

**Не запускайте** `90_drop_schema.sql` на личной схеме helios — удалится вся схема, включая чужие лабораторные таблицы.

---

## Сборка JAR (на своём ПК)

```powershell
cd "D:\_projs\Новая папка"
mvn -q -DskipTests package
# артефакт: target\skhodim.jar
```

---

## Загрузка и запуск приложения на helios

JAR должен выполняться **на helios**, потому что в профиле `helios` JDBC-URL указывает на хост `pg` (доступен только из сети сервера).

```powershell
scp -P 2222 target\skhodim.jar s467667@helios.se.ifmo.ru:~/skhodim-etap2/
```

На helios:

```bash
ssh -p 2222 s467667@helios.se.ifmo.ru
cd ~/skhodim-etap2

export SKHODIM_SCHEMA=s467667
export SKHODIM_DB_PASSWORD='ваш_пароль_от_studs'

# Java 17+ (проверка: java -version)
nohup java -jar skhodim.jar --spring.profiles.active=helios > skhodim.log 2>&1 &
tail -f skhodim.log
```

Остановка:

```bash
pkill -f 'skhodim.jar'   # или найти PID через ps aux | grep skhodim
```

При успешном старте в логе будет строка Spring Boot `Started SkhodimApplication`. HTTP-API на этапе 3 нет (порт 8080 слушается, но контроллеров нет) — демонстрация этапа 3: **архитектура + репозитории/сервисы + тесты + отчёт**. Раз в минуту планировщик вызывает `expire_requests()` (см. `application.yml`, `app.request-expiration`).

---

## Демонстрация преподавателю (рекомендуемый сценарий)

1. **Отчёт:** `Skhodim_Etap_2_DB_full.docx` + `Skhodim_Etap_3_Report.docx`, рисунок из `docs/architecture.png`.
2. **БД на helios:** `psql -h pg -d studs` → `SET search_path TO s467667, public;` → выборка из `app_user`, вызов `SELECT * FROM search_catalog(p_query => 'концерт', p_limit => 3);`.
3. **Код этапа 3:** показать цепочку `CatalogService` → `CatalogRepository` → `SELECT * FROM search_catalog(...)`.
4. **Автотесты:**
   - на helios/SSH: `mvn test -Dtest=SkhodimDatabaseTest` (с ПК, если настроен `helios.local.properties`);
   - локально: `mvn test -Dtest=SkhodimServiceIntegrationTest` при поднятой `skhodim_course`.
5. **Процесс на helios:** `java -jar skhodim.jar --spring.profiles.active=helios` + фрагмент `skhodim.log` (подключение к `pg`, scheduler).

---

## Переменные и файлы

| Параметр | Значение на helios |
|----------|-------------------|
| SSH | `helios.se.ifmo.ru`, порт `2222`, пользователь `s467667` |
| PostgreSQL | хост `pg`, база `studs`, схема = логин |
| JDBC user | тот же логин (`SKHODIM_SCHEMA`) |
| Пароль БД | `SKHODIM_DB_PASSWORD` (не коммитить) |
| Профиль Spring | `--spring.profiles.active=helios` |

Конфиг: `src/main/resources/application-helios.yml`.

---

## Частые ошибки

- **`permission denied for database studs`** — вы пытались `CREATE DATABASE` или `CREATE SCHEMA` без прав; используйте только личную схему и правку `01_schema.sql` (схема создаётся только если её ещё нет).
- **`Connection refused` к `pg` с локального ПК** — приложение нужно запускать **на helios**, не на Windows.
- **Пустые интеграционные тесты сервисов** — не развёрнута локальная `skhodim_course` (скрипты 01–04).
- **`SkhodimDatabaseTest` падает** — проверьте `src/test/resources/helios.local.properties` (host, port, user, password, schema, database, pgHost).
