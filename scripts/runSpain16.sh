#!/usr/bin/env bash

set -Eeuo pipefail

readonly PROJECT_DIR="/paradigmas/master/algoritmos_basicos_3d/googleEarthApiTrace"
readonly PATH_PLANNER_DIR="$PROJECT_DIR/11_pathPlanner"
readonly MY_PLACES_DIR="$HOME/.googleearth"
readonly PYRAMID_DIR="/samples/datasets/googleEarth/toplevel"
readonly ORDER_REFERENCE_LAT="40.4166"
readonly ORDER_REFERENCE_LON="-3.703"
readonly MIN_LAT="36.0"
readonly MAX_LAT="44.0"
readonly MIN_LON="-9.5"
readonly MAX_LON="4.5"
readonly TILE_LAT_SPAN="0.25"
readonly TILE_LON_SPAN="0.25"
readonly TILE_CENTER_OFFSET="0.125"
readonly START_FROM_TILE=29
readonly PERFORMANCE_REPORT="/media/ramdisk/pyramidalImageExporterPerformanceReport.log"

die() {
    printf '[runSpain16][ERROR] %s\n' "$*" >&2
    exit 1
}

build_coverage_mask() {
    find "$PYRAMID_DIR" -type f -name '????????????????.png' -printf '%f\n' | awk \
        -v min_lat="$MIN_LAT" \
        -v max_lat="$MAX_LAT" \
        -v min_lon="$MIN_LON" \
        -v max_lon="$MAX_LON" \
        -v region_lat_span="$TILE_LAT_SPAN" \
        -v region_lon_span="$TILE_LON_SPAN" '
        function floor_value(value) {
            return value < int(value) ? int(value) - 1 : int(value)
        }

        length($0) == 20 && substr($0, 1, 1) == "0" {
            quadkey = substr($0, 1, 16)
            column = 0
            south_row = 0

            for (digit_index = 2; digit_index <= 16; digit_index++) {
                quadrant = substr(quadkey, digit_index, 1)
                if (quadrant !~ /^[0-3]$/) {
                    next
                }
                column = column * 2 + ((quadrant == 1 || quadrant == 2) ? 1 : 0)
                south_row = south_row * 2 + ((quadrant == 2 || quadrant == 3) ? 1 : 0)
            }

            source_tiles++
            source_span = 360.0 / 32768.0
            west = -180.0 + column * source_span
            east = west + source_span
            south = -180.0 + south_row * source_span
            north = south + source_span
            base_x = floor_value((west - min_lon) / region_lon_span)
            base_y = floor_value((south - min_lat) / region_lat_span)

            # A level-15 tile is smaller than a 0.25-degree region. Testing the
            # containing cell and its neighbors also handles straddled boundaries.
            for (dx = -1; dx <= 1; dx++) {
                for (dy = -1; dy <= 1; dy++) {
                    x = base_x + dx
                    y = base_y + dy
                    region_west = min_lon + x * region_lon_span
                    region_south = min_lat + y * region_lat_span
                    if (region_west >= min_lon && region_west < max_lon &&
                        region_south >= min_lat && region_south < max_lat &&
                        east > region_west && west < region_west + region_lon_span &&
                        north > region_south && south < region_south + region_lat_span) {
                        covered[y SUBSEP x] = 1
                    }
                }
            }
        }

        END {
            printf "META\t%d\n", source_tiles
            for (key in covered) {
                split(key, coordinates, SUBSEP)
                printf "TILE\t%.2f\t%.2f\n", \
                    min_lat + coordinates[1] * region_lat_span, \
                    min_lon + coordinates[2] * region_lon_span
            }
        }
    '
}

[[ -d "$PYRAMID_DIR" ]] || die "Pyramid directory does not exist: $PYRAMID_DIR"

