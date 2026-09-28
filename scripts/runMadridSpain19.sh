#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

export MADRID_JOB_SOURCE="spain-madrid19"
export MADRID_SCRIPT_LABEL="runMadridSpain19"
export MADRID_GRID_LABEL="16x16"
export MADRID_LEVEL="19"
export MADRID_TILE_LAT_SPAN="0.03125"
export MADRID_TILE_LON_SPAN="0.03125"
export MADRID_ZIGZAG_ALTITUDE="400"
export MADRID_ZIGZAG_STEP="12.5"
export MADRID_ZIGZAG_RANGE="200"

exec "$SCRIPT_DIR/runSpainMadrid17.sh" "$@"
