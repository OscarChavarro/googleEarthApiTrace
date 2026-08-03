#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 1 ]; then
    echo "ERROR: Missing required <inputFolder> argument: no default paths are assumed." >&2
    echo "It must point to the folder exported by 31_matrixMerger (the one containing the matrix_<n> subfolders)." >&2
    echo "The session's pyramidal image is written inside it, to <inputFolder>/pyramidalImage." >&2
    echo "An existing pyramid may optionally be supplied read-only with --reference-pyramid so sparse" >&2
    echo "child layers can be anchored; combining sessions remains the responsibility of program 42." >&2
    echo "Usage: ./run.sh <inputFolder> [--reference-pyramid <folder>] [--export] [--offline] [options]" >&2
    exit 1
fi

gradle run --quiet --args="$*"
