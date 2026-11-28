#!/usr/bin/env bash
set -euo pipefail

minimum_samples="$1"
pid="$(cat "$RUNNER_TEMP/survival.pid")"
evidence="$RUNNER_TEMP/survival-benchmark"
while true; do
    if [[ -s "$evidence/orders.json" && -f "$evidence/progress.jsonl" ]] &&
       [[ "$(wc -l < "$evidence/progress.jsonl")" -ge "$minimum_samples" ]]; then
        echo "ready=true" >> "$GITHUB_OUTPUT"
        break
    fi
    if [[ -f "$RUNNER_TEMP/survival.exit" ]]; then
        break
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
        [[ -f "$RUNNER_TEMP/survival.exit" ]]
        break
    fi
    sleep 3
done
tail -n 12 "$RUNNER_TEMP/survival-runner.log"
