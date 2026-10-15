@echo off
rem ECShopX Kotlin 版一键启动（Windows）。
rem 首次或改了代码会先 mvn package，之后 java -jar 起 fat jar。
rem 可覆盖：PORT（默认 18080）、JAVA_OPTS、SPRING_PROFILE。数据落在 .\data，删掉即重置演示库。
setlocal
chcp 65001 >nul

set "ROOT=%~dp0"
set "JAR=%ROOT%ecshopx-boot\target\ecshopx-boot-0.1.0-SNAPSHOT.jar"
if not defined PORT set "PORT=18080"

cd /d "%ROOT%"

rem 构建产物缺失时才编译，避免每次启动都过一遍 7 模块
if not exist "%JAR%" (
  echo [run] jar 不存在，先构建（跳过测试）...
  call mvn -B -DskipTests package || exit /b 1
)

echo [run] http://127.0.0.1:%PORT%  API 前缀 /api，健康检查 /actuator/health
echo [run] 演示账号 admin/admin123（后台）、13800000001/验证码 8888（前台）。Ctrl+C 停止。
java %JAVA_OPTS% -jar "%JAR%" --server.port=%PORT% %*