mapfile -t MASK_RECORDS < <(build_coverage_mask)
(( ${#MASK_RECORDS[@]} > 1 )) || die "No level-15 coverage was found in $PYRAMID_DIR"

IFS=$'\t' read -r record_type SOURCE_TILE_COUNT <<< "${MASK_RECORDS[0]}"
[[ "$record_type" == "META" && "$SOURCE_TILE_COUNT" =~ ^[1-9][0-9]*$ ]] || \
    die "Could not count the level-15 source tiles"

readonly SOURCE_TILE_COUNT
readonly -a COVERED_TILES=("${MASK_RECORDS[@]:1}")
unset MASK_RECORDS

build_ordered_tiles() {
    printf '%s\n' "${COVERED_TILES[@]}" | awk \
        -F $'\t' \
        -v center_offset="$TILE_CENTER_OFFSET" \
        -v ref_lat="$ORDER_REFERENCE_LAT" \
        -v ref_lon="$ORDER_REFERENCE_LON" '
        $1 == "TILE" {
            center_lat = $2 + center_offset
            center_lon = $3 + center_offset
            d_lat = center_lat - ref_lat
            d_lon = center_lon - ref_lon
            distance_sq = d_lat * d_lat + d_lon * d_lon
            printf "%.12f\t%s\t%s\t%.6f,%.6f\n", \
                distance_sq, $2, $3, center_lat, center_lon
        }
    ' | sort -t $'\t' -k1,1n -k2,2n -k3,3n
}

mapfile -t ORDERED_TILES < <(build_ordered_tiles)
readonly TOTAL_TILES="${#ORDERED_TILES[@]}"
(( TOTAL_TILES > 0 )) || die "The level-15 coverage mask contains no runnable regions"

printf '[runSpain16][INFO] Built %d covered 0.25-degree regions from %d level-15 tiles.\n' \
    "$TOTAL_TILES" "$SOURCE_TILE_COUNT"
mkdir -p "$(dirname "$PERFORMANCE_REPORT")" 2>/dev/null || true
printf '[runSpain16] start totalTiles=%d sourceLevel15Tiles=%d pyramidDir=%s\n' \
    "$TOTAL_TILES" "$SOURCE_TILE_COUNT" "$PYRAMID_DIR" 2>/dev/null >> "$PERFORMANCE_REPORT" || true

for index in "${!ORDERED_TILES[@]}"; do
    cycle_number=$((index + 1))

    if (( cycle_number < START_FROM_TILE )); then
        continue
    fi

    IFS=$'\t' read -r _distance_sq LAT LON TILE_CENTER <<< "${ORDERED_TILES[$index]}"

    printf '[runSpain16][INFO][%s] Starting cycle %d/%d (tile %d onward) at lat=%s lon=%s center=%s.\n' \
        "$(date '+%Y-%m-%d %H:%M')" "$cycle_number" "$TOTAL_TILES" "$START_FROM_TILE" "$LAT" "$LON" "$TILE_CENTER"

    cycle_start_ns="$(date +%s%N)"
    run_log="$(mktemp /tmp/runSpain16.runFullProcess.XXXXXX)"
    if (
        cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml" &&
        cd "$PATH_PLANNER_DIR" &&
        ./run.sh zigzag "$LAT" "$LON" 3500 100 1600 "$TILE_LAT_SPAN" "$TILE_LON_SPAN" &&
        cd "$PROJECT_DIR" &&
        ./scripts/runFullProcess.sh \
            --route-command "./run.sh zigzag $LAT $LON 3500 100 1600 $TILE_LAT_SPAN $TILE_LON_SPAN"
    ) > >(tee "$run_log" | grep --line-buffered -E '\[PHASE\]|ITERATION (COMPLETED AS NO-OP|COMMITTED SUCCESSFULLY)') 2>&1; then
        printf '[runSpain16][INFO][%s] Finished cycle %d/%d at lat=%s lon=%s.\n' \
            "$(date '+%Y-%m-%d %H:%M')" "$cycle_number" "$TOTAL_TILES" "$LAT" "$LON"
        cycle_end_ns="$(date +%s%N)"
        printf '[runSpain16] cycle=%d/%d status=OK lat=%s lon=%s center=%s elapsed_ms=%d\n' \
            "$cycle_number" "$TOTAL_TILES" "$LAT" "$LON" "$TILE_CENTER" \
            $(((cycle_end_ns - cycle_start_ns) / 1000000)) 2>/dev/null >> "$PERFORMANCE_REPORT" || true
        rm -f -- "$run_log"
    else
        # A failed tile must not stop the batch nor emit its failure output.
        cycle_end_ns="$(date +%s%N)"
        printf '[runSpain16] cycle=%d/%d status=FAILED lat=%s lon=%s center=%s elapsed_ms=%d\n' \
            "$cycle_number" "$TOTAL_TILES" "$LAT" "$LON" "$TILE_CENTER" \
            $(((cycle_end_ns - cycle_start_ns) / 1000000)) 2>/dev/null >> "$PERFORMANCE_REPORT" || true
        rm -f -- "$run_log"
        pkill -9 google-earth >/dev/null 2>&1 || true
    fi
done
