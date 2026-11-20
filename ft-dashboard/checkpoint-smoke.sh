#!/bin/sh
set -eu

cd "$(dirname "$0")"
if [ "${1:-}" = --install ]; then
    sudo pacman -S --needed podman criu crun
    shift
fi
sudo -v
sudo -n criu check
sh botfury-container.sh prepare

port="${1:-38765}"
username="${2:-CriuSmoke}"
report="$PWD/build/checkpoint-smoke.log"
mkdir -p "$(dirname "$report")"
tmp=$(mktemp -d)
job=
cleanup() {
    if [ -n "$job" ]; then
        sudo -v
        sh botfury-container.sh stop "$username" "$port" || true
        wait "$job" || true
    fi
    rm -rf -- "$tmp"
}
trap cleanup EXIT
trap 'exit 130' INT TERM
: > "$report"

cycle() {
    number=$1
    marker="$tmp/ready-$number"
    sudo -v
    sh botfury-container.sh run "$username" "$port" true "$marker" > "$tmp/run-$number" 2>&1 &
    job=$!
    for tick in $(seq 1 240); do
        kill -0 "$job" 2>/dev/null || break
        [ ! -e "$marker" ] || break
        sleep 1
    done
    if [ ! -e "$marker" ]; then
        cat "$tmp/run-$number" | tee -a "$report"
        exit 1
    fi
    status=$(curl --fail --silent --show-error --max-time 3 "http://127.0.0.1:$port/status")
    printf '%s\n' "$status" | tee -a "$report"
    sh botfury-container.sh stop "$username" "$port"
    wait "$job"
    job=
    grep '^BOTFURY_CHECKPOINT_VERIFIED ' "$tmp/run-$number" | tail -1 > "$tmp/proof-$number"
    test -s "$tmp/proof-$number"
    runtime=$(cut -d ' ' -f 2 "$tmp/proof-$number")
    case "$status" in
        *'"connected":false,"ready":true'*'"runtimeId":"'"$runtime"'"'*) ;;
        *) printf 'The published endpoint did not report the restored title-screen JVM.\n' >&2; exit 1 ;;
    esac
    cat "$tmp/proof-$number" | tee -a "$report"
}

cycle 1
cycle 2
cmp "$tmp/proof-1" "$tmp/proof-2"
printf 'PASS: two restores resumed the same Minecraft JVM from its saved title screen.\n' | tee -a "$report"
