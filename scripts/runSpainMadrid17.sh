#!/usr/bin/env bash

set -Eeuo pipefail

export QT_LINUX_ACCESSIBILITY_ALWAYS_ON=1

readonly PROJECT_DIR="/paradigmas/master/algoritmos_basicos_3d/googleEarthApiTrace"
readonly PATH_PLANNER_DIR="$PROJECT_DIR/11_pathPlanner"
readonly MY_PLACES_DIR="$HOME/.googleearth"
readonly PYRAMID_DIR="/samples/datasets/googleEarth/toplevel"
readonly INVENTORY_ROOT="$PYRAMID_DIR/3/1/1/2/2/2/0/1"
readonly JOB_SOURCE="${MADRID_JOB_SOURCE:-spain-madrid17}"
readonly SCRIPT_LABEL="${MADRID_SCRIPT_LABEL:-runSpainMadrid17}"
readonly GRID_LABEL="${MADRID_GRID_LABEL:-4x4}"
readonly ORDER_REFERENCE_LAT="40.4166"
readonly ORDER_REFERENCE_LON="-3.703"
readonly TILE_LAT_SPAN="${MADRID_TILE_LAT_SPAN:-0.125}"
readonly TILE_LON_SPAN="${MADRID_TILE_LON_SPAN:-0.125}"
readonly AREA_MIN_LAT="40.125"
readonly AREA_MAX_LAT="40.625"
readonly AREA_MIN_LON="-3.875"
readonly AREA_MAX_LON="-3.375"
readonly COMPLETION_CHECK_DEPTH="${MADRID_LEVEL:-17}"
readonly MERGE_LEVEL_ONLY="$COMPLETION_CHECK_DEPTH"
readonly ZIGZAG_ALTITUDE="${MADRID_ZIGZAG_ALTITUDE:-1600}"
readonly ZIGZAG_STEP="${MADRID_ZIGZAG_STEP:-50}"
readonly ZIGZAG_RANGE="${MADRID_ZIGZAG_RANGE:-800}"
readonly DEFAULT_TMP_DIR="${PIPELINE_TMP_DIR:-/media/ramdisk}"
readonly DEFAULT_START_FROM_TILE=1
readonly PERFORMANCE_REPORT="/media/ramdisk/pyramidalImageExporterPerformanceReport.log"
readonly ERRORS_LOG="$PROJECT_DIR/scripts/errors.log"

# All 16 Madrid cells lie below this level-8 quadkey. Restricting the
# reference catalogue keeps each level-17 export local to the requested area.
export PYRAMIDAL_IMAGE_EXPORTER_REFERENCE_PREFIXES="031122201"

emit_jobs=0
direct_mode=0
start_from_tile="$DEFAULT_START_FROM_TILE"
start_from_tile_was_set=0
limit_tiles=0
continue_on_errors=1

die() {
    printf '[%s][ERROR] %s\n' "$SCRIPT_LABEL" "$*" >&2
    exit 1
}

append_tile_error() {
    local cycle_number="$1"
    local status="$2"
    local lat="$3"
    local lon="$4"
    local center="$5"
    local route_command="$6"

    printf '%s | script=%s | cycle=%s/%s | lat=%s lon=%s center=%s | route=%s | reason=tile_failed_status_%s\n' \
        "$(date --iso-8601=seconds)" \
        "$SCRIPT_LABEL" \
        "$cycle_number" \
        "$TOTAL_TILES" \
        "$lat" \
        "$lon" \
        "$center" \
        "$route_command" \
        "$status" >> "$ERRORS_LOG" || true
}

