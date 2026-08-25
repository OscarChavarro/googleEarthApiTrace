#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -eq 0 ]; then
    gradle --console=plain run --quiet
else
    gradle --console=plain run --quiet --args="$*"
fi
