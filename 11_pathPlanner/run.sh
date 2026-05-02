#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

gradle --console=plain -q run --args="zigzag 40.41656821133267 -3.703776486103843 100 6000"
