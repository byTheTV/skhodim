#!/usr/bin/env bash
# Выкладка с компьютера разработчика или из CI. Запускается ЛОКАЛЬНО из корня репозитория:
#   deploy/deploy.sh            собрать, загрузить, применить миграцию, перезапустить
#   deploy/deploy.sh --no-build взять уже собранный target/skhodim.jar
set -eu

HELIOS_USER=${HELIOS_USER:-s467667}
HELIOS_HOST=${HELIOS_HOST:-helios.se.ifmo.ru}
HELIOS_PORT=${HELIOS_PORT:-2222}
REMOTE_DIR=skhodim/incoming
TARGET="$HELIOS_USER@$HELIOS_HOST"

if [ "${1:-}" != "--no-build" ]; then
    ${MVN:-mvn} -q -B -DskipTests package
fi
[ -f target/skhodim.jar ] || { echo "Нет target/skhodim.jar"; exit 1; }

ssh -p "$HELIOS_PORT" "$TARGET" "mkdir -p $REMOTE_DIR/sql $REMOTE_DIR/deploy"
scp -q -P "$HELIOS_PORT" target/skhodim.jar "$TARGET:$REMOTE_DIR/skhodim.jar"
scp -q -P "$HELIOS_PORT" sql/*.sql "$TARGET:$REMOTE_DIR/sql/"
scp -q -P "$HELIOS_PORT" deploy/release.sh deploy/db.sh deploy/tunnel.sh deploy/keepalive.sh "$TARGET:$REMOTE_DIR/deploy/"

# Миграция только добавляет объекты, поэтому применяется при каждой выкладке.
ssh -p "$HELIOS_PORT" "$TARGET" "cd $REMOTE_DIR && bash deploy/db.sh migrate && bash deploy/release.sh skhodim.jar"

# Туннель переживает перезапуск приложения; если он упал, поднимется здесь.
# Его сбой не отменяет выкладку: приложение уже работает, а CI отдельно проверяет адрес снаружи.
ssh -p "$HELIOS_PORT" "$TARGET" "TUNA_SUBDOMAIN='${TUNA_SUBDOMAIN:-}' sh $REMOTE_DIR/deploy/tunnel.sh start" \
    || echo "ВНИМАНИЕ: публичный туннель не поднялся"