usage() {
    cat <<EOF
Usage: ./scripts/${SCRIPT_LABEL}.sh [options]

Runs the missing level-${COMPLETION_CHECK_DEPTH} tiles in the ${GRID_LABEL} Madrid rectangle bounded by
longitude [-3.875, -3.375] and latitude [40.125, 40.625]. Candidates are generated
directly from that rectangle, independently of the level-16 coverage, and are
ordered by distance to the Spain reference point (40.4166, -3.703).

Options:
  --emit-jobs           Print the selected jobs as JSONL and do not run captures.
  --direct              Run jobs sequentially without the superpipeline.
  --start-from N        Start at tile N in the current pending ordered list.
                        By default, all currently pending centers are emitted.
  --limit N             Run or emit at most N jobs after --start-from.
  --stop-on-error       Stop the superpipeline at the first failed stage.
  --continue-on-errors  Record failed jobs and continue with later pending jobs
                        (default).
  -h, --help            Show this help.
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
                start_from_tile_was_set=1
                shift 2
                ;;
            --limit)
                (($# >= 2)) || die "--limit requires a count."
                [[ "$2" =~ ^[1-9][0-9]*$ ]] || die "--limit must be a positive integer: $2"
                limit_tiles="$2"
                shift 2
                ;;
            --stop-on-error)
                continue_on_errors=0
                shift
                ;;
            --continue-on-errors)
                continue_on_errors=1
                shift
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
        --job-source "$JOB_SOURCE"
    )

    if (( continue_on_errors == 1 )); then
        command+=(--continue-on-errors)
    fi
    if (( start_from_tile_was_set == 1 )); then
        command+=(--start-from "$start_from_tile")
    fi
    if (( limit_tiles > 0 )); then
        command+=(--limit "$limit_tiles")
    fi

    exec "${command[@]}"
}

parse_args "$@"

if (( emit_jobs == 0 && direct_mode == 0 )); then
    delegate_to_superpipeline
fi

[[ -d "$PYRAMID_DIR" ]] || die "Pyramid directory does not exist: $PYRAMID_DIR"
[[ -d "$INVENTORY_ROOT" ]] || die "Madrid inventory subtree does not exist: $INVENTORY_ROOT"

build_tile_inventory() {
    awk \
        -v min_lat="$AREA_MIN_LAT" \
        -v max_lat="$AREA_MAX_LAT" \
        -v min_lon="$AREA_MIN_LON" \
        -v max_lon="$AREA_MAX_LON" \
        -v lat_span="$TILE_LAT_SPAN" \
        -v lon_span="$TILE_LON_SPAN" \
        -v depth="$COMPLETION_CHECK_DEPTH" '
        function floor_value(value) {
            return value < int(value) ? int(value) - 1 : int(value)
        }

        function clamp(value, minimum, maximum) {
            if (value < minimum) return minimum
            if (value > maximum) return maximum
            return value
        }

        function quadkey_from_coordinates(level, column, south_row,    bit, east, north, quadkey) {
            quadkey = "0"
            for (bit = level - 1; bit >= 0; bit--) {
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

        {
            present[$0] = 1
            existing_files++
        }

        END {
            matrix_side = 2 ^ depth
            world_span = 360.0 / matrix_side

            # Strict overlap excludes tiles that merely touch the rectangle edge.
            for (south = min_lat; south < max_lat; south += lat_span) {
                north = south + lat_span
                for (west = min_lon; west < max_lon; west += lon_span) {
                    east = west + lon_span
                    if (east <= min_lon || west >= max_lon ||
                        north <= min_lat || south >= max_lat) {
                        continue
                    }

                    center_lat = south + lat_span / 2.0
                    center_lon = west + lon_span / 2.0
                    column = clamp(floor_value((center_lon + 180.0) / world_span), 0, matrix_side - 1)
                    south_row = clamp(floor_value((center_lat + 180.0) / world_span), 0, matrix_side - 1)
                    center_quadkey = quadkey_from_coordinates(depth, column, south_row)

                    first_column = clamp(floor_value((west + 180.0) / world_span), 0, matrix_side - 1)
                    last_column = clamp(floor_value((east + 180.0 - world_span / 1000.0) / world_span), 0, matrix_side - 1)
                    first_row = clamp(floor_value((south + 180.0) / world_span), 0, matrix_side - 1)
                    last_row = clamp(floor_value((north + 180.0 - world_span / 1000.0) / world_span), 0, matrix_side - 1)
                    required_files = 0
                    present_files = 0
                    for (tile_row = first_row; tile_row <= last_row; tile_row++) {
                        for (tile_column = first_column; tile_column <= last_column; tile_column++) {
                            required_files++
                            tile_quadkey = quadkey_from_coordinates(depth, tile_column, tile_row)
                            if (tile_quadkey in present) present_files++
                        }
                    }
                    status = present_files == required_files ? "downloaded" : "pending"

                    printf "TILE\t%s\t%.4f\t%.4f\t%.6f,%.6f\t%s\t%d\t%d\n", \
                        status, south, west, center_lat, center_lon, center_quadkey, \
                        present_files, required_files
                }
            }
            printf "META\texistingLevelFiles\t%d\n", existing_files
        }
    ' < <(find "$INVENTORY_ROOT" -type f -name "$(printf '%*s' "$((COMPLETION_CHECK_DEPTH + 1))" '' | tr ' ' '?').png" -printf '%f\n' | sed 's/[.]png$//')
}

mapfile -t TILE_INVENTORY < <(build_tile_inventory)
COMPLETION_TILE_COUNT=0
EXPECTED_CENTER_COUNT=0
DOWNLOADED_CENTER_COUNT=0
PENDING_TILES=()

for record in "${TILE_INVENTORY[@]}"; do
    IFS=$'\t' read -r record_type field_1 field_2 field_3 field_4 field_5 field_6 field_7 extra <<< "$record"
    case "$record_type" in
        META)
            [[ "$field_1" == "existingLevelFiles" && "$field_2" =~ ^[0-9]+$ ]] || \
                die "Malformed inventory metadata: $record"
            COMPLETION_TILE_COUNT="$field_2"
            ;;
        TILE)
            [[ "$field_1" == "downloaded" || "$field_1" == "pending" ]] || \
                die "Invalid inventory status: $field_1"
            [[ -n "$field_2" && -n "$field_3" && -n "$field_4" && -n "$field_5" \
                && "$field_6" =~ ^[0-9]+$ && "$field_7" =~ ^[1-9][0-9]*$ && -z "$extra" ]] || \
                die "Malformed inventory record: $record"
            EXPECTED_CENTER_COUNT=$((EXPECTED_CENTER_COUNT + 1))
            if [[ "$field_1" == "downloaded" ]]; then
                DOWNLOADED_CENTER_COUNT=$((DOWNLOADED_CENTER_COUNT + 1))
            else
                PENDING_TILES+=("$record")
            fi
            ;;
        *)
            [[ -z "$record" ]] || die "Unknown inventory record: $record"
            ;;
    esac
