#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
    echo 'Usage: ./run.sh <pyramidalImageFolder>' >&2
    exit 1
fi

gradle run --quiet --args="$1"
