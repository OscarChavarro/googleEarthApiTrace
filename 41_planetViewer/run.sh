#!/usr/bin/env bash
set -euo pipefail
DEFAULT_IMAGE="/samples/datasets/googleEarth"
if [ "$#" -gt 0 ]; then
    ARGS="$*"
else
    ARGS="$DEFAULT_IMAGE"
fi
gradle run --quiet --args="$ARGS"
