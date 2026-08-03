#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

DEFAULT_DEM="/media/extra/FABDEM/02_rawPyramidal"
if [ "$#" -eq 0 ]; then
    set -- "$DEFAULT_DEM"
fi

gradle run --quiet --args="$*"
