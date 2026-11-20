#!/usr/bin/env sh
set -eu

podman_local() {
    if [ "$(id -u)" = 0 ]; then
        command podman "$@"
    else
        sudo -n -- podman "$@"
    fi
}

fail() {
    printf '%s\n' "$*" >&2
    exit 1
}

log_follower=
finish() {
    if [ -n "$log_follower" ]; then
        kill "$log_follower" 2>/dev/null || true
        wait "$log_follower" 2>/dev/null || true
    fi
}
trap finish EXIT

command -v podman >/dev/null || fail "Install Podman and CRIU to use BotFury containers."
operation="${1:-}"
image=localhost/botfury-client:1.20.1
if [ "$operation" = prepare ]; then
    dashboard=$(CDPATH= cd "$(dirname "$0")" && pwd)
    root=$(CDPATH= cd "$dashboard/.." && pwd)
    mod="$root/minecraft-bots/botfury-mod"
    stage=$(mktemp -d)
    trap 'rm -rf -- "$stage"' EXIT
    mkdir "$stage/mod"
    cp "$dashboard/container/Containerfile" "$dashboard/container/entrypoint.sh" "$stage/"
    cp "$root/config/bot-options.txt" "$stage/options.txt"
    cp -R "$mod/gradle" "$mod/src" "$mod/gradlew" "$mod/build.gradle" \
        "$mod/settings.gradle" "$mod/gradle.properties" "$stage/mod/"
    podman_local build --tag "$image" --file "$stage/Containerfile" "$stage"
    exit
fi

username="${2:-}"
port="${3:-}"
render="${4:-true}"
ready_file="${5:-}"
case "$username" in
    ''|*[!a-zA-Z0-9_]*) fail "Invalid Minecraft username." ;;
esac
[ "${#username}" -le 16 ] || fail "Minecraft usernames can contain at most 16 characters."
case "$port" in
    ''|0*|*[!0-9]*) fail "Invalid bot port." ;;
esac
[ "$port" -ge 1024 ] && [ "$port" -le 55535 ] || fail "Container bot ports must be 1024–55535."
case "$render" in true|false) ;; *) fail "Invalid render mode." ;; esac
vnc_port=$((port + 10000))
lowercase=$(printf '%s' "$username" | tr '[:upper:]' '[:lower:]')
container="botfury-$port-$lowercase"
checkpoint="localhost/botfury-checkpoint:$port-$lowercase-$render"
label=org.botfury.slot

if [ "$operation" = view ]; then
    command -v vncviewer >/dev/null || fail "Install TigerVNC Viewer to open bot windows."
    exec vncviewer -RemoteResize=0 "127.0.0.1::$vnc_port"
fi

# Treat engine errors differently from a missing container. Only manage labeled slots.
bootstrap="$container-bootstrap"
state() {
    target=$1
    if podman_local container exists "$target"; then
        metadata=$(podman_local inspect --type container --format \
            '{{index .Config.Labels "org.botfury.slot"}}|{{.State.Status}}' "$target") || fail "Could not inspect the BotFury container."
        case "$metadata" in
            "$container|"*) printf '%s\n' "${metadata#*|}" ;;
            *) fail "Container name is in use by another workload." ;;
        esac
    else
        rc=$?
        [ "$rc" -eq 1 ] || fail "Cannot access rootful Podman. Run sudo -v in the dashboard terminal."
        printf '%s\n' missing
    fi
}

stop_if_running() {
    target=$1
    how=$2
    status=$(state "$target")
    case "$status" in
        running|paused)
            if [ "$how" = kill ]; then
                podman_local container kill "$target"
            else
                podman_local container stop --time 5 "$target"
            fi
            ;;
    esac
}

cancelled() {
    if [ -n "$ready_file" ] && [ -f "$ready_file.cancel" ]; then
        stop_if_running "$container" kill
        stop_if_running "$bootstrap" kill
        exit 0
    fi
}

clear_stopped() {
    target=$1
    status=$2
    case "$status" in
        missing) ;;
        exited|stopped|configured|created|checkpointed) podman_local container rm "$target" ;;
        *) fail "Container is in state $status. Stop it before starting again." ;;
    esac
}

