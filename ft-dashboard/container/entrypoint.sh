#!/bin/sh
set -eu

if [ ! -e options.txt ]; then
    cp /opt/botfury/options.txt options.txt
fi
Xtigervnc :1 -geometry 960x540 -depth 24 -rfbport 5900 -SecurityTypes None \
    -localhost no -nolisten tcp -ac -extension MIT-SHM &
display_pid=$!
trap 'kill "$display_pid" 2>/dev/null || true' EXIT
for attempt in 1 2 3 4 5 6 7 8 9 10; do
    if xdpyinfo >/dev/null 2>&1; then break; fi
    sleep 1
done
xdpyinfo >/dev/null
java -Djava.io.tmpdir=/game/tmp -Dbotfury.port=8765 -Dbotfury.managedHttp=true -Dbotfury.strictPort=true \
    -Dbotfury.autoJoin=false "-Dbotfury.offlineUsername=$1" "-Dbotfury.disableRendering=$2" \
    -Dbotfury.maxFps=10 @/opt/botfury/mod/build/botfury-client/client.args --username "$1" &
game_pid=$!
trap 'kill -TERM "$game_pid" 2>/dev/null || true; wait "$game_pid" || true; exit 0' TERM INT
wait "$game_pid"
