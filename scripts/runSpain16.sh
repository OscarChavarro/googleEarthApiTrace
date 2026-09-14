#!/usr/bin/env bash

set -Eeuo pipefail

export QT_LINUX_ACCESSIBILITY_ALWAYS_ON=1

readonly PROJECT_DIR="/paradigmas/master/algoritmos_basicos_3d/googleEarthApiTrace"
readonly PATH_PLANNER_DIR="$PROJECT_DIR/11_pathPlanner"
readonly MY_PLACES_DIR="$HOME/.googleearth"
readonly PYRAMID_DIR="/samples/datasets/googleEarth/toplevel"
readonly CENTER_INVENTORY_SCRIPT="$PROJECT_DIR/scripts/countLevel16Centers.sh"
readonly ORDER_REFERENCE_LAT="40.4166"
readonly ORDER_REFERENCE_LON="-3.703"
readonly TILE_LAT_SPAN="0.25"
readonly TILE_LON_SPAN="0.25"
readonly COMPLETION_CHECK_DEPTH=16
readonly DEFAULT_TMP_DIR="${PIPELINE_TMP_DIR:-/media/ramdisk}"
readonly DEFAULT_START_FROM_TILE=1
readonly PERFORMANCE_REPORT="/media/ramdisk/pyramidalImageExporterPerformanceReport.log"
readonly ERRORS_LOG="$PROJECT_DIR/scripts/errors.log"

emit_jobs=0
direct_mode=0
start_from_tile="$DEFAULT_START_FROM_TILE"
start_from_tile_was_set=0
limit_tiles=0
continue_on_errors=1

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
already has a level-16 PNG are skipped using countLevel16Centers.sh. Re-running
the script therefore resumes from the current pyramid contents automatically.

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
        --job-source spain16
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
[[ -x "$CENTER_INVENTORY_SCRIPT" ]] || die "Center inventory script is not executable: $CENTER_INVENTORY_SCRIPT"

if ! CENTER_INVENTORY_OUTPUT="$($CENTER_INVENTORY_SCRIPT --emit-centers "$PYRAMID_DIR")"; then
    die "Could not inventory Spain level-16 centers with $CENTER_INVENTORY_SCRIPT"
fi
mapfile -t CENTER_INVENTORY_RECORDS <<< "$CENTER_INVENTORY_OUTPUT"
unset CENTER_INVENTORY_OUTPUT

SOURCE_TILE_COUNT=""
COMPLETION_TILE_COUNT=""
EXPECTED_CENTER_COUNT=""
DOWNLOADED_CENTER_COUNT=""
PENDING_CENTER_COUNT=""
PENDING_TILES=()

for record in "${CENTER_INVENTORY_RECORDS[@]}"; do
    IFS=$'\t' read -r record_type field_1 field_2 field_3 field_4 field_5 extra <<< "$record"
    case "$record_type" in
        META)
            case "$field_1" in
                sourceLevel15Files) SOURCE_TILE_COUNT="$field_2" ;;
                existingLevel16Files) COMPLETION_TILE_COUNT="$field_2" ;;
                expectedCenters) EXPECTED_CENTER_COUNT="$field_2" ;;
                downloadedCenters) DOWNLOADED_CENTER_COUNT="$field_2" ;;
                pendingCenters) PENDING_CENTER_COUNT="$field_2" ;;
            esac
            ;;
        TILE)
            [[ "$field_1" == "downloaded" || "$field_1" == "pending" ]] || \
                die "Invalid center inventory status: $field_1"
            [[ -n "$field_2" && -n "$field_3" && -n "$field_4" && -n "$field_5" && -z "$extra" ]] || \
                die "Malformed center inventory record: $record"
            if [[ "$field_1" == "pending" ]]; then
                PENDING_TILES+=("$record")
            fi
            ;;
        *)
            [[ -z "$record" ]] || die "Unknown center inventory record: $record"
            ;;
    esac
done

for count_value in \
    "$SOURCE_TILE_COUNT" "$COMPLETION_TILE_COUNT" "$EXPECTED_CENTER_COUNT" \
    "$DOWNLOADED_CENTER_COUNT" "$PENDING_CENTER_COUNT"; do
    [[ "$count_value" =~ ^[0-9]+$ ]] || die "Center inventory returned incomplete metadata"
done
(( SOURCE_TILE_COUNT > 0 && EXPECTED_CENTER_COUNT > 0 )) || die "No level-15 coverage was found in $PYRAMID_DIR"
(( DOWNLOADED_CENTER_COUNT + PENDING_CENTER_COUNT == EXPECTED_CENTER_COUNT )) || \
    die "Center inventory totals are inconsistent"
