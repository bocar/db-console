#!/usr/bin/env bash
#
# db-console 启动脚本（Linux 发行包：麒麟 V10 / CentOS 7+）。JDK 选择优先级：
#   1) DB_CONSOLE_JAVA_HOME 环境变量显式指定的 JDK（须 17+）
#   2) 随包 jdk/jdk17-<架构>/（默认）
# 不探测系统 PATH 的 java：现场预装的毕昇 JDK 8 不参与启动、无需任何改动。
# 用法：bin/start.sh [--daemon] [--dry-run]
#   --daemon  后台运行，日志写入 logs/db-console.log，PID 记录于 logs/db-console.pid
#   --dry-run 只打印解析结果（JAVA、配置、启动命令），不真正启动
set -euo pipefail

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

DAEMON=false
DRY_RUN=false
while [ $# -gt 0 ]; do
  case "$1" in
    --daemon) DAEMON=true; shift ;;
    --dry-run) DRY_RUN=true; shift ;;
    -h|--help)
      sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) printf '未知参数：%s（--help 查看用法）\n' "$1" >&2; exit 1 ;;
  esac
done

die() { printf '错误：%s\n' "$*" >&2; exit 1; }

machine="$(uname -m)"
case "$machine" in
  aarch64|arm64) ARCH=aarch64 ;;
  x86_64|amd64) ARCH=x64 ;;
  *)
    die "不支持的 CPU 架构：${machine}。本发行包提供 aarch64（鲲鹏/飞腾）与 x64（海光/兆芯/Intel/AMD）构建，目标系统为麒麟 V10 / CentOS / Debian 等 glibc ≥ 2.17 的发行版。" ;;
esac

# 1) 解析 java：仅显式指定或随包，不探测系统 PATH（现场毕昇 JDK 8 不参与）
if [ -n "${DB_CONSOLE_JAVA_HOME:-}" ]; then
  JAVA="$DB_CONSOLE_JAVA_HOME/bin/java"
else
  JAVA="$APP_HOME/jdk/jdk17-$ARCH/bin/java"
fi
[ -n "$JAVA" ] && [ -x "$JAVA" ] \
  || die "未找到可用的 JDK 17（随包 jdk/jdk17-$ARCH/ 缺失，且未设置 DB_CONSOLE_JAVA_HOME）。
解决方式：
  1) 重新解压完整发行包，确保 jdk/jdk17-$ARCH/ 目录存在；
  2) 或 export DB_CONSOLE_JAVA_HOME=/path/to/jdk17 指向本机已装的 JDK 17。
系统自带的 java（如毕昇 JDK 8）不参与本应用，无需改动。"

# 2) 版本门槛：Spring Boot 3.4 须 JDK 17+，直接给出可操作的报错而不是 UnsupportedClassVersionError
JAVA_VERSION_LINE="$("$JAVA" -version 2>&1 | head -n 1)"
JAVA_MAJOR="$(printf '%s\n' "$JAVA_VERSION_LINE" \
  | awk -F'"' '/version "/{print $2; exit}' | cut -d. -f1 | sed 's/^1$/8/')"
if ! [ "$JAVA_MAJOR" -ge 17 ] 2>/dev/null; then
  die "当前 java 主版本为 \"${JAVA_MAJOR}\"（${JAVA_VERSION_LINE}），db-console 需要 JDK 17+。
解决方式：
  1) 取消 DB_CONSOLE_JAVA_HOME 设置，改用随包 JDK（确保 jdk/jdk17-$ARCH/ 目录存在）；
  2) 或将其改为指向本机已安装的 JDK 17+（如毕昇 JDK 17）；
  3) 若站点仅允许 JDK 8，该站点暂不在支持范围，请联系维护方。"
fi

# 3) 外部配置：沿用 README 约定的 SPRING_CONFIG_ADDITIONAL_LOCATION 双文件注入。
#    运维已自行设置该变量时不覆盖，配置检查也交由其自行负责。
if [ -z "${SPRING_CONFIG_ADDITIONAL_LOCATION:-}" ]; then
  for f in db-console.yml datasources.yml; do
    [ -f "$APP_HOME/config/$f" ] || die "缺少配置文件 config/${f}。请先执行：
  cd $APP_HOME/config
  cp db-console.example.yml db-console.yml
  cp datasources.example.yml datasources.yml
再按现场情况修改（数据源、端口、审计目录等），并准备好配置中引用的凭据环境变量。"
  done
  export SPRING_CONFIG_ADDITIONAL_LOCATION="file:$APP_HOME/config/db-console.yml,file:$APP_HOME/config/datasources.yml"
fi

# 工作目录固定为部署根目录，使默认审计目录 ./audit 等相对路径落位确定
cd "$APP_HOME"
# JAVA_OPTS 按惯例做空白切分（如 "-Xms256m -Xmx1g"）；${OPTS[@]+...} 为 set -u 下的空数组安全写法
OPTS=()
if [ -n "${JAVA_OPTS:-}" ]; then
  read -r -a OPTS <<< "$JAVA_OPTS"
fi
CMD=("$JAVA" ${OPTS[@]+"${OPTS[@]}"} -jar "$APP_HOME/db-console.jar")

if $DRY_RUN; then
  printf 'CPU 架构   : %s（uname -m: %s）\n' "$ARCH" "$machine"
  printf 'JAVA       : %s\n' "$JAVA"
  printf 'JAVA 版本  : %s\n' "$JAVA_VERSION_LINE"
  printf '应用目录   : %s\n' "$APP_HOME"
  printf '外部配置   : %s\n' "${SPRING_CONFIG_ADDITIONAL_LOCATION}"
  printf 'JAVA_OPTS  : %s\n' "${JAVA_OPTS:-（未设置）}"
  printf '启动命令   :'
  printf ' %q' "${CMD[@]}"
  printf '\n'
  exit 0
fi

if $DAEMON; then
  mkdir -p "$APP_HOME/logs"
  nohup "${CMD[@]}" >> "$APP_HOME/logs/db-console.log" 2>&1 &
  echo $! > "$APP_HOME/logs/db-console.pid"
  printf 'db-console 已后台启动（PID %s）。\n日志：tail -f %s/logs/db-console.log\n' \
    "$(cat "$APP_HOME/logs/db-console.pid")" "$APP_HOME"
  exit 0
fi

exec "${CMD[@]}"
