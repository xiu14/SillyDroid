#!/system/bin/sh
set -eu

NODE_BIN="${1:-${TAVERN_NODE_BIN:-}}"
BOOTSTRAP_ROOT="${BOOTSTRAP_ROOT:?BOOTSTRAP_ROOT is required}"
TAVERN_DATA_ROOT="${TAVERN_DATA_ROOT:?TAVERN_DATA_ROOT is required}"
TAVERN_SERVER_DIR="${TAVERN_SERVER_DIR:?TAVERN_SERVER_DIR is required}"

is_disabled() {
    case "${SILLYDROID_ST_REMOTE_BACKUP_ENABLED:-true}" in
        ''|0|false|off|disabled|no)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

if is_disabled; then
    exit 0
fi

if [ -z "$NODE_BIN" ] || [ ! -x "$NODE_BIN" ]; then
    echo "st_remote_backup event=start skipped reason=node_missing node=$NODE_BIN" >&2
    exit 0
fi

SIDE_CAR_ROOT="$BOOTSTRAP_ROOT/sidecars/st-remote-backup"
if [ ! -f "$SIDE_CAR_ROOT/server.js" ]; then
    echo "st_remote_backup event=start skipped reason=server_missing dir=$SIDE_CAR_ROOT" >&2
    exit 0
fi

STATE_ROOT="${ST_REMOTE_BACKUP_STATE_ROOT:-$TAVERN_DATA_ROOT/../sidecars/st-remote-backup}"
APP_DIR="${ST_REMOTE_BACKUP_APP_DIR:-$STATE_ROOT}"
BACKUP_DIR="${ST_REMOTE_BACKUP_BACKUP_DIR:-$TAVERN_DATA_ROOT/../backups/st-remote-backup}"
DATA_DIR="${ST_REMOTE_BACKUP_DATA_DIR:-$TAVERN_DATA_ROOT/data}"
PORT="${ST_REMOTE_BACKUP_PORT:-8787}"
BIND_HOST="${ST_REMOTE_BACKUP_BIND_HOST:-127.0.0.1}"
LOG_DIR="${LOGS_DIR:-$APP_DIR/logs}"
LOG_FILE="$LOG_DIR/st-remote-backup.log"
PID_FILE="$APP_DIR/st-remote-backup.pid"

mkdir -p "$APP_DIR" "$BACKUP_DIR" "$DATA_DIR" "$LOG_DIR"

if [ -f "$PID_FILE" ]; then
    old_pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    case "$old_pid" in
        ''|*[!0-9]*)
            rm -f "$PID_FILE"
            ;;
        *)
            if kill -0 "$old_pid" 2>/dev/null; then
                echo "$old_pid"
                exit 0
            fi
            rm -f "$PID_FILE"
            ;;
    esac
fi

export APP_DIR
export BACKUP_DIR
export DATA_DIR
export TAVERN_DATA_ROOT
export PORT
export BIND_HOST
export SILLYDROID_EMBEDDED=1
export ST_REMOTE_BACKUP_DISABLE_PM2_RESTART="${ST_REMOTE_BACKUP_DISABLE_PM2_RESTART:-1}"
export NODE_PATH="$SIDE_CAR_ROOT/node_modules:$TAVERN_SERVER_DIR/node_modules${NODE_PATH:+:$NODE_PATH}"

{
    echo "st_remote_backup event=start port=$PORT bindHost=$BIND_HOST dataDir=$DATA_DIR backupDir=$BACKUP_DIR"
} >> "$LOG_FILE" 2>&1

cd "$SIDE_CAR_ROOT"
"$NODE_BIN" "$SIDE_CAR_ROOT/server.js" >> "$LOG_FILE" 2>&1 &
pid="$!"
printf '%s\n' "$pid" > "$PID_FILE"
echo "$pid"
