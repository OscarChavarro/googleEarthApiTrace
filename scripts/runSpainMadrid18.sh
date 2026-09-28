#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

export MADRID_JOB_SOURCE="spain-madrid18"
export MADRID_SCRIPT_LABEL="runSpainMadrid18"
export MADRID_GRID_LABEL="8x8"
export MADRID_LEVEL="18"
export MADRID_TILE_LAT_SPAN="0.0625"
export MADRID_TILE_LON_SPAN="0.0625"
export MADRID_ZIGZAG_ALTITUDE="800"
export MADRID_ZIGZAG_STEP="25"
export MADRID_ZIGZAG_RANGE="400"

exec "$SCRIPT_DIR/runSpainMadrid17.sh" "$@"
