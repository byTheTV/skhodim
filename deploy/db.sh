#!/usr/bin/env bash
# Работа с БД проекта на сервере. Запускается НА СЕРВЕРЕ из каталога с папкой sql/.
#
#   deploy/db.sh check     что есть в схеме: таблицы проекта и всё остальное (ничего не меняет)
#   deploy/db.sh backup    копия таблиц проекта в ~/skhodim/backups
#   deploy/db.sh migrate   применить 08_stage4.sql и 11_sources.sql: только добавляет объекты, данные не удаляет
#   deploy/db.sh reset     пересоздать таблицы проекта без демо-событий (спросит подтверждение)
#   deploy/db.sh activity  добавить активность демо-учёток поверх импортированных событий
#
# 90_drop_schema.sql здесь не используется намеренно: он удалил бы всю личную схему
# вместе с чужими лабораторными таблицами.
set -eu

COMMAND=${1:-check}
SCHEMA=${SKHODIM_SCHEMA:-$(id -un)}
PG_HOST=${PG_HOST:-pg}
PG_DB=${PG_DB:-studs}
BACKUPS=${SKHODIM_HOME:-$HOME/skhodim}/backups
SQL_DIR=${SQL_DIR:-sql}
export PGCLIENTENCODING=UTF8

PROJECT_TABLES="app_user user_profile user_interest catalog_source category tag place event event_session \
event_category event_tag event_reaction event_intention intention_period companion_request conversation \
message event_feedback notification report import_run import_error event_link event_duplicate"

psql_run() {
    psql -h "$PG_HOST" -d "$PG_DB" -v ON_ERROR_STOP=1 -v "app_schema=$SCHEMA" "$@"
}

in_list() {
    # local обязателен: цикл в check тоже использует переменную t.
    local project_table
    for project_table in $PROJECT_TABLES; do
        [ "$project_table" = "$1" ] && return 0
    done
    return 1
}

check() {
    echo "Схема: $SCHEMA"
    all=$(psql_run -Atc "SELECT table_name FROM information_schema.tables WHERE table_schema = '$SCHEMA' ORDER BY 1")
    echo "--- Таблицы проекта:"
    for t in $all; do in_list "$t" && echo "  $t"; done
    echo "--- Другие таблицы (скрипты их не трогают):"
    for t in $all; do in_list "$t" || echo "  $t"; done
    # Таблица с тем же именем могла остаться от другой лабораторной: тогда сброс её бы удалил.
    if psql_run -Atc "SELECT 1 FROM information_schema.columns WHERE table_schema = '$SCHEMA' AND table_name = 'app_user' AND column_name = 'password_hash'" | grep -q 1; then
        echo "--- Таблица app_user принадлежит проекту"
    else
        echo "--- ВНИМАНИЕ: таблицы app_user нет или она не из этого проекта"
    fi
    psql_run -Atc "SET search_path TO $SCHEMA; SELECT 'Событий: ' || count(*) FROM event" 2>/dev/null || true
}

backup() {
    mkdir -p "$BACKUPS"
    chmod 700 "$BACKUPS"
    file="$BACKUPS/skhodim-$(date +%Y%m%d-%H%M%S).sql"
    args=""
    for t in $PROJECT_TABLES; do
        args="$args -t $SCHEMA.$t"
    done
    # shellcheck disable=SC2086
    pg_dump -h "$PG_HOST" -d "$PG_DB" --no-owner $args > "$file"
    chmod 600 "$file"
    echo "Копия сохранена: $file ($(wc -c < "$file") байт)"
}

case "$COMMAND" in
    check)
        check
        ;;
    backup)
        backup
        ;;
    migrate)
        psql_run -q -f "$SQL_DIR/08_stage4.sql"
        psql_run -q -f "$SQL_DIR/11_sources.sql"
        echo "Миграция применена"
        ;;
    reset)
        check
        echo
        echo "Будут пересозданы ТОЛЬКО таблицы проекта из списка выше. Другие таблицы останутся."
        if [ "${2:-}" != "--yes" ]; then
            printf 'Напишите reset, чтобы продолжить: '
            read -r answer
            [ "$answer" = "reset" ] || { echo "Отменено"; exit 1; }
        fi
        backup
        for f in 91_reset_objects 01_schema 02_logic 03_queries_indexes 08_stage4 11_sources 09_base_data; do
            echo "== $f"
            psql_run -q -f "$SQL_DIR/$f.sql"
        done
        psql_run -Atc "SET search_path TO $SCHEMA; SELECT 'Пользователей: ' || count(*) FROM app_user; SELECT 'Событий: ' || count(*) FROM event"
        ;;
    activity)
        psql_run -q -f "$SQL_DIR/10_demo_activity.sql"
        ;;
    *)
        echo "Неизвестная команда: $COMMAND"
        exit 2
        ;;
esac
