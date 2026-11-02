#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Vitral's libpng based reader, built with its pkgs/NativeImageReader project. It is
# optional: without it, Vitral reads PNG files through AWT, which is slower.
export VSDK_NATIVE_LIB_PATH="${VSDK_NATIVE_LIB_PATH:-$HOME/VITRAL/vitral/pkgs/NativeImageReader/build}"

gradle --console=plain run --quiet --args="$* --mcp-port 42817"
