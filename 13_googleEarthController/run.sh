#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -eq 0 ]; then
    gradle run --quiet
else
    gradle run --quiet --args="$*"
fi
