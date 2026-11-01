#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Reuse the canonical slot; never allocate another output* capture directory.
# Module 21 currently expects a capture directory literally named "output".
export PIPELINE_OUTPUT_DIRECTORY="/media/ramdisk/output"
export PIPELINE_LOGICAL_OUTPUT_DIRECTORY="$PIPELINE_OUTPUT_DIRECTORY"
export PIPELINE_TRACE_DUMP_DIR="$PIPELINE_OUTPUT_DIRECTORY"
export PIPELINE_MATRIX_DIR="/media/ramdisk/matrix"
export PIPELINE_LOG_ROOT="/media/ramdisk/logs"
export PIPELINE_SESSION_LOG="/media/ramdisk/googleEarthSession.log"
export PIPELINE_TMP_DIR="/media/ramdisk"
mkdir -p "$PIPELINE_OUTPUT_DIRECTORY"
printf 'RAM capture slot: %s\n' "$PIPELINE_OUTPUT_DIRECTORY"
# Same successful zigzag settings, shifted one 8-degree block west.
route=(zigzag 52 -100 180000 3200 100000 8 8)
"$ROOT/11_pathPlanner/run.sh" "${route[@]}"
exec "$ROOT/scripts/runFullProcess.sh" --merge-level-only 10 \
    --route-command "./run.sh ${route[*]}" "$@"
