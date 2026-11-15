#!/usr/bin/env bash
#
# db-console Linux 离线交付打包脚本（目标环境：麒麟 V10 / CentOS 7+）。
#
# 产出：packaging/out/db-console-<版本>-linux-<架构>.tar.gz（+ .sha256 校验文件）
#   内含可执行 JAR、随包 JDK 17、启动/停止脚本、systemd 服务模板、配置样例与部署说明。
#   随包 JDK 与服务器现有 JDK 8 共存，不写入系统路径。
#
# 用法：
#   ./packaging/package.sh                    # 打包所有已就绪架构（有 JDK 原始包的）
#   ./packaging/package.sh --arch aarch64     # 只打鲲鹏/飞腾（ARM64）
#   ./packaging/package.sh --arch x64         # 只打海光/兆芯（x86_64）
#   ./packaging/package.sh --build            # 强制重新 mvn package（默认仅 JAR 缺失时构建）
#   ./packaging/package.sh --no-build         # 只使用现有 target/db-console.jar
#
# JDK 17 原始包（毕昇/Kona/Dragonwell 等，Linux 版压缩包）放入 packaging/packages/，
# 文件名包含 aarch64 / x64（或 x86_64、amd64）即可被识别；也可用环境变量指定：
#   JDK_TARBALL_AARCH64=/path/to/bisheng-jdk17-...-aarch64.tar.gz
#   JDK_TARBALL_X64=/path/to/bisheng-jdk17-...-x64.tar.gz
# 下载渠道见 packaging/README.md。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PACKAGES_DIR="$SCRIPT_DIR/packages"
OUT_DIR="$SCRIPT_DIR/out"
JAR_PATH="$REPO_ROOT/target/db-console.jar"

ARCH_FLAG="all"
BUILD_MODE="auto"

die() { printf '错误：%s\n' "$*" >&2; exit 1; }

usage() {
  sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'
}

# 取 JDK 主版本号：处理 "17.0.12" 与旧式 "1.8.0_392" 两种格式
java_major() {
  "$1" -version 2>&1 | awk -F'"' '/version "/{print $2; exit}' \
    | cut -d. -f1 | sed 's/^1$/8/'
}

sha256_of() {
  if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else die "未找到 shasum/sha256sum，无法生成校验和"; fi
}

project_version() {
  sed -n '/<artifactId>db-console<\/artifactId>/{n;p;}' "$REPO_ROOT/pom.xml" \
    | sed -n 's/.*<version>\([^<]*\)<\/version>.*/\1/p' | head -n 1
}

# 在目录内按 glob 找压缩包；多个匹配时取修改时间最新的一个
find_tarball() {
  local dir="$1"; shift
  local newest="" f p count=0
  for f in "$@"; do
    for p in "$dir"/$f; do
      if [ -f "$p" ]; then
        count=$((count + 1))
        if [ -z "$newest" ] || [ "$p" -nt "$newest" ]; then
          newest="$p"
        fi
      fi
    done
  done
  [ "$count" -gt 0 ] || return 1
  if [ "$count" -gt 1 ]; then
    printf '警告：%s 下有多个匹配的 JDK 包，按修改时间取最新\n' "$*" >&2
  fi
  printf '%s\n' "$newest"
}

jdk_tarball_for() { # $1=aarch64|x64；输出原始包路径，找不到则返回 1
  local arch="$1"
  local env_var
  env_var="JDK_TARBALL_$(printf '%s' "$arch" | tr '[:lower:]' '[:upper:]')"
  if [ -n "${!env_var:-}" ]; then
    [ -f "${!env_var}" ] || die "$env_var 指向的文件不存在：${!env_var}"
    printf '%s\n' "${!env_var}"
    return 0
  fi
  if [ "$arch" = aarch64 ]; then
    find_tarball "$PACKAGES_DIR" '*aarch64*.tar.gz' '*arm64*.tar.gz' && return 0
  else
    find_tarball "$PACKAGES_DIR" '*x64*.tar.gz' '*x86_64*.tar.gz' '*amd64*.tar.gz' && return 0
  fi
  return 1
}

build_jar() {
  command -v mvn >/dev/null 2>&1 || die "未找到 mvn，请先安装 Maven 3.9+ 或用 --no-build 复用已有 JAR"
  # 构建机 JDK 防呆：Maven 构建受 enforcer 约束，JDK 8 机器上必然失败，提前给出可操作报错
  local jbin major
  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    jbin="${JAVA_HOME}/bin/java"
  else
    jbin="$(command -v java 2>/dev/null || true)"
  fi
  [ -n "$jbin" ] && [ -x "$jbin" ] \
    || die "构建机上未找到 java（JAVA_HOME / PATH），Maven 构建无法进行。请在装有 JDK 17 的机器上执行打包，或用 --no-build 复用已构建的 JAR"
  major=$(java_major "$jbin")
  [ "$major" -ge 17 ] 2>/dev/null \
    || die "构建 db-console 需要 JDK 17+，当前构建机 java 主版本为 ${major}。
请在装有 JDK 17 的机器上执行打包，或用 --no-build 复用已构建的 target/db-console.jar"
  printf '==> 构建可执行 JAR（跳过测试；发布前检查请另行执行 mvn clean verify）\n'
  (cd "$REPO_ROOT" && mvn -B -ntp -DskipTests clean package)
}

