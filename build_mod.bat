@echo off
setlocal

echo =========================================
echo Building BotFury Mod for Minecraft
echo =========================================

echo 1/2 Building the Fabric mod...
cd minecraft-bots\botfury-mod
call gradlew.bat build

echo 2/2 Build complete!
cd ..\..

set ARTIFACT=minecraft-bots\botfury-mod\build\libs\botfury-0.1.0.jar
if exist "%ARTIFACT%" (
    echo =========================================
    echo SUCCESS! The BotFury mod has been built.
    echo JAR file: %ARTIFACT%
    echo Copy this file to your Minecraft 'mods' folder.
    echo =========================================
) else (
    echo FAILURE: The JAR file was not found.
    exit /b 1
)
