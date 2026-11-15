#!/usr/bin/env bash
#
# db-console 重启脚本（裸进程 --daemon 部署模式）。
# 等价于 bin/stop.sh && bin/start.sh --daemon；当前无进程时等价于直接启动。
# systemd 部署请用 systemctl restart dbconsole（不要对本服务混用本脚本，
# 避免脱离 systemd 再拉起一个进程导致端口冲突）。
# 用法：bin/restart.sh
set -euo pipefail

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

printf '==> 停止旧进程\n'
"$APP_HOME/bin/stop.sh"

printf '==> 启动新进程\n'
exec "$APP_HOME/bin/start.sh" --daemon
