# ИС «Сходим» — курсовая работа

Этапы 1–2 (предметная область, прецеденты, ER-модель, БД PostgreSQL) — в
`sql/` и отчёте этапа 2. Этап 3: уровень хранения и бизнес-логики (Spring Boot,
JdbcTemplate поверх pl/pgsql). REST `/api/v1` и Swagger — для проверки на helios.
Отчёт этапа 3: `docs/generate_report.py` → `Skhodim_Etap_3_Report.docx`.

## Структура

- `sql/` — схема БД, pl/pgsql-функции/процедуры, индексы, сиды.
- `src/main/java/ru/ifmo/skhodim/domain` — доменные record-классы.
- `src/main/java/ru/ifmo/skhodim/repository` — уровень хранения: JdbcTemplate + функции БД.
- `src/main/java/ru/ifmo/skhodim/service` — бизнес-логика.
- `src/main/java/ru/ifmo/skhodim/web` — REST `/api/v1` и Swagger.
- `docs/architecture.puml` — диаграмма классов общей архитектуры.

Демо-учётки после посева `04_seed.sql`:

| Роль | Email | Пароль |
|------|-------|--------|
| Пользователь | `demo1@example.org` | `Demo12345!` |
| Модератор | `demo100@example.org` | `Demo12345!` |

---

## Запуск на helios (основной сценарий)

Приложение должно работать **на helios**: JDBC ходит на хост `pg`, с вашего ПК он
недоступен. HTTP слушает только `127.0.0.1:18080` (порт 8080 занят GlassFish).
Снаружи заходим SSH-туннелем.

### Что умирает, когда закрываешь терминал

Два разных процесса:

| Что | Где живёт | Закрыл терминал |
|-----|-----------|-----------------|
| `java -jar skhodim.jar` | helios, `nohup` | **не умирает** (пока helios не прибьёт процесс) |
| SSH-туннель `-L 18080` | твой ПК | **умирает**. Swagger на localhost пропадёт |

Поэтому почти всегда достаточно заново открыть туннель. JAR пересобирать и
SQL заливать не нужно.

### Каждый раз, когда закрыл терминал / перезагрузил ноут

**1. Проверь, жив ли JAR** (можно в любом ssh-окне, его потом закрыть):

```bash
ssh -p 2222 s467667@helios.se.ifmo.ru
curl -sS http://127.0.0.1:18080/api/v1/health
# ожидается: {"status":"ok","schema":"s467667"}
pgrep -af skhodim.jar
```

Если health отвечает — Java не трогай, только туннель (шаг 2).

Если `Connection refused` / пустой `pgrep` — перезапусти JAR:

```bash
cd ~/skhodim-etap2
pkill -f skhodim.jar || true
export SKHODIM_SCHEMA=s467667
export SKHODIM_DB_PASSWORD=$(awk -F: '{print $5; exit}' ~/.pgpass)
# без -Xmx256m JVM на helios падает (пытается выделить десятки ГБ)
nohup java -Xms64m -Xmx256m -Djava.awt.headless=true \
  -jar skhodim.jar --spring.profiles.active=helios --server.port=18080 \
  > skhodim.log 2>&1 &
sleep 8
tail -n 20 skhodim.log
# нужна строка: Started SkhodimApplication
curl -sS http://127.0.0.1:18080/api/v1/health
```

Остановка: `pkill -f skhodim.jar`. Лог: `~/skhodim-etap2/skhodim.log`.

**2. Туннель на своём ПК** — отдельный терминал, **не закрывать**, пока смотришь Swagger:

```bash
ssh -L 18080:127.0.0.1:18080 -p 2222 s467667@helios.se.ifmo.ru
```

Сессия должна остаться открытой (просто висит). Если окно закрылось — туннель
умер, команду повторить. В браузере:

