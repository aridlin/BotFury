@echo off
setlocal

set "BOT_USERNAME=%~1"
set "BOT_PORT=%~2"
set "SERVER_IP=%~3"
set "SERVER_PORT=%~4"
set "DISABLE_RENDERING=%~5"

set "DASHBOARD_DIR=%~dp0"
for %%I in ("%DASHBOARD_DIR%..") do set "BOTFURY_ROOT=%%~fI"
set "MOD_DIR=%BOTFURY_ROOT%\minecraft-bots\botfury-mod"
set "RUN_DIR=run\%BOT_USERNAME%"

if not exist "%MOD_DIR%\gradlew.bat" (
    echo BotFury Gradle project not found: %MOD_DIR%
    exit /b 2
)

set "BF_JAVA_HOME="
if exist "C:\Program Files\Eclipse Adoptium\jdk-21.0.4.7-hotspot\bin\java.exe" set "BF_JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.4.7-hotspot"
if not defined BF_JAVA_HOME for /d %%J in ("C:\Program Files\Eclipse Adoptium\jdk-*") do if exist "%%~fJ\bin\java.exe" set "BF_JAVA_HOME=%%~fJ"
if not defined BF_JAVA_HOME for /d %%J in ("C:\Program Files\Java\jdk-*") do if exist "%%~fJ\bin\java.exe" set "BF_JAVA_HOME=%%~fJ"
if not defined BF_JAVA_HOME for /d %%J in ("C:\Program Files\Microsoft\jdk-*") do if exist "%%~fJ\bin\java.exe" set "BF_JAVA_HOME=%%~fJ"

if defined BF_JAVA_HOME (
    set "JAVA_HOME=%BF_JAVA_HOME%"
)

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "PATH=%JAVA_HOME%\bin;%PATH%"
where java >nul 2>nul
if errorlevel 1 (
    echo Java 17 or newer was not found. Install a JDK 17+ and rerun the dashboard.
    exit /b 3
)

set "MAX_HEAP_MB=768"
set "MIN_HEAP_MB=128"
set "ACTIVE_PROCESSORS=1"
set "MAX_FPS=10"
set "RENDER_DISTANCE=2"
set "SIMULATION_DISTANCE=5"
if /I not "%DISABLE_RENDERING%"=="false" set "DISABLE_RENDERING=true"

echo Launching %BOT_USERNAME% on BotFury port %BOT_PORT%
echo Target server: %SERVER_IP%:%SERVER_PORT%
echo Mod project: %MOD_DIR%

cd /d "%MOD_DIR%"
set "ABS_RUN_DIR=%MOD_DIR%\%RUN_DIR%"
if not exist "%ABS_RUN_DIR%" mkdir "%ABS_RUN_DIR%"
> "%ABS_RUN_DIR%\options.txt" (
    echo version:3465
    echo autoJump:false
    echo enableVsync:false
    echo maxFps:%MAX_FPS%
    echo renderDistance:%RENDER_DISTANCE%
    echo simulationDistance:%SIMULATION_DISTANCE%
    echo entityDistanceScaling:0.5
    echo graphicsMode:1
    echo ao:false
    echo renderClouds:"false"
    echo particles:2
    echo mipmapLevels:0
    echo biomeBlendRadius:0
    echo bobView:false
    echo entityShadows:false
    echo fovEffectScale:0.0
    echo darknessEffectScale:0.0
    echo soundCategory_master:0.0
    echo soundCategory_music:0.0
    echo soundCategory_weather:0.0
    echo soundCategory_block:0.0
    echo soundCategory_hostile:0.0
    echo soundCategory_neutral:0.0
    echo soundCategory_player:0.0
    echo soundCategory_ambient:0.0
    echo soundCategory_voice:0.0
    echo showAutosaveIndicator:false
    echo syncChunkWrites:false
    echo tutorialStep:none
)

call gradlew.bat --no-daemon --no-problems-report ^
    -Dbotfury.port=%BOT_PORT% ^
    -Dbotfury.offlineUsername=%BOT_USERNAME% ^
    -Dbotfury.username=%BOT_USERNAME% ^
    -Dbotfury.serverIp=%SERVER_IP% ^
    -Dbotfury.serverPort=%SERVER_PORT% ^
    -Dbotfury.autoJoin=true ^
    -Dbotfury.disableRendering=%DISABLE_RENDERING% ^
    -Dbotfury.runDir=%RUN_DIR% ^
    -Dbotfury.minHeapMb=%MIN_HEAP_MB% ^
    -Dbotfury.maxHeapMb=%MAX_HEAP_MB% ^
    -Dbotfury.activeProcessors=%ACTIVE_PROCESSORS% ^
    -Dbotfury.maxFps=%MAX_FPS% ^
    -Dbotfury.renderDistance=%RENDER_DISTANCE% ^
    -Dbotfury.simulationDistance=%SIMULATION_DISTANCE% ^
    runClient
exit /b %ERRORLEVEL%
