#!/usr/bin/env bash
# Mini Claude Java — Spring Boot 服务启动脚本
# 用法:
#   ./run.sh                         # 启动 HTTP 服务 (8080)
#   ./run.sh --cli --yolo "读 README" # 旧 CLI 一次性模式
#   ./run.sh --help

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

JAR="$ROOT/target/miniclaude-java-1.0.0.jar"

# 加载 .env（不覆盖已在 shell 里 export 的变量）
if [[ -f "$ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/.env"
  set +a
fi

# 没有 jar 就先打包
if [[ ! -f "$JAR" ]]; then
  echo "[run] 未找到 $JAR，正在 mvn package ..."
  if ! command -v mvn >/dev/null 2>&1; then
    echo "[run] 错误: 未找到 mvn，请先安装 Maven 或手动执行: mvn -q package" >&2
    exit 1
  fi
  mvn -q -DskipTests package
fi

if [[ "${1:-}" == "--cli" ]]; then
  if [[ -z "${MOONSHOT_API_KEY:-}${KIMI_API_KEY:-}${ANTHROPIC_API_KEY:-}${OPENAI_API_KEY:-}" ]]; then
    echo "[run] 错误: CLI 模式需要 API Key（MOONSHOT_API_KEY 等）" >&2
    exit 1
  fi
  exec java -jar "$JAR" "$@"
fi

echo "[run] 启动 Spring Boot: http://127.0.0.1:${SERVER_PORT:-8080}"
exec java -jar "$JAR" "$@"
