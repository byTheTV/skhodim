#!/bin/sh
# Публичный адрес для приложения на helios через Tuna (tuna.am). Запускается НА СЕРВЕРЕ:
#   deploy/tunnel.sh start    поднять туннель, если он не запущен, и напечатать «Адрес: https://...»
#   deploy/tunnel.sh stop     остановить
#   deploy/tunnel.sh status   запущен ли и по какому адресу
#
# Почему Tuna: helios выпускает наружу только порт 443 (cloudflared нужен 7844, ssh-туннелям 22),
# а ngrok не пускает агентов с российских IP.
#
# Один раз вручную на сервере (в CI этого не делается):
#   1. положить FreeBSD-сборку tuna в ~/skhodim/tunnel/tuna/tuna, проверив подпись GPG;
#   2. войти: ~/skhodim/tunnel/tuna/tuna login --no-browser --insecure-storage
# Постоянный поддомен аккаунта передаётся через TUNA_SUBDOMAIN и запоминается в файле subdomain.
set -eu

COMMAND=${1:-status}
SKHODIM_HOME=${SKHODIM_HOME:-$HOME/skhodim}
[ -f "$SKHODIM_HOME/shared/env" ] && . "$SKHODIM_HOME/shared/env"
SERVER_PORT=${SERVER_PORT:-28467}
SCHEMA=${SKHODIM_SCHEMA:-$(id -un)}
DIR="$SKHODIM_HOME/tunnel"
TUNA="$DIR/tuna/tuna"
LOG="$DIR/tuna.log"
SUPERVISOR_PID="$DIR/supervisor.pid"

umask 077
mkdir -p "$DIR"

is_running() {
    [ -f "$SUPERVISOR_PID" ] && kill -0 "$(cat "$SUPERVISOR_PID")" 2>/dev/null
}

current_url() {
    [ -f "$LOG" ] || return 0
    grep -o 'Forwarding https://[^ ]*' "$LOG" | tail -1 | cut -d' ' -f2
}

# Туннель открывает порт всему интернету, поэтому сначала проверяем, что на нём наш сервис.
# Если наш процесс убили, порт может занять чужое приложение, и туннель показал бы его.
app_is_ours() {
    curl -fsS -m 5 "http://127.0.0.1:${SERVER_PORT}/api/v1/health" 2>/dev/null \
        | grep -q "\"schema\":\"$SCHEMA\""
}

start() {
    if ! app_is_ours; then
        echo "На порту $SERVER_PORT не отвечает приложение «Сходим», туннель не запускаю"
        stop > /dev/null
        exit 1
    fi
    if [ -n "${TUNA_SUBDOMAIN:-}" ]; then
        echo "$TUNA_SUBDOMAIN" > "$DIR/subdomain"
    fi
    subdomain=$(cat "$DIR/subdomain" 2>/dev/null || true)

    if is_running; then
        echo "Туннель уже запущен"
    else
        [ -x "$TUNA" ] || { echo "Нет $TUNA: установите tuna вручную (см. начало скрипта)"; exit 1; }
        set -- http "$SERVER_PORT" --inspect=false
        [ -n "$subdomain" ] && set -- "$@" --subdomain="$subdomain"
        : > "$LOG"
        # daemon перезапускает tuna, если та упала или потеряла связь.
        # Инспектор запросов выключен: на общем сервере его видели бы другие пользователи.
        # Без перенаправления daemon держит открытым вывод ssh, и команда по ssh не завершается.
        daemon -r -R 10 -P "$SUPERVISOR_PID" -p "$DIR/tuna.pid" -o "$LOG" "$TUNA" "$@" \
            < /dev/null > /dev/null 2>&1
        i=0
        while [ "$i" -lt 30 ] && [ -z "$(current_url)" ]; do
            sleep 1
            i=$((i + 1))
        done
    fi

    url=$(current_url)
    if [ -z "$url" ]; then
        echo "Адрес не появился, последние строки журнала:"
        tail -n 5 "$LOG" 2>/dev/null || true
        exit 1
    fi
    echo "Адрес: $url"
}

stop() {
    if is_running; then
        pid=$(cat "$SUPERVISOR_PID")
        kill "$pid"
        # Ждём, пока daemon завершит tuna: иначе новый запуск встретит старое подключение.
        i=0
        while kill -0 "$pid" 2>/dev/null && [ "$i" -lt 15 ]; do
            sleep 1
            i=$((i + 1))
        done
        echo "Туннель остановлен"
    else
        echo "Туннель не запущен"
    fi
    rm -f "$SUPERVISOR_PID" "$DIR/tuna.pid"
}

case "$COMMAND" in
    start) start ;;
    stop) stop ;;
    status)
        if is_running; then
            echo "Туннель запущен"
            echo "Адрес: $(current_url)"
        else
            echo "Туннель не запущен"
            exit 1
        fi
        ;;
    *)
        echo "Неизвестная команда: $COMMAND"
        exit 2
        ;;
esac
