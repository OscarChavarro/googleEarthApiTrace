#!/usr/bin/env bash

set -Eeuo pipefail

readonly PROJECT_DIR="/paradigmas/master/algoritmos_basicos_3d/googleEarthApiTrace"
readonly PATH_PLANNER_DIR="$PROJECT_DIR/11_pathPlanner"
readonly MY_PLACES_DIR="$HOME/.googleearth"
readonly TARGETS=(
    "37.51 -8.9"
)
readonly TILE_SIZE="0.5"
readonly TOTAL_TILES="${#TARGETS[@]}"

tile_origin() {
    awk -v value="$1" -v tile_size="$TILE_SIZE" '
        BEGIN {
            tile_index = int(value / tile_size)
            if (value < 0 && value != tile_index * tile_size) {
                tile_index--
            }
            printf "%.1f", tile_index * tile_size
        }
    '
}

for ((cycle = 1; cycle <= TOTAL_TILES; cycle++)); do
    read -r target_lat target_lon <<<"${TARGETS[cycle - 1]}"
    current_lat="$(tile_origin "$target_lat")"
    current_lon="$(tile_origin "$target_lon")"

    printf '[runErrored][INFO] Starting cycle %d/%d for target lat=%s lon=%s using tile lat=%s lon=%s.\n' \
        "$cycle" "$TOTAL_TILES" "$target_lat" "$target_lon" "$current_lat" "$current_lon"

    run_log="$(mktemp /tmp/runErrored.runFullProcess.XXXXXX)"
    if (
        cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml" &&
        cd "$PATH_PLANNER_DIR" &&
        ./run.sh zigzag "$current_lat" "$current_lon" 7000 100 3000 0.44 0.38 &&
        cd "$PROJECT_DIR" &&
        ./scripts/runFullProcess.sh \
            --route-command "./run.sh zigzag $current_lat $current_lon 7000 100 3000 0.44 0.38"
    ) > >(tee "$run_log" | grep --line-buffered '\[PHASE\]') 2>&1; then
        grep -v '\[PHASE\]' "$run_log" || true
        printf '[runErrored][INFO] Finished cycle %d/%d at lat=%s lon=%s.\n' \
            "$cycle" "$TOTAL_TILES" "$current_lat" "$current_lon"
        rm -f -- "$run_log"
    else
        # A failed tile must not emit its failure output.
        rm -f -- "$run_log"
        pkill -9 google-earth >/dev/null 2>&1 || true
    fi
done
