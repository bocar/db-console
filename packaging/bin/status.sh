#!/usr/bin/env bash
#
# db-console 状态检查（只读，不改变任何状态；裸进程与 systemd 部署均适用）。
# 输出：进程状态（PID、运行时长、发现途径）、HTTP 存活、外部配置、随包 JDK。
# 退出码：0 运行中（HTTP 检查通过或跳过）；1 未运行；2 运行中但 HTTP 检查失败。
# 用法：bin/status.sh
set -euo pipefail

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="$APP_HOME/logs/db-console.pid"

# --- 1) 进程发现：PID 文件优先，缺失或过期时按命令行特征查找 ---
pid=""
pid_source=""
stale_note=""
if [ -f "$PID_FILE" ]; then
  p="$(cat "$PID_FILE")"
  if kill -0 "$p" 2>/dev/null; then
    pid="$p"; pid_source="PID 文件"
  else
    stale_note="（PID 文件中的 ${p} 已过期，改按进程特征查找）"
  fi
fi
if [ -z "$pid" ]; then
  p="$(pgrep -f '[d]b-console\.jar' 2>/dev/null | head -n 1 || true)"
  if [ -n "$p" ]; then
    pid="$p"; pid_source="进程特征查找"
  fi
fi
# 多实例告警：双进程会撞端口（如 systemd 与 --daemon 混用）
match_count="$(pgrep -f '[d]b-console\.jar' 2>/dev/null | wc -l | tr -d ' ' || true)"
if [ "$match_count" -gt 1 ]; then
  printf '警告：发现 %s 个 db-console 进程（PID: %s），疑似 systemd 与裸进程混用\n' \
    "$match_count" "$(pgrep -f '[d]b-console\.jar' | tr '\n' ' ')" >&2
fi

# --- 2) 外部配置与随包 JDK 就绪情况 ---
config_note="缺少（未创建 config/db-console.yml 与 config/datasources.yml）"
if [ -f "$APP_HOME/config/db-console.yml" ] && [ -f "$APP_HOME/config/datasources.yml" ]; then
  config_note="已就绪（config/db-console.yml、config/datasources.yml）"
fi
jdk_note="未随包"
bundled="$(find "$APP_HOME/jdk" -maxdepth 3 -type f -path '*/bin/java' 2>/dev/null | head -n 1 || true)"
if [ -n "$bundled" ]; then
  jdk_dir="$(dirname "$bundled")"     # .../jdk17-<arch>/bin
  jdk_dir="$(dirname "$jdk_dir")"     # .../jdk17-<arch>
  jdk_version="$("$bundled" -version 2>&1 | head -n 1)"
  jdk_note="已随包（$(basename "$jdk_dir")：${jdk_version}）"
fi

# --- 3) HTTP 存活（仅进程运行中时检查；端口取配置，缺省 8080） ---
http_exit=0
http_note="跳过（未运行）"
if [ -n "$pid" ]; then
  port="$(sed -n 's/^[[:space:]]*port:[[:space:]]*\([0-9][0-9]*\).*$/\1/p' \
    "$APP_HOME/config/db-console.yml" 2>/dev/null | head -n 1 || true)"
  port="${port:-8080}"
  if command -v curl >/dev/null 2>&1; then
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
      "http://127.0.0.1:${port}/" 2>/dev/null || true)"
    case "$code" in
      2*|3*) http_note="正常（http://127.0.0.1:${port}/ → HTTP ${code}）" ;;
      *) http_note="异常（http://127.0.0.1:${port}/ → HTTP ${code:-无响应}；可能仍在启动，或 server.address 绑定了非回环地址）"; http_exit=2 ;;
    esac
  else
    http_note="跳过（未安装 curl）"
  fi
fi

# --- 4) 汇总输出 ---
if [ -n "$pid" ]; then
  uptime="$(ps -o etime= -p "$pid" 2>/dev/null | tr -d ' ' || true)"
  printf '进程状态   : 运行中（PID %s，已运行 %s，经%s发现%s）\n' \
    "$pid" "${uptime:-未知}" "$pid_source" "$stale_note"
else
  printf '进程状态   : 未运行%s\n' "$stale_note"
fi
printf 'HTTP 存活   : %s\n' "$http_note"
printf '外部配置    : %s\n' "$config_note"
printf '随包 JDK    : %s\n' "$jdk_note"

if [ -z "$pid" ]; then
  exit 1
fi
exit "$http_exit"
