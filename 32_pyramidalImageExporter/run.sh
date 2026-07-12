#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 1 ]; then
    echo "ERROR: Missing required <inputFolder> argument: no default paths are assumed." >&2
    echo "It must point to the folder exported by 31_matrixMerger (the one containing the matrix_<n> subfolders)." >&2
    echo "The session's pyramidal image is written inside it, to <inputFolder>/pyramidalImage; no other" >&2
    echo "pyramidal image is ever read or written. Merging different capture sessions' pyramidal images" >&2
    echo "is the responsibility of a separate program." >&2
    echo "Usage: ./run.sh <inputFolder> [--export] [--offline] [options]" >&2
    exit 1
fi

gradle run --quiet --args="$*"
