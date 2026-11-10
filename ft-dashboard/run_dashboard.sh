#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"

if [ ! -x build/botfury-dashboard ]; then
    sh build_dashboard.sh
fi

if [ "$#" -eq 0 ]; then
    shell_name=$(basename "${SHELL:-}")
    case "$shell_name" in
        bash|zsh)
            set -- --web
            ;;
    esac
fi

exec build/botfury-dashboard "$@"
