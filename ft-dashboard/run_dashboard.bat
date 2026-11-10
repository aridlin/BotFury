@echo off
setlocal
cd /d "%~dp0"

if not exist build\botfury-dashboard.exe (
    call build_dashboard.bat
    if errorlevel 1 (
        echo.
        echo Failed to build BotFury dashboard.
        pause
        exit /b 1
    )
)

start "BotFury Dashboard" "%~dp0build\botfury-dashboard.exe" %*
