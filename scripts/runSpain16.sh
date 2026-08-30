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
readonly COMPLETION_CHECK_DEPTH=16
readonly DEFAULT_TMP_DIR="${PIPELINE_TMP_DIR:-/media/ramdisk}"
readonly DEFAULT_START_FROM_TILE=1
readonly PERFORMANCE_REPORT="/media/ramdisk/pyramidalImageExporterPerformanceReport.log"
readonly ERRORS_LOG="$PROJECT_DIR/scripts/errors.log"

emit_jobs=0
direct_mode=0
start_from_tile="$DEFAULT_START_FROM_TILE"
limit_tiles=0

die() {
    printf '[runSpain16][ERROR] %s\n' "$*" >&2
    exit 1
}

append_tile_error() {
    local cycle_number="$1"
    local status="$2"
    local lat="$3"
    local lon="$4"
    local center="$5"
    local route_command="$6"

    printf '%s | script=runSpain16 | cycle=%s/%s | lat=%s lon=%s center=%s | route=%s | reason=tile_failed_status_%s\n' \
        "$(date --iso-8601=seconds)" \
        "$cycle_number" \
        "$TOTAL_TILES" \
        "$lat" \
        "$lon" \
        "$center" \
        "$route_command" \
        "$status" >> "$ERRORS_LOG" || true
}

usage() {
    cat <<'EOF'
Usage: ./scripts/runSpain16.sh [options]

Runs the missing Spain level-16 batch in the deterministic Madrid-distance order.
The candidate regions come from the level-15 coverage mask; regions whose center
already has a level-16 PNG are skipped.

Options:
  --emit-jobs      Print the selected jobs as JSONL and do not run captures.
  --direct         Run jobs sequentially without the superpipeline.
  --start-from N   Start at tile N in the filtered ordered list (default: 1).
  --limit N        Run or emit at most N jobs after --start-from.
  -h, --help       Show this help.
EOF
}

parse_args() {
    while (($# > 0)); do
        case "$1" in
            --emit-jobs)
                emit_jobs=1
                shift
                ;;
            --direct)
                direct_mode=1
                shift
                ;;
            --start-from)
                (($# >= 2)) || die "--start-from requires a tile number."
                [[ "$2" =~ ^[1-9][0-9]*$ ]] || die "--start-from must be a positive integer: $2"
                start_from_tile="$2"
                shift 2
                ;;
            --limit)
                (($# >= 2)) || die "--limit requires a count."
                [[ "$2" =~ ^[1-9][0-9]*$ ]] || die "--limit must be a positive integer: $2"
                limit_tiles="$2"
                shift 2
                ;;
            -h|--help)
                usage
                exit 0
                ;;
            *)
                die "Unknown argument: $1"
                ;;
        esac
    done
}

