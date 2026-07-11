#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
export GRADLE_USER_HOME="$SCRIPT_DIR/.gradle-user-home"

DEFAULT_ARGS="globe 0 0 1600000 1"
RUN_ARGS="${*:-$DEFAULT_ARGS}"

# ./run.sh zigzag 40.41656821133267 -3.703776486103843 100 6000
# ./run.sh spiral 40.41656821133267 -3.703776486103843 100 6000
# ./run.sh globe 0 0 800000 1
./gradlew --console=plain run --quiet --args="$RUN_ARGS"