read_runtime_id() {
    target=$1
    response=$(podman_local exec "$target" curl -fsS --max-time 1 http://127.0.0.1:8765/status 2>/dev/null) || return 1
    runtime_id=$(printf '%s\n' "$response" | sed -nE 's/.*"runtimeId":"([a-f0-9-]{36})".*/\1/p')
    [ -n "$runtime_id" ] || return 1
    printf '%s\n' "$runtime_id"
}

capture_checkpoint() {
    status=$(state "$bootstrap")
    if [ "$status" != running ]; then
        clear_stopped "$bootstrap" "$status"
        podman_local container run --detach --name "$bootstrap" --label "$label=$container" \
            "$image" "$username" "$render"
    fi
    ready=false
    for attempt in $(seq 1 120); do
        cancelled
        status=$(state "$bootstrap")
        [ "$status" = running ] || break
        response=$(podman_local exec "$bootstrap" curl -fsS --max-time 1 http://127.0.0.1:8765/status 2>/dev/null || true)
        case "$response" in
            *'"connected":false,"ready":true'*) ready=true; break ;;
        esac
        sleep 1
    done
    if [ "$ready" = false ]; then
        podman_local logs --tail 60 "$bootstrap" >&2 || true
        stop_if_running "$bootstrap" stop
        fail "Minecraft did not reach the title screen."
    fi
    expected_runtime=$(read_runtime_id "$bootstrap") || fail "The BotFury image lacks checkpoint verification. Rebuild the image."
    podman_local exec "$bootstrap" sh -c 'printf "%s" "$1" > /game/.botfury-runtime-id' sh "$expected_runtime"
    # The title screen can briefly contact Realms or session services.
    quiet=false
    for attempt in $(seq 1 20); do
        cancelled
        if podman_local exec "$bootstrap" awk 'FNR > 1 && $4 == "01" { open = 1 } END { exit open }' \
                /proc/net/tcp /proc/net/tcp6; then
            quiet=true
            break
        fi
        sleep 1
    done
    if [ "$quiet" = false ]; then
        stop_if_running "$bootstrap" stop
        fail "Minecraft still has open TCP connections. A title-screen checkpoint needs idle sockets."
    fi
    cancelled
    if ! podman_local container checkpoint --file-locks --create-image "$checkpoint" "$bootstrap"; then
        podman_local logs --tail 60 "$bootstrap" >&2 || true
        stop_if_running "$bootstrap" stop
        fail "Checkpoint failed. See Podman and CRIU diagnostics."
    fi
    podman_local container rm "$bootstrap"
}

case "$operation" in
    kill|stop)
        stop_if_running "$container" "$operation"
        stop_if_running "$bootstrap" "$operation"
        ;;
    run)
        umask 077
        lock_dir="${XDG_RUNTIME_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}}/botfury"
        mkdir -p "$lock_dir"
        exec 9>"$lock_dir/$container.lock"
        flock -n 9 || fail "This BotFury slot is already being prepared."
        cancelled
        current=$(state "$container")
        if [ "$current" != running ]; then
            clear_stopped "$container" "$current"
            if podman_local image exists "$checkpoint"; then
                bootstrap_status=$(state "$bootstrap")
                clear_stopped "$bootstrap" "$bootstrap_status"
            else
                rc=$?
                [ "$rc" -eq 1 ] || fail "Could not check the local checkpoint store."
                podman_local image exists "$image" || fail "Prepare the BotFury container image first."
                capture_checkpoint
            fi
            cancelled
            podman_local container restore --file-locks --name "$container" \
                --publish "127.0.0.1:$port:8765" --publish "127.0.0.1:$vnc_port:5900" "$checkpoint"
            expected_runtime=$(podman_local exec "$container" cat /game/.botfury-runtime-id) || {
                stop_if_running "$container" stop
                fail "Checkpoint predates runtime verification. Recreate this slot’s checkpoint."
            }
            actual_runtime=
            for attempt in $(seq 1 15); do
                cancelled
                if actual_runtime=$(read_runtime_id "$container"); then break; fi
                sleep 1
            done
            if [ -z "$actual_runtime" ] || [ "$actual_runtime" != "$expected_runtime" ]; then
                stop_if_running "$container" stop
                fail "The restored bot did not report the checkpoint’s JVM identity."
            fi
            printf 'BOTFURY_CHECKPOINT_VERIFIED %s\n' "$actual_runtime"
        fi
        cancelled
        exec 9>&-
        if [ -n "$ready_file" ]; then
            : > "$ready_file"
        fi
        printf '%s\n' BOTFURY_CONTAINER_READY
        podman_local logs --follow --tail 80 "$container" &
        log_follower=$!
        result=$(podman_local container wait "$container")
        case "$result" in ''|*[!0-9]*) fail "Invalid container exit status." ;; esac
        exit "$result"
        ;;
    *) fail "Usage: botfury-container.sh prepare | {run|stop|kill|view} USER PORT [DISABLE_RENDERING]" ;;
esac