delegate_to_superpipeline() {
    local -a command=(
        "$PROJECT_DIR/scripts/runSuperPipeline.sh"
        --job-source spain16
        --keep-failed-capture
    )

    if (( start_from_tile != DEFAULT_START_FROM_TILE )); then
        command+=(--start-from "$start_from_tile")
    fi
    if (( limit_tiles > 0 )); then
        command+=(--limit "$limit_tiles")
    fi

    exec "${command[@]}"
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

build_completion_keys() {
    local name_pattern

    printf -v name_pattern '%*s.png' "$((COMPLETION_CHECK_DEPTH + 1))" ''
    name_pattern="${name_pattern// /?}"

    find "$PYRAMID_DIR" -type f -name "$name_pattern" -printf '%f\n' | awk \
        -v expected_length="$((COMPLETION_CHECK_DEPTH + 5))" \
        -v key_length="$((COMPLETION_CHECK_DEPTH + 1))" '
        length($0) == expected_length && substr($0, 1, 1) == "0" {
            print substr($0, 1, key_length)
        }
    '
}

parse_args "$@"

if (( emit_jobs == 0 && direct_mode == 0 )); then
    delegate_to_superpipeline
fi

[[ -d "$PYRAMID_DIR" ]] || die "Pyramid directory does not exist: $PYRAMID_DIR"

mapfile -t MASK_RECORDS < <(build_coverage_mask)
(( ${#MASK_RECORDS[@]} > 1 )) || die "No level-15 coverage was found in $PYRAMID_DIR"

mapfile -t COMPLETION_KEYS < <(build_completion_keys)
readonly COMPLETION_TILE_COUNT="${#COMPLETION_KEYS[@]}"
readonly -a COMPLETION_KEYS

IFS=$'\t' read -r record_type SOURCE_TILE_COUNT <<< "${MASK_RECORDS[0]}"
[[ "$record_type" == "META" && "$SOURCE_TILE_COUNT" =~ ^[1-9][0-9]*$ ]] || \
    die "Could not count the level-15 source tiles"

readonly SOURCE_TILE_COUNT
readonly -a COVERED_TILES=("${MASK_RECORDS[@]:1}")
unset MASK_RECORDS

build_ordered_tiles() {
    awk \
        -F $'\t' \
        -v center_offset="$TILE_CENTER_OFFSET" \
        -v completion_depth="$COMPLETION_CHECK_DEPTH" \
        -v ref_lat="$ORDER_REFERENCE_LAT" \
        -v ref_lon="$ORDER_REFERENCE_LON" '
        function floor_value(value) {
            return value < int(value) ? int(value) - 1 : int(value)
        }

        function clamp(value, minimum, maximum) {
            if (value < minimum) return minimum
            if (value > maximum) return maximum
            return value
        }

        function quadkey_from_coordinates(depth, column, south_row,    bit, east, north, quadkey) {
            quadkey = "0"
            for (bit = depth - 1; bit >= 0; bit--) {
                east = int(column / (2 ^ bit)) % 2
                north = int(south_row / (2 ^ bit)) % 2
                if (north == 0 && east == 0) {
                    quadkey = quadkey "0"
                } else if (north == 0 && east == 1) {
                    quadkey = quadkey "1"
                } else if (north == 1 && east == 0) {
                    quadkey = quadkey "3"
                } else {
                    quadkey = quadkey "2"
                }
            }
            return quadkey
        }

        FNR == NR {
            if ($0 != "") {
                completed[$0] = 1
            }
            next
        }

        $1 == "TILE" {
            center_lat = $2 + center_offset
            center_lon = $3 + center_offset
            matrix_side = 2 ^ completion_depth
            source_span = 360.0 / matrix_side
            column = clamp(floor_value((center_lon + 180.0) / source_span), 0, matrix_side - 1)
            south_row = clamp(floor_value((center_lat + 180.0) / source_span), 0, matrix_side - 1)
            center_quadkey = quadkey_from_coordinates(completion_depth, column, south_row)
            if (center_quadkey in completed) {
                next
            }
            d_lat = center_lat - ref_lat
            d_lon = center_lon - ref_lon
            distance_sq = d_lat * d_lat + d_lon * d_lon
            printf "%.12f\t%s\t%s\t%.6f,%.6f\t%s\n", \
                distance_sq, $2, $3, center_lat, center_lon, center_quadkey
        }
    ' <(printf '%s\n' "${COMPLETION_KEYS[@]}") <(printf '%s\n' "${COVERED_TILES[@]}") \
        | sort -t $'\t' -k1,1n -k2,2n -k3,3n
}

mapfile -t ORDERED_TILES < <(build_ordered_tiles)
readonly TOTAL_TILES="${#ORDERED_TILES[@]}"
(( TOTAL_TILES > 0 )) || die "The level-15 coverage mask contains no runnable regions"

if (( emit_jobs == 0 )); then
    printf '[runSpain16][INFO] Built %d pending 0.25-degree regions from %d level-15 tiles.\n' \
        "$TOTAL_TILES" "$SOURCE_TILE_COUNT"
    printf '[runSpain16][INFO] Skipped regions whose center is already present at level %d using %d existing tiles.\n' \
        "$COMPLETION_CHECK_DEPTH" "$COMPLETION_TILE_COUNT"
    mkdir -p "$(dirname "$PERFORMANCE_REPORT")" 2>/dev/null || true
    printf '[runSpain16] start totalTiles=%d sourceLevel15Tiles=%d completionLevel=%d completionTiles=%d pyramidDir=%s\n' \
        "$TOTAL_TILES" "$SOURCE_TILE_COUNT" "$COMPLETION_CHECK_DEPTH" "$COMPLETION_TILE_COUNT" "$PYRAMID_DIR" \
        2>/dev/null >> "$PERFORMANCE_REPORT" || true
fi

emitted_tiles=0
for index in "${!ORDERED_TILES[@]}"; do
    cycle_number=$((index + 1))

    if (( cycle_number < start_from_tile )); then
        continue
    fi

    IFS=$'\t' read -r _distance_sq LAT LON TILE_CENTER CENTER_QUADKEY <<< "${ORDERED_TILES[$index]}"
    route_command="./run.sh zigzag $LAT $LON 3500 100 1600 $TILE_LAT_SPAN $TILE_LON_SPAN"

    if (( emit_jobs == 1 )); then
        printf '{"sequence":%d,"total":%d,"jobId":"spain16-%06d-%s-%s","latitude":%s,"longitude":%s,"center":"%s","centerLevel16Quadkey":"%s","routeCommand":"%s"}\n' \
            "$cycle_number" "$TOTAL_TILES" "$cycle_number" "$LAT" "$LON" \
            "$LAT" "$LON" "$TILE_CENTER" "$CENTER_QUADKEY" "$route_command"
        emitted_tiles=$((emitted_tiles + 1))
        if (( limit_tiles > 0 && emitted_tiles >= limit_tiles )); then
            break
        fi
        continue
    fi

    printf '[runSpain16][INFO][%s] Starting cycle %d/%d (tile %d onward) at lat=%s lon=%s center=%s.\n' \
        "$(date '+%Y-%m-%d %H:%M')" "$cycle_number" "$TOTAL_TILES" "$start_from_tile" "$LAT" "$LON" "$TILE_CENTER"

    cycle_start_ns="$(date +%s%N)"
    mkdir -p "$DEFAULT_TMP_DIR"
    run_log="$(mktemp "$DEFAULT_TMP_DIR/runSpain16.runFullProcess.XXXXXX")"
    if (
        cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml" &&
        cd "$PATH_PLANNER_DIR" &&
        ./run.sh zigzag "$LAT" "$LON" 3500 100 1600 "$TILE_LAT_SPAN" "$TILE_LON_SPAN" &&
        cd "$PROJECT_DIR" &&
        ./scripts/runFullProcess.sh \
            --route-command "$route_command"
    ) > >(tee "$run_log" | grep --line-buffered -E '\[PHASE\]|ITERATION (COMPLETED AS NO-OP|COMMITTED SUCCESSFULLY)') 2>&1; then
        printf '[runSpain16][INFO][%s] Finished cycle %d/%d at lat=%s lon=%s.\n' \
            "$(date '+%Y-%m-%d %H:%M')" "$cycle_number" "$TOTAL_TILES" "$LAT" "$LON"
        cycle_end_ns="$(date +%s%N)"
        printf '[runSpain16] cycle=%d/%d status=OK lat=%s lon=%s center=%s elapsed_ms=%d\n' \
            "$cycle_number" "$TOTAL_TILES" "$LAT" "$LON" "$TILE_CENTER" \
            $(((cycle_end_ns - cycle_start_ns) / 1000000)) 2>/dev/null >> "$PERFORMANCE_REPORT" || true
        rm -f -- "$run_log"
    else
        status=$?
        # A failed tile must not stop the batch nor emit its failure output.
        cycle_end_ns="$(date +%s%N)"
        append_tile_error "$cycle_number" "$status" "$LAT" "$LON" "$TILE_CENTER" "$route_command"
        printf '[runSpain16] cycle=%d/%d status=FAILED lat=%s lon=%s center=%s elapsed_ms=%d\n' \
            "$cycle_number" "$TOTAL_TILES" "$LAT" "$LON" "$TILE_CENTER" \
            $(((cycle_end_ns - cycle_start_ns) / 1000000)) 2>/dev/null >> "$PERFORMANCE_REPORT" || true
        rm -f -- "$run_log"
        pkill -9 google-earth >/dev/null 2>&1 || true
    fi

    emitted_tiles=$((emitted_tiles + 1))
    if (( limit_tiles > 0 && emitted_tiles >= limit_tiles )); then
        break
    fi
done
