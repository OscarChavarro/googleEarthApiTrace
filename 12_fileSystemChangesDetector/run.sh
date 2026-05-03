#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$SCRIPT_DIR/build"

cmake -S "$SCRIPT_DIR" -B "$BUILD_DIR"
cmake --build "$BUILD_DIR" -j

echo "Warning: this command will run with sudo (root privileges) to access fanotify kernel operations."
exec sudo "$BUILD_DIR/fileSystemChangesDetector" /media/ramdisk/output
