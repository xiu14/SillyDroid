#!/system/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd -P)"
BOOTSTRAP_ROOT="${BOOTSTRAP_ROOT:-$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd -P)}"
export BOOTSTRAP_ROOT

. "$SCRIPT_DIR/termux-host-runtime.sh"

prepare_termux_host_runtime
install_git_core_links "$HOST_TMP_DIR/server-fast-bin"
install_git_core_links "$GIT_EXEC_PATH"

echo "server-fast git injection completed"
