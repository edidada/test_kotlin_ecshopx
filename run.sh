#!/usr/bin/env bash
# ECShopX Kotlin 版一键启动（Linux/macOS/WSL）。
# 首次或改了代码会先 mvn package，之后 java -jar 起 fat jar。
# 可覆盖：PORT（默认 18080）、JAVA_OPTS、SPRING_PROFILE。数据落在 ./data，删掉即重置演示库。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$ROOT/ecshopx-boot/target/ecshopx-boot-0.1.0-SNAPSHOT.jar"
PORT="${PORT:-18080}"
cd "$ROOT"

if ! java -version 2>&1 | grep -qE 'version "(1[7-9]|2[0-9])'; then
  echo "[run] 需要 JDK 17+，当前：$(java -version 2>&1 | head -1)" >&2
  exit 1
fi

# 构建产物缺失时才编译，避免每次启动都过一遍 7 模块
if [ ! -f "$JAR" ]; then
  echo "[run] jar 不存在，先构建（跳过测试）..."
  mvn -B -DskipTests package
fi

echo "[run] http://127.0.0.1:${PORT}  API 前缀 /api，健康检查 /actuator/health"
echo "[run] 演示账号 admin/admin123（后台）、13800000001/验证码 8888（前台）。Ctrl+C 停止。"
exec java ${JAVA_OPTS:-} -jar "$JAR" --server.port="$PORT" "$@"
