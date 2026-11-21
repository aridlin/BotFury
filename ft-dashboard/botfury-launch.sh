#!/usr/bin/env sh
set -eu

BOT_USERNAME="${1:-Bot01}"
BOT_PORT="${2:-8765}"
SERVER_IP="${3:-localhost}"
SERVER_PORT="${4:-25565}"
DISABLE_RENDERING="${5:-true}"
AUTO_JOIN="${6:-true}"

case "$BOT_USERNAME" in
    *[!a-zA-Z0-9_]*) echo "Invalid Minecraft username" >&2; exit 64 ;;
esac

DASHBOARD_DIR=$(CDPATH= cd "$(dirname "$0")" && pwd)
BOTFURY_ROOT=$(CDPATH= cd "$DASHBOARD_DIR/.." && pwd)
MOD_DIR="$BOTFURY_ROOT/minecraft-bots/botfury-mod"
RUNTIME="$MOD_DIR/build/botfury-client"
if [ -s "$BOTFURY_ROOT/config/client-runtime.path" ]; then
    IFS= read -r RUNTIME < "$BOTFURY_ROOT/config/client-runtime.path"
fi
RUNTIME="${BOTFURY_RUNTIME_DIR:-$RUNTIME}"
RUN_DIR="$MOD_DIR/run/$BOT_USERNAME"

if [ ! -s "$RUNTIME/client.args" ] || [ ! -s "$RUNTIME/java-path.txt" ]; then
    echo "Prepare the BotFury Minecraft runtime with: sh ./gradlew prepareBotFuryClient" >&2
    echo "Run this in $MOD_DIR" >&2
    exit 78
fi
IFS= read -r JAVA_EXEC < "$RUNTIME/java-path.txt"
if [ ! -x "$JAVA_EXEC" ]; then
    echo "Prepared Java executable is unavailable. Run prepareBotFuryClient again." >&2
    exit 78
fi

mkdir -p "$RUN_DIR/tmp"
cd "$RUN_DIR"
if [ ! -e options.txt ]; then
    cp "$BOTFURY_ROOT/config/bot-options.txt" options.txt
fi

exec nice -n 10 "$JAVA_EXEC" \
    "-Djava.io.tmpdir=$RUN_DIR/tmp" \
    "-Dbotfury.advancedModulesFile=${BOTFURY_ADVANCED_MODULES_FILE:-$BOTFURY_ROOT/config/.advanced-modules}" \
    "-Dbotfury.port=$BOT_PORT" \
    -Dbotfury.managedHttp=true -Dbotfury.strictPort=true -Dbotfury.httpBindAddress=127.0.0.1 \
    "-Dbotfury.offlineUsername=$BOT_USERNAME" \
    "-Dbotfury.serverIp=$SERVER_IP" \
    "-Dbotfury.serverPort=$SERVER_PORT" \
    "-Dbotfury.autoJoin=$AUTO_JOIN" \
    "-Dbotfury.disableRendering=$DISABLE_RENDERING" \
    -Dbotfury.maxFps=10 "@$RUNTIME/client.args" --username "$BOT_USERNAME"
