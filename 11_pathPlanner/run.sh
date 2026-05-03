#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# gradle --console=plain run --quiet --args="zigzag 40.41656821133267 -3.703776486103843 100 6000"
# gradle --console=plain run --quiet --args="spiral 40.41656821133267 -3.703776486103843 100 6000"
gradle --console=plain run --quiet --args="globe 0 0 800000 1"