(( ${#PENDING_TILES[@]} == PENDING_CENTER_COUNT )) || \
    die "Center inventory listed ${#PENDING_TILES[@]} pending tiles but reported $PENDING_CENTER_COUNT"

readonly SOURCE_TILE_COUNT COMPLETION_TILE_COUNT EXPECTED_CENTER_COUNT
readonly DOWNLOADED_CENTER_COUNT PENDING_CENTER_COUNT
readonly -a CENTER_INVENTORY_RECORDS
readonly -a PENDING_TILES

build_ordered_tiles() {
    awk \
        -F $'\t' \
        -v ref_lat="$ORDER_REFERENCE_LAT" \
        -v ref_lon="$ORDER_REFERENCE_LON" '
        function cell_key(lat, lon) {
            return sprintf("%.2f\t%.2f", lat, lon)
        }

        $1 == "TILE" {
            status_by_cell[cell_key($3, $4)] = $2
            records[++record_count] = $0
            next
        }

        END {
            for (record_index = 1; record_index <= record_count; record_index++) {
                split(records[record_index], record, FS)
                if (record[2] != "pending") {
                    continue
                }

                downloaded_neighbors = 0
                for (lat_step = -1; lat_step <= 1; lat_step++) {
                    for (lon_step = -1; lon_step <= 1; lon_step++) {
                        if (lat_step == 0 && lon_step == 0) {
                            continue
                        }
                        neighbor_lat = record[3] + lat_step * 0.25
                        neighbor_lon = record[4] + lon_step * 0.25
                        if (status_by_cell[cell_key(neighbor_lat, neighbor_lon)] == "downloaded") {
                            downloaded_neighbors++
                        }
                    }
                }

                # Interior failures appear as missing cells surrounded by completed level-16 tiles.
                # Run those first, then continue with the Madrid-distance sweep.
                patch_priority = downloaded_neighbors >= 5 ? 0 : 1
                split(record[5], center, ",")
                center_lat = center[1]
                center_lon = center[2]
                d_lat = center_lat - ref_lat
                d_lon = center_lon - ref_lon
                distance_sq = d_lat * d_lat + d_lon * d_lon
                printf "%d\t%.12f\t%s\t%s\t%.6f,%.6f\t%s\n", \
                    patch_priority, distance_sq, record[3], record[4], center_lat, center_lon, record[6]
            }
        }
    ' <(printf '%s\n' "${CENTER_INVENTORY_RECORDS[@]}") \
        | sort -t $'\t' -k1,1n -k2,2n -k3,3n -k4,4n
}

mapfile -t ORDERED_TILES < <(build_ordered_tiles)
readonly TOTAL_TILES="${#ORDERED_TILES[@]}"
(( TOTAL_TILES > 0 )) || die "The level-15 coverage mask contains no runnable regions"

if (( emit_jobs == 0 )); then
    printf '[runSpain16][INFO] Center inventory: %d downloaded, %d pending, %d expected.\n' \
        "$DOWNLOADED_CENTER_COUNT" "$PENDING_CENTER_COUNT" "$EXPECTED_CENTER_COUNT"
    printf '[runSpain16][INFO] Scanned %d level-15 and %d level-16 files in %s.\n' \
        "$SOURCE_TILE_COUNT" "$COMPLETION_TILE_COUNT" "$PYRAMID_DIR"
    mkdir -p "$(dirname "$PERFORMANCE_REPORT")" 2>/dev/null || true
    printf '[runSpain16] start totalTiles=%d downloadedCenters=%d expectedCenters=%d sourceLevel15Tiles=%d completionLevel=%d completionTiles=%d pyramidDir=%s\n' \
        "$TOTAL_TILES" "$DOWNLOADED_CENTER_COUNT" "$EXPECTED_CENTER_COUNT" "$SOURCE_TILE_COUNT" \
        "$COMPLETION_CHECK_DEPTH" "$COMPLETION_TILE_COUNT" "$PYRAMID_DIR" \
        2>/dev/null >> "$PERFORMANCE_REPORT" || true
fi

emitted_tiles=0
for index in "${!ORDERED_TILES[@]}"; do
    cycle_number=$((index + 1))

    if (( cycle_number < start_from_tile )); then
        continue
    fi

    IFS=$'\t' read -r _patch_priority _distance_sq LAT LON TILE_CENTER CENTER_QUADKEY <<< "${ORDERED_TILES[$index]}"
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