1. Жив ли сервис: <http://localhost:18080/api/v1/health>
2. Swagger UI: <http://localhost:18080/swagger-ui.html>
3. `POST /api/v1/auth/login` — тело уже заполнено: `demo1@example.org` / `Demo12345!`
4. **Authorize** → вставить `token` из ответа (без слова Bearer)
5. Дальше любые методы. Гость без токена: health, поиск, карточка события.
   Админка — логин `demo100@example.org`.

Готовые id в шапке Swagger: события `1` и `10`, сеансы `19`/`20`, диалог `1`.

Если `localhost:18080` не открывается, а на helios `curl` к health работает —
проблема только в туннеле (закрыт, занят порт, опечатка в `-L`).

### 1. База в личной схеме (один раз)

На helios **не** создают отдельную БД и **не** запускают `sql/90_drop_schema.sql`
(снесёт всю личную схему). Нужны скрипты в `~/skhodim-etap2/sql/`.

```bash
ssh -p 2222 s467667@helios.se.ifmo.ru
cd ~/skhodim-etap2
export PGCLIENTENCODING=UTF8
APP_SCHEMA=s467667

# полный пересоздать объекты проекта (чужие таблицы схемы не трогает):
psql -h pg -d studs -v ON_ERROR_STOP=1 -v app_schema=$APP_SCHEMA -f sql/91_reset_objects.sql
psql -h pg -d studs -v ON_ERROR_STOP=1 -v app_schema=$APP_SCHEMA -f sql/01_schema.sql
psql -h pg -d studs -v ON_ERROR_STOP=1 -v app_schema=$APP_SCHEMA -f sql/02_logic.sql
psql -h pg -d studs -v ON_ERROR_STOP=1 -v app_schema=$APP_SCHEMA -f sql/03_queries_indexes.sql
psql -h pg -d studs -v ON_ERROR_STOP=1 -v app_schema=$APP_SCHEMA -f sql/04_seed.sql

psql -h pg -d studs -c "SET search_path TO s467667, public; SELECT count(*) FROM app_user;"
# ожидается 100
```

Если схема уже залита, достаточно проверки `count(*)`. Для накатки только новых
функций/колонок: `sql/07_migrate_etap1_api.sql`, затем снова `03_queries_indexes.sql`.

### 2. Сборка JAR (на своём ПК)

```bash
mvn -DskipTests package
# артефакт: target/skhodim.jar
scp -P 2222 target/skhodim.jar s467667@helios.se.ifmo.ru:~/skhodim-etap2/
```

### 3. Старт JAR на helios (если health молчит)

См. блок «Каждый раз, когда закрыл терминал» выше. Коротко: `pkill` → `nohup java -Xmx256m … &`.

Коллекция Postman: `docs/postman/Skhodim-Helios.postman_collection.json`.

---

## Запуск локально

Нужна отдельная БД `skhodim_course` (см. `sql/README.md`):

```bash
psql -d postgres -v db_name=skhodim_course -f sql/00_create_database.sql
psql -d skhodim_course -v app_schema=skhodim -f sql/01_schema.sql
psql -d skhodim_course -v app_schema=skhodim -f sql/02_logic.sql
psql -d skhodim_course -v app_schema=skhodim -f sql/03_queries_indexes.sql
psql -d skhodim_course -v app_schema=skhodim -f sql/04_seed.sql

export SKHODIM_DB_PASSWORD=postgres
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Локально порт **8080**: <http://localhost:8080/swagger-ui.html>.

Тесты:

```bash
mvn test -Dtest=SkhodimServiceIntegrationTest   # локальная БД
mvn test -Dtest=SkhodimDatabaseTest            # helios по SSH, нужен helios.local.properties
```

`src/test/resources/helios.local.properties` в git не коммитится.

---

## Генерация отчёта этапа 3

```
docs/generate_report.py   # pip install python-docx
```

Результат — `Skhodim_Etap_3_Report.docx`. Подробности по БД на helios:
[docs/HELIOS_STAGE3.md](docs/HELIOS_STAGE3.md).