done

readonly COMPLETION_TILE_COUNT EXPECTED_CENTER_COUNT DOWNLOADED_CENTER_COUNT
readonly PENDING_CENTER_COUNT="${#PENDING_TILES[@]}"
readonly -a TILE_INVENTORY PENDING_TILES

(( EXPECTED_CENTER_COUNT > 0 )) || die "The Madrid rectangle contains no level-${COMPLETION_CHECK_DEPTH} tiles"

build_ordered_tiles() {
    awk \
        -F $'\t' \
        -v ref_lat="$ORDER_REFERENCE_LAT" \
        -v ref_lon="$ORDER_REFERENCE_LON" '
        $1 == "TILE" && $2 == "pending" {
            split($5, center, ",")
            d_lat = center[1] - ref_lat
            d_lon = center[2] - ref_lon
            distance_sq = d_lat * d_lat + d_lon * d_lon
            printf "%.12f\t%s\t%s\t%s\t%s\n", distance_sq, $3, $4, $5, $6
        }
    ' <(printf '%s\n' "${PENDING_TILES[@]}") \
        | sort -t $'\t' -k1,1n -k2,2n -k3,3n
}

mapfile -t ORDERED_TILES < <(build_ordered_tiles)
readonly TOTAL_TILES="${#ORDERED_TILES[@]}"
(( TOTAL_TILES > 0 )) || die "All level-${COMPLETION_CHECK_DEPTH} tiles intersecting the Madrid rectangle are already downloaded"

if (( emit_jobs == 0 )); then
    printf '[%s][INFO] Tile inventory: %d downloaded, %d pending, %d expected.\n' "$SCRIPT_LABEL" \
        "$DOWNLOADED_CENTER_COUNT" "$PENDING_CENTER_COUNT" "$EXPECTED_CENTER_COUNT"
    printf '[%s][INFO] Found %d level-%d files in %s.\n' "$SCRIPT_LABEL" \
        "$COMPLETION_TILE_COUNT" "$COMPLETION_CHECK_DEPTH" "$PYRAMID_DIR"
    mkdir -p "$(dirname "$PERFORMANCE_REPORT")" 2>/dev/null || true
    printf '[%s] start totalTiles=%d downloadedCenters=%d expectedCenters=%d completionLevel=%d completionTiles=%d pyramidDir=%s\n' \
        "$SCRIPT_LABEL" "$TOTAL_TILES" "$DOWNLOADED_CENTER_COUNT" "$EXPECTED_CENTER_COUNT" \
        "$COMPLETION_CHECK_DEPTH" "$COMPLETION_TILE_COUNT" "$PYRAMID_DIR" \
        2>/dev/null >> "$PERFORMANCE_REPORT" || true
fi

