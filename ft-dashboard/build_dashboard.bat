@echo off
setlocal
cd /d "%~dp0"

if not exist build mkdir build

where rc >nul 2>nul
if not errorlevel 1 (
    rc /nologo /fo build\botfury.res botfury.rc
) else (
    where llvm-rc >nul 2>nul
    if errorlevel 1 (
        echo No resource compiler found. Install the Windows SDK or LLVM with llvm-rc.
        exit /b 1
    )
    llvm-rc /fo build\botfury.res botfury.rc
)
if errorlevel 1 exit /b 1

where clang++ >nul 2>nul
if %errorlevel%==0 (
    clang++ dashboard.cpp build\botfury.res -o build\botfury-dashboard.exe -std=c++17 -lws2_32 -lshell32
    if errorlevel 1 exit /b 1
    exit /b 0
)

where cl >nul 2>nul
if %errorlevel%==0 (
    cl /EHsc /std:c++17 dashboard.cpp build\botfury.res /Fe:build\botfury-dashboard.exe ws2_32.lib shell32.lib
    if errorlevel 1 exit /b 1
    exit /b 0
)

echo No supported C++ compiler found. Install clang++ or run from a Visual Studio Developer Command Prompt.
exit /b 1
