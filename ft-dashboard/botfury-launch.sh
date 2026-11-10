#!/usr/bin/env sh
set -eu

BOT_USERNAME="${1:-Bot01}"
BOT_PORT="${2:-8765}"
SERVER_IP="${3:-localhost}"
SERVER_PORT="${4:-25565}"
DISABLE_RENDERING="${5:-true}"

DASHBOARD_DIR=$(CDPATH= cd "$(dirname "$0")" && pwd)
BOTFURY_ROOT=$(CDPATH= cd "$DASHBOARD_DIR/.." && pwd)
MOD_DIR="$BOTFURY_ROOT/minecraft-bots/botfury-mod"
RUN_DIR="run/$BOT_USERNAME"

if [ ! -f "$MOD_DIR/gradlew" ]; then
    echo "BotFury Gradle project not found: $MOD_DIR"
    exit 2
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    PATH="$JAVA_HOME/bin:$PATH"
fi
if ! command -v java >/dev/null 2>&1; then
    echo "Java 17 or newer was not found. Install a JDK 17+ and rerun the dashboard."
    exit 3
fi

MAX_HEAP_MB=768
MIN_HEAP_MB=128
ACTIVE_PROCESSORS=1
MAX_FPS=10
RENDER_DISTANCE=2
SIMULATION_DISTANCE=5
if [ "$DISABLE_RENDERING" != "false" ] && [ "$DISABLE_RENDERING" != "FALSE" ]; then
    DISABLE_RENDERING=true
fi

echo "Launching $BOT_USERNAME on BotFury port $BOT_PORT"
echo "Target server: $SERVER_IP:$SERVER_PORT"
echo "Mod project: $MOD_DIR"

cd "$MOD_DIR"
ABS_RUN_DIR="$MOD_DIR/$RUN_DIR"
mkdir -p "$ABS_RUN_DIR"
cat > "$ABS_RUN_DIR/options.txt" <<EOF
version:3465
autoJump:false
enableVsync:false
maxFps:$MAX_FPS
renderDistance:$RENDER_DISTANCE
simulationDistance:$SIMULATION_DISTANCE
entityDistanceScaling:0.5
graphicsMode:1
ao:false
renderClouds:"false"
particles:2
mipmapLevels:0
biomeBlendRadius:0
bobView:false
entityShadows:false
fovEffectScale:0.0
darknessEffectScale:0.0
soundCategory_master:0.0
soundCategory_music:0.0
soundCategory_weather:0.0
soundCategory_block:0.0
soundCategory_hostile:0.0
soundCategory_neutral:0.0
soundCategory_player:0.0
soundCategory_ambient:0.0
soundCategory_voice:0.0
showAutosaveIndicator:false
syncChunkWrites:false
tutorialStep:none
EOF

exec nice -n 10 sh ./gradlew --no-daemon --no-problems-report \
    -Dbotfury.port="$BOT_PORT" \
    -Dbotfury.offlineUsername="$BOT_USERNAME" \
    -Dbotfury.username="$BOT_USERNAME" \
    -Dbotfury.serverIp="$SERVER_IP" \
    -Dbotfury.serverPort="$SERVER_PORT" \
    -Dbotfury.autoJoin=true \
    -Dbotfury.disableRendering="$DISABLE_RENDERING" \
    -Dbotfury.runDir="$RUN_DIR" \
    -Dbotfury.minHeapMb="$MIN_HEAP_MB" \
    -Dbotfury.maxHeapMb="$MAX_HEAP_MB" \
    -Dbotfury.activeProcessors="$ACTIVE_PROCESSORS" \
    -Dbotfury.maxFps="$MAX_FPS" \
    -Dbotfury.renderDistance="$RENDER_DISTANCE" \
    -Dbotfury.simulationDistance="$SIMULATION_DISTANCE" \
    runClient
