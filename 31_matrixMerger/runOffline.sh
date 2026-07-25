#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$script_dir"

if (($# < 1 || $# > 2)); then
    echo "Usage: ./runOffline.sh <outputFolder> [minimumTileCount]" >&2
    exit 1
fi

minimum_tile_count="${2:-10}"
if [[ ! "$minimum_tile_count" =~ ^[0-9]+$ ]]; then
    echo "minimumTileCount must be a non-negative integer." >&2
    exit 1
fi

exec gradle run --quiet \
    --args="--mode auto --offline --diagnose-order --minimum-tile-count=$minimum_tile_count $1"