assemble() { # $1=arch $2=jdk 原始包
  local arch="$1" tarball="$2"
  local stage_root app
  stage_root=$(mktemp -d "${TMP_ROOT}/stage.XXXXXX")
  app="$stage_root/db-console"
  mkdir -p "$app/bin" "$app/config" "$app/systemd" "$app/jdk"

  cp "$JAR_PATH" "$app/db-console.jar"
  cp "$SCRIPT_DIR/bin/start.sh" "$SCRIPT_DIR/bin/stop.sh" "$SCRIPT_DIR/bin/restart.sh" \
     "$SCRIPT_DIR/bin/status.sh" "$app/bin/"
  chmod 755 "$app/bin/start.sh" "$app/bin/stop.sh" "$app/bin/restart.sh" "$app/bin/status.sh"
  cp "$SCRIPT_DIR/systemd/dbconsole.service" "$app/systemd/"
  cp "$SCRIPT_DIR/config/dbconsole.env.example" "$app/config/"
  cp "$SCRIPT_DIR/DEPLOY.md" "$app/README.md"
  cp "$REPO_ROOT/config/db-console.example.yml" "$REPO_ROOT/config/datasources.example.yml" "$app/config/"

  # 解压 JDK，规范目录名为 jdk/jdk17-<arch>（start.sh 依赖该命名，与发行商解包名解耦）
  local ex java_bin home_dir
  ex=$(mktemp -d "${TMP_ROOT}/jdk.XXXXXX")
  tar -xzf "$tarball" -C "$ex"
  java_bin=$(find "$ex" -type f -path '*/bin/java' | head -n 1)
  [ -n "$java_bin" ] || die "JDK 包内未找到 bin/java：$tarball"
  home_dir=$(cd "$(dirname "$java_bin")/.." && pwd)
  mv "$home_dir" "$app/jdk/jdk17-$arch"

  # 防呆：随包 JDK 必须 17+，避免误把 JDK 8 原始包打进去
  local bundled major
  bundled="$app/jdk/jdk17-$arch/bin/java"
  major=$(java_major "$bundled")
  [ "$major" -ge 17 ] 2>/dev/null \
    || die "随包 JDK 主版本为 \"${major}\"（${tarball}），需要 17+"

  local out_tar sum
  out_tar="$OUT_DIR/db-console-$(project_version)-linux-$arch.tar.gz"
  tar -czf "$out_tar" -C "$stage_root" db-console
  sum=$(sha256_of "$out_tar")
  printf '%s  %s\n' "$sum" "$(basename "$out_tar")" > "$out_tar.sha256"

  printf '✔ %s（%s）\n' "$out_tar" "$(du -h "$out_tar" | cut -f1)"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --arch) [ $# -ge 2 ] || die "--arch 需要参数：aarch64|x64|all"; ARCH_FLAG="$2"; shift 2 ;;
    --arch=*) ARCH_FLAG="${1#*=}"; shift ;;
    --build) BUILD_MODE="always"; shift ;;
    --no-build) BUILD_MODE="never"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "未知参数：$1（--help 查看用法）" ;;
  esac
done
case "$ARCH_FLAG" in aarch64|x64|all) ;; *) die "--arch 仅支持 aarch64|x64|all" ;; esac

mkdir -p "$PACKAGES_DIR" "$OUT_DIR"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

case "$BUILD_MODE" in
  always) build_jar ;;
  never) [ -f "$JAR_PATH" ] || die "未找到 ${JAR_PATH}（--no-build 模式需先构建）" ;;
  auto) [ -f "$JAR_PATH" ] || build_jar ;;
esac

ARCHES=()
MISSING=()
for arch in aarch64 x64; do
  [ "$ARCH_FLAG" = all ] || [ "$ARCH_FLAG" = "$arch" ] || continue
  t=$(jdk_tarball_for "$arch") || true
  if [ -n "${t:-}" ]; then
    ARCHES+=("$arch:$t")
  else
    MISSING+=("$arch")
  fi
done

if [ ${#ARCHES[@]} -eq 0 ]; then
  cat >&2 <<'EOF'
错误：未找到随包 JDK 17 原始包。请将 JDK 17 的 Linux 压缩包放入 packaging/packages/：
  - aarch64（鲲鹏/飞腾）：毕昇 JDK 17 aarch64（鲲鹏开发者社区 / mirrors.huawei.com）
  - x86_64（海光/兆芯）：毕昇 JDK 17 x64，或腾讯 Kona 17、阿里 Dragonwell 17
文件名含 aarch64 / x64（或 x86_64、amd64）即可识别；
也可用 JDK_TARBALL_AARCH64 / JDK_TARBALL_X64 环境变量指定绝对路径。
EOF
  exit 1
fi
for m in "${MISSING[@]:-}"; do
  [ -n "$m" ] && printf '提示：未找到 %s 的 JDK 包，跳过该架构\n' "$m" >&2
done

for entry in "${ARCHES[@]}"; do
  assemble "${entry%%:*}" "${entry#*:}"
done

printf '\n完成。产物目录：%s\n' "$OUT_DIR"
