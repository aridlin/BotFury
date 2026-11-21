#!/bin/bash
set -e
cd -- "$(dirname "$0")"

echo "========================================="
echo "Building BotFury Mod for Minecraft"
echo "========================================="

echo "1/2 Building the Fabric mod..."
cd minecraft-bots/botfury-mod
chmod +x gradlew
./gradlew build

echo "2/2 Build complete!"
cd ../..

ARTIFACT="minecraft-bots/botfury-mod/build/libs/botfury-0.3.3.jar"
if [ -f "$ARTIFACT" ]; then
    echo "========================================="
    echo "SUCCESS! The BotFury mod has been built."
    echo "JAR file: $ARTIFACT"
    echo "Copy this file to your Minecraft 'mods' folder."
    echo "========================================="
else
    echo "FAILURE: The JAR file was not found."
    exit 1
fi
