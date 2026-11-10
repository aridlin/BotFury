@echo off
setlocal
cd /d "%~dp0"

if not exist build mkdir build

where clang++ >nul 2>nul
if %errorlevel%==0 (
    clang++ dashboard.cpp -o build\botfury-dashboard.exe -std=c++17 -lws2_32 -lshell32
    exit /b %errorlevel%
)

where cl >nul 2>nul
if %errorlevel%==0 (
    cl /EHsc /std:c++17 dashboard.cpp /Fe:build\botfury-dashboard.exe ws2_32.lib shell32.lib
    exit /b %errorlevel%
)

echo No supported C++ compiler found. Install clang++ or run from a Visual Studio Developer Command Prompt.
exit /b 1
