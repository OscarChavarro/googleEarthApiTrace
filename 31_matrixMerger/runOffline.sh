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

canonical_output="$(realpath -m "$1")"
lock_key="$(printf '%s' "$canonical_output" | cksum | awk '{print $1}')"
lock_file="${TMPDIR:-/media/ramdisk}/google-earth-matrix-${lock_key}.lock"
exec 8>"$lock_file"
if ! flock -n 8; then
    echo "ERROR: Matrix folder is already being produced or consumed: $canonical_output" >&2
    echo "Wait for the other program 31/32 execution to finish before retrying." >&2
    exit 1
fi

exec gradle --console=plain run --quiet \
    --args="--mode auto --offline --diagnose-order --minimum-tile-count=$minimum_tile_count $1"
