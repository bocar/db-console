#!/usr/bin/env bash
#
# db-console 停止脚本。优先使用 logs/db-console.pid（--daemon 模式写入），
# 缺失时按命令行特征查找 java 进程。先 SIGTERM 优雅停机（等待语句取消完成），
# 超时后 SIGKILL。systemd 部署时无需本脚本，直接 systemctl stop dbconsole。
# 用法：bin/stop.sh
set -euo pipefail

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="$APP_HOME/logs/db-console.pid"
# 与 systemd 模板的 TimeoutStopSec 对齐：留足执行中语句的取消时间
STOP_TIMEOUT=90

PIDS=()

if [ -f "$PID_FILE" ]; then
  pid="$(cat "$PID_FILE")"
  if kill -0 "$pid" 2>/dev/null; then
    PIDS+=("$pid")
  else
    printf '提示：PID 文件中的进程 %s 已不存在（过期），改为按进程特征查找\n' "$pid" >&2
  fi
  rm -f "$PID_FILE"
fi

if [ ${#PIDS[@]} -eq 0 ]; then
  # 特征串首字符用 [d] 避免匹配到本脚本的命令行
  while IFS= read -r pid; do
    [ -n "$pid" ] && PIDS+=("$pid")
  done < <(pgrep -f '[d]b-console\.jar' 2>/dev/null || true)
fi

if [ ${#PIDS[@]} -eq 0 ]; then
  printf '未发现运行中的 db-console\n'
  exit 0
fi

printf '停止 db-console（PID: %s）...\n' "${PIDS[*]}"
kill "${PIDS[@]}" 2>/dev/null || true

waited=0
while [ "$waited" -lt "$STOP_TIMEOUT" ]; do
  alive=0
  for pid in "${PIDS[@]}"; do
    kill -0 "$pid" 2>/dev/null && alive=1
  done
  [ "$alive" -eq 0 ] && { printf '已停止\n'; exit 0; }
  sleep 1
  waited=$((waited + 1))
done

printf '等待 %ss 后仍存活，强制结束\n' "$STOP_TIMEOUT" >&2
kill -9 "${PIDS[@]}" 2>/dev/null || true
printf '已强制停止\n'
