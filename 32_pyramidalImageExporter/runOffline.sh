#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$script_dir"

if (($# < 1 || $# > 2)); then
    echo "Usage: ./runOffline.sh <inputFolder> [referencePyramidalImageFolder]" >&2
    exit 1
fi

if (($# == 2)); then
    exec gradle run --quiet --args="--offline --reference-pyramid $2 $1"
fi

exec gradle run --quiet --args="--offline $1"
