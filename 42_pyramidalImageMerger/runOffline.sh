#!/usr/bin/env bash
set -euo pipefail

DEFAULT_DESTINATION="/samples/datasets/googleEarth/toplevel"
DEFAULT_DELTA="/tmp/matrix/pyramidalImage"

if [ "$#" -eq 0 ]; then
    ARGS="--offline $DEFAULT_DESTINATION $DEFAULT_DELTA"
elif [ "$#" -eq 2 ]; then
    ARGS="--offline $1 $2"
else
    ARGS="$*"
fi

gradle run --quiet --args="$ARGS"
