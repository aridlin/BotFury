#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
mkdir -p build

PKG_FLAGS=""
if command -v pkg-config >/dev/null 2>&1; then
    PKG_FLAGS="$(pkg-config --cflags --libs cairo x11 2>/dev/null || true)"
fi

c++ dashboard.cpp -o build/botfury-dashboard -std=c++17 -pthread ${PKG_FLAGS}
