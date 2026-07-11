#!/usr/bin/env bash
set -euo pipefail
INPUT_FOLDER="${1:-/media/ramdisk/output}"
EXPORT_FOLDER="${2:-/samples/datasets/googleEarth}"
if [ "$#" -gt 2 ]; then
    shift 2
else
    shift "$#"
fi
gradle run --quiet --args="$INPUT_FOLDER $EXPORT_FOLDER $*"
