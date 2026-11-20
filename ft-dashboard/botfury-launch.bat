@echo off
setlocal DisableDelayedExpansion
set "BOT_USERNAME=%~1"
set "BOT_PORT=%~2"
set "SERVER_IP=%~3"
set "SERVER_PORT=%~4"
set "DISABLE_RENDERING=%~5"
set "AUTO_JOIN=%~6"
if not defined BOT_USERNAME set "BOT_USERNAME=Bot01"
if not defined BOT_PORT set "BOT_PORT=8765"
if not defined SERVER_IP set "SERVER_IP=localhost"
if not defined SERVER_PORT set "SERVER_PORT=25565"
if not defined DISABLE_RENDERING set "DISABLE_RENDERING=true"
if not defined AUTO_JOIN set "AUTO_JOIN=true"

set "BOTFURY_ROOT=%~dp0.."
set "MOD_DIR=%BOTFURY_ROOT%\minecraft-bots\botfury-mod"
set "RUNTIME=%MOD_DIR%\build\botfury-client"
set "RUN_DIR=%MOD_DIR%\run\%BOT_USERNAME%"
if not exist "%RUNTIME%\client.args" goto prepare
if not exist "%RUNTIME%\java-path.txt" goto prepare
set /p "JAVA_EXEC="<"%RUNTIME%\java-path.txt"
if not exist "%JAVA_EXEC%" goto prepare

if not exist "%RUN_DIR%\tmp" mkdir "%RUN_DIR%\tmp"
cd /d "%RUN_DIR%" || exit /b 73
if not exist options.txt (
    copy "%BOTFURY_ROOT%\config\bot-options.txt" options.txt >nul || exit /b 73
)

"%JAVA_EXEC%" ^
    "-Djava.io.tmpdir=%CD%\tmp" ^
    "-Dbotfury.port=%BOT_PORT%" ^
    -Dbotfury.managedHttp=true -Dbotfury.strictPort=true -Dbotfury.httpBindAddress=127.0.0.1 ^
    "-Dbotfury.offlineUsername=%BOT_USERNAME%" ^
    "-Dbotfury.serverIp=%SERVER_IP%" ^
    "-Dbotfury.serverPort=%SERVER_PORT%" ^
    "-Dbotfury.autoJoin=%AUTO_JOIN%" ^
    "-Dbotfury.disableRendering=%DISABLE_RENDERING%" ^
    -Dbotfury.maxFps=10 "@%RUNTIME%\client.args" --username "%BOT_USERNAME%"
exit /b %ERRORLEVEL%

:prepare
echo Prepare the BotFury Minecraft runtime with: gradlew.bat prepareBotFuryClient 1>&2
echo Run this in "%MOD_DIR%" 1>&2
exit /b 78