emitted_tiles=0
for index in "${!ORDERED_TILES[@]}"; do
    cycle_number=$((index + 1))

    if (( cycle_number < start_from_tile )); then
        continue
    fi

    IFS=$'\t' read -r _distance_sq LAT LON TILE_CENTER CENTER_QUADKEY <<< "${ORDERED_TILES[$index]}"
    route_command="./run.sh zigzag $LAT $LON $ZIGZAG_ALTITUDE $ZIGZAG_STEP $ZIGZAG_RANGE $TILE_LAT_SPAN $TILE_LON_SPAN"

    if (( emit_jobs == 1 )); then
        printf '{"sequence":%d,"total":%d,"jobId":"%s-%06d-%s-%s","latitude":%s,"longitude":%s,"latitudeSpan":%s,"longitudeSpan":%s,"center":"%s","centerQuadkey":"%s","mergeLevelOnly":%d,"routeCommand":"%s"}\n' \
            "$cycle_number" "$TOTAL_TILES" "$JOB_SOURCE" "$cycle_number" "$LAT" "$LON" \
            "$LAT" "$LON" "$TILE_LAT_SPAN" "$TILE_LON_SPAN" \
            "$TILE_CENTER" "$CENTER_QUADKEY" "$MERGE_LEVEL_ONLY" "$route_command"
        emitted_tiles=$((emitted_tiles + 1))
        if (( limit_tiles > 0 && emitted_tiles >= limit_tiles )); then
            break
        fi
        continue
    fi

    printf '[%s][INFO][%s] Starting cycle %d/%d (tile %d onward) at lat=%s lon=%s center=%s.\n' \
        "$SCRIPT_LABEL" "$(date '+%Y-%m-%d %H:%M')" "$cycle_number" "$TOTAL_TILES" "$start_from_tile" "$LAT" "$LON" "$TILE_CENTER"

    cycle_start_ns="$(date +%s%N)"
    mkdir -p "$DEFAULT_TMP_DIR"
    run_log="$(mktemp "$DEFAULT_TMP_DIR/runSpainMadrid17.runFullProcess.XXXXXX")"
    if (
        cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml" &&
        cd "$PATH_PLANNER_DIR" &&
        ./run.sh zigzag "$LAT" "$LON" "$ZIGZAG_ALTITUDE" "$ZIGZAG_STEP" "$ZIGZAG_RANGE" "$TILE_LAT_SPAN" "$TILE_LON_SPAN" &&
        cd "$PROJECT_DIR" &&
        ./scripts/runFullProcess.sh \
            --merge-level-only "$MERGE_LEVEL_ONLY" \
            --route-command "$route_command"
    ) > >(tee "$run_log" | grep --line-buffered -E '\[PHASE\]|ITERATION (COMPLETED AS NO-OP|COMMITTED SUCCESSFULLY)') 2>&1; then
        printf '[%s][INFO][%s] Finished cycle %d/%d at lat=%s lon=%s.\n' \
            "$SCRIPT_LABEL" "$(date '+%Y-%m-%d %H:%M')" "$cycle_number" "$TOTAL_TILES" "$LAT" "$LON"
        cycle_end_ns="$(date +%s%N)"
        printf '[%s] cycle=%d/%d status=OK lat=%s lon=%s center=%s elapsed_ms=%d\n' \
            "$SCRIPT_LABEL" "$cycle_number" "$TOTAL_TILES" "$LAT" "$LON" "$TILE_CENTER" \
            $(((cycle_end_ns - cycle_start_ns) / 1000000)) 2>/dev/null >> "$PERFORMANCE_REPORT" || true
        rm -f -- "$run_log"
    else
        status=$?
        cycle_end_ns="$(date +%s%N)"
        append_tile_error "$cycle_number" "$status" "$LAT" "$LON" "$TILE_CENTER" "$route_command"
        printf '[%s] cycle=%d/%d status=FAILED lat=%s lon=%s center=%s elapsed_ms=%d\n' \
            "$SCRIPT_LABEL" "$cycle_number" "$TOTAL_TILES" "$LAT" "$LON" "$TILE_CENTER" \
            $(((cycle_end_ns - cycle_start_ns) / 1000000)) 2>/dev/null >> "$PERFORMANCE_REPORT" || true
        rm -f -- "$run_log"
        pkill -9 google-earth >/dev/null 2>&1 || true
    fi

    emitted_tiles=$((emitted_tiles + 1))
    if (( limit_tiles > 0 && emitted_tiles >= limit_tiles )); then
        break
    fi
done
