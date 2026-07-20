#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
export GRADLE_USER_HOME="$SCRIPT_DIR/.gradle-user-home"

DEFAULT_ARGS="globe 0 0 1600000 1"
RUN_ARGS="${*:-$DEFAULT_ARGS}"

# Rectangle zigzag example parameters:
# generator: zigzag
# lower_left_lat: 35.0
# lower_left_lon: -8.33000000
# step_distance_m: 100000
# max_distance_m: 6000
# altitude_m: 50000
# lat_span_deg: 9.0
# lon_span_deg: 13.0

#./run.sh zigzag 35.000 -8.33000000 100000 6000 50000 9.0 13.0

# ./run.sh zigzag 40.41656821133267 -3.703776486103843 100 6000 0

# ./run.sh spiral 40.41656821133267 -3.703776486103843 100 6000 0
# ./run.sh globe 0 0 800000 1
./gradlew --console=plain run --quiet --args="$RUN_ARGS"
