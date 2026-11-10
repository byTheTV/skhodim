#!/bin/sh
# Сторож: поднимает приложение и туннель, если их не стало. Запускается НА СЕРВЕРЕ:
#   sh deploy/keepalive.sh
# CI вызывает его по расписанию (.github/workflows/keepalive.yml).
#
# Зачем: helios каждую ночь в 05:00 завершает процессы студентов, и без перезапуска
# сайт не работает до утра. cron и at студентам на helios запрещены, поэтому расписание живёт в GitHub.
set -eu

SKHODIM_HOME=${SKHODIM_HOME:-$HOME/skhodim}
HERE=$(cd "$(dirname "$0")" && pwd)
[ -f "$SKHODIM_HOME/shared/env" ] && . "$SKHODIM_HOME/shared/env"
SERVER_PORT=${SERVER_PORT:-28467}
SCHEMA=${SKHODIM_SCHEMA:-$(id -un)}

if curl -fsS -m 5 "http://127.0.0.1:${SERVER_PORT}/api/v1/health" 2>/dev/null \
    | grep -q "\"schema\":\"$SCHEMA\""; then
    echo "Приложение работает"
else
    [ -f "$SKHODIM_HOME/current" ] || { echo "Нет ни одной выложенной версии"; exit 1; }
    echo "Приложение не отвечает, запускаю последнюю версию"
    bash "$HERE/release.sh" "$(cat "$SKHODIM_HOME/current")/skhodim.jar"
fi

sh "$HERE/tunnel.sh" start
