#!/usr/bin/env bash
set -euo pipefail
DEFAULT_DESTINATION="/samples/datasets/googleEarth/secondLevelStep1"
DEFAULT_DELTA="/samples/datasets/googleEarth/sesion1Madrid"
if [ "$#" -eq 0 ]; then
    ARGS="$DEFAULT_DESTINATION $DEFAULT_DELTA"
elif [ "$#" -eq 2 ]; then
    ARGS="$1 $2"
else
    ARGS="$*"
fi
gradle run --quiet --args="$ARGS"
