#!/usr/bin/env bash
# Выкладка новой версии. Запускается НА СЕРВЕРЕ: deploy/release.sh <путь к новому skhodim.jar>
#
# Что делает:
#   1. кладёт JAR в отдельную папку releases/<время>;
#   2. останавливает только свой процесс (по pid-файлу) и запускает новую версию;
#   3. проверяет /api/v1/health; если сервис не поднялся, возвращает предыдущую версию;
#   4. оставляет последние 3 версии.
# Ничего за пределами папки SKHODIM_HOME не трогает.
set -eu

JAR_SOURCE=${1:?Укажите путь к JAR}
SKHODIM_HOME=${SKHODIM_HOME:-$HOME/skhodim}
SKHODIM_PROFILE=${SKHODIM_PROFILE:-helios}
SERVER_PORT=${SERVER_PORT:-18080}
HEALTH_TRIES=${HEALTH_TRIES:-45}
HEALTH_URL="http://127.0.0.1:${SERVER_PORT}/api/v1/health"

RELEASES="$SKHODIM_HOME/releases"
SHARED="$SKHODIM_HOME/shared"
PID_FILE="$SHARED/app.pid"
LOG_FILE="$SHARED/app.log"
ENV_FILE="$SHARED/env"

mkdir -p "$RELEASES" "$SHARED"
chmod 700 "$SKHODIM_HOME" "$SHARED"

# Секрет подписи токенов создаётся один раз и хранится только на сервере.
if [ ! -f "$ENV_FILE" ]; then
    umask 077
    {
        echo "SKHODIM_SCHEMA=${SKHODIM_SCHEMA:-$(id -un)}"
        echo "SKHODIM_JWT_SECRET=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    } > "$ENV_FILE"
    chmod 600 "$ENV_FILE"
fi
set -a
. "$ENV_FILE"
set +a

if [ "$SKHODIM_PROFILE" = "helios" ] && [ -z "${SKHODIM_DB_PASSWORD:-}" ]; then
    SKHODIM_DB_PASSWORD=$(awk -F: 'NF >= 5 { print $5; exit }' "$HOME/.pgpass")
    export SKHODIM_DB_PASSWORD
fi

is_running() {
    [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null
}

stop_app() {
    if is_running; then
        pid=$(cat "$PID_FILE")
        echo "Останавливаю процесс $pid"
        kill "$pid" 2>/dev/null || true
        i=0
        while kill -0 "$pid" 2>/dev/null && [ "$i" -lt 30 ]; do
            sleep 1
            i=$((i + 1))
        done
        if kill -0 "$pid" 2>/dev/null; then
            kill -9 "$pid" 2>/dev/null || true
        fi
    fi
    rm -f "$PID_FILE"
}

start_app() {
    jar=$1
    # Без -Xmx JVM на helios пытается выделить десятки гигабайт и падает.
    nohup java -Xms64m -Xmx256m -Djava.awt.headless=true -jar "$jar" \
        "--spring.profiles.active=$SKHODIM_PROFILE" "--server.port=$SERVER_PORT" \
        >> "$LOG_FILE" 2>&1 &
    echo $! > "$PID_FILE"
}

wait_healthy() {
    i=0
    while [ "$i" -lt "$HEALTH_TRIES" ]; do
        if curl -fsS -m 3 "$HEALTH_URL" 2>/dev/null | grep -q '"status":"ok"'; then
            return 0
        fi
        if ! is_running; then
            echo "Процесс завершился при запуске"
            return 1
        fi
        sleep 2
        i=$((i + 1))
    done
    echo "Сервис не ответил за отведённое время"
    return 1
}

previous=""
if [ -f "$SKHODIM_HOME/current" ]; then
    previous=$(cat "$SKHODIM_HOME/current")
fi

release="$RELEASES/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$release"
cp "$JAR_SOURCE" "$release/skhodim.jar"

stop_app
start_app "$release/skhodim.jar"

if wait_healthy; then
    echo "$release" > "$SKHODIM_HOME/current"
    echo "Версия запущена: $release"
    # Старые версии удаляются только внутри releases, от новых к старым остаётся 3.
    ls -1d "$RELEASES"/*/ 2>/dev/null | sort -r | tail -n +4 | while read -r old; do
        rm -rf "$old"
    done
    curl -fsS -m 3 "$HEALTH_URL"
    echo
    exit 0
fi

echo "Новая версия не запустилась, последние строки журнала:"
tail -n 25 "$LOG_FILE" || true
stop_app
rm -rf "$release"
if [ -n "$previous" ] && [ -f "$previous/skhodim.jar" ]; then
    echo "Возвращаю предыдущую версию: $previous"
    start_app "$previous/skhodim.jar"
    if wait_healthy; then
        echo "Откат выполнен, работает предыдущая версия"
    else
        echo "Откат не удался: нужна ручная проверка"
    fi
fi
exit 1
