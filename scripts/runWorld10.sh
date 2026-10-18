#!/usr/bin/env bash

set -Eeuo pipefail

readonly PROJECT_DIR="/paradigmas/master/algoritmos_basicos_3d/googleEarthApiTrace"
readonly PATH_PLANNER_DIR="$PROJECT_DIR/11_pathPlanner"
readonly MY_PLACES_DIR="$HOME/.googleearth"
readonly DEFAULT_START_FROM_TILE=1
readonly ERRORS_LOG="$PROJECT_DIR/scripts/errors.log"
# `reverse` preserves the historical antimeridian-first traversal.  A caller may
# set WORLD10_ORDER=land-first to consume the generated catalog in its native
# descending land-coverage order, or `america-first` to exhaust the continental
# Americas before continuing with the remaining land blocks.  In both cases the
# generated catalog's zero-land (ocean) blocks remain at the end.
readonly WORLD10_ORDER="${WORLD10_ORDER:-reverse}"

emit_jobs=0
direct_mode=0
start_from_tile="$DEFAULT_START_FROM_TILE"
limit_tiles=0

die() {
    printf '[runWorld10][ERROR] %s\n' "$*" >&2
    exit 1
}

usage() {
    cat <<'EOF'
Usage: ./scripts/runWorld10.sh [options]

Runs the world level-10 batch in reverse generated order. By default,
captures and post-processing overlap through the superpipeline. Re-running without
--start-from skips blocks whose complete level-10 extent is already downloaded.

Options:
  --emit-jobs      Print the selected jobs as JSONL and do not run captures.
  --direct         Run jobs sequentially without the superpipeline.
  --start-from N   Start at tile N in the generated order (default: 1).
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

    (( emit_jobs == 0 || direct_mode == 0 )) || die "--emit-jobs and --direct cannot be combined."
}

selected_job_records() {
    case "$WORLD10_ORDER" in
        reverse|land-first|america-first)
            ;;
        *)
            die "WORLD10_ORDER must be reverse, land-first or america-first: $WORLD10_ORDER"
            ;;
    esac

    awk \
        -v start_from="$start_from_tile" \
        -v limit="$limit_tiles" \
        -v order="$WORLD10_ORDER" '
        /^# trueCount=[0-9]+ lat=\[-?[0-9]+,-?[0-9]+\) lon=\[-?[0-9]+,-?[0-9]+\)$/ {
            sequence++
            record = $0
            gsub(/[=\[\),]/, " ", record)
            split(record, fields, /[[:space:]]+/)
            true_count = fields[3]
            latitude = fields[5]
            longitude = fields[8]
            records[sequence] = true_count "\t" latitude "\t" longitude
        }
        END {
            total = sequence
            emitted = 0
            traversal_index = 0
            # The native catalog is already ordered by descending land-like
            # coverage.  For america-first make two stable passes over it: the
            # continental American bounds first, then every other block.  This
            # preserves the ocean-last property of the source catalog.
            passes = order == "america-first" ? 2 : 1
            for (pass = 1; pass <= passes; pass++) {
                direction = order == "reverse" ? -1 : 1
                source_sequence = order == "reverse" ? total : 1
                while (source_sequence >= 1 && source_sequence <= total) {
                    split(records[source_sequence], fields, /\t/)
                    latitude = fields[2]
                    longitude = fields[3]
                    american = latitude >= -56 && latitude < 76 && longitude >= -172 && longitude < -28
                    if (order == "america-first" && ((pass == 1 && !american) || (pass == 2 && american))) {
                        source_sequence += direction
                        continue
                    }
                    sequence = total - source_sequence + 1
                    traversal_index++
                    # --start-from names a position in the emitted traversal.
                    if (traversal_index >= start_from && (limit == 0 || emitted < limit)) {
                        printf "%d\t%d\t%s\n", sequence, total, records[source_sequence]
                        emitted++
                    }
                    source_sequence += direction
                }
            }
        }
    ' "${BASH_SOURCE[0]}"
}

emit_selected_jobs() {
    local sequence
    local total
    local true_count
    local lat
    local lon
    local center_lat
    local center_lon
    local route_command

    while IFS=$'\t' read -r sequence total true_count lat lon; do
        center_lat=$((lat + 4))
        center_lon=$((lon + 4))
        route_command="./run.sh zigzag $lat $lon 180000 3200 100000 8 8"
        # completionLevel and the geographic span are part of the provider/controller
        # contract.  They allow the controller to skip a whole 8x8-degree block only
        # when every level-10 leaf intersecting it is already in the production pyramid.
        printf '{"sequence":%d,"total":%d,"jobId":"world10-%06d-%s-%s","latitude":%s,"longitude":%s,"latitudeSpan":8,"longitudeSpan":8,"completionLevel":10,"center":"%s,%s","landLikeCells":%d,"routeCommand":"%s"}\n' \
            "$sequence" "$total" "$sequence" "$lat" "$lon" "$lat" "$lon" \
            "$center_lat" "$center_lon" "$true_count" "$route_command"
    done < <(selected_job_records)
}

delegate_to_superpipeline() {
    local -a command=(
        "$PROJECT_DIR/scripts/runSuperPipeline.sh"
        --job-source world10
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

run_direct() {
    local sequence
    local total
    local _true_count
    local lat
    local lon
    local route_command
    local status

    while IFS=$'\t' read -r sequence total _true_count lat lon; do
        route_command="./run.sh zigzag $lat $lon 180000 3200 100000 8 8"
        printf '[runWorld10][INFO] Starting cycle %d/%d at lat=%s lon=%s.\n' \
            "$sequence" "$total" "$lat" "$lon"
        if (
            cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml" &&
            cd "$PATH_PLANNER_DIR" &&
            ./run.sh zigzag "$lat" "$lon" 180000 3200 100000 8 8 &&
            cd "$PROJECT_DIR" &&
            ./scripts/runFullProcess.sh --route-command "$route_command"
        ); then
            printf '[runWorld10][INFO] Finished cycle %d/%d at lat=%s lon=%s.\n' \
                "$sequence" "$total" "$lat" "$lon"
        else
            status=$?
            printf '%s | script=runWorld10 | cycle=%s/%s | lat=%s lon=%s | route=%s | reason=tile_failed_status_%s\n' \
                "$(date --iso-8601=seconds)" "$sequence" "$total" "$lat" "$lon" \
                "$route_command" "$status" >> "$ERRORS_LOG" || true
            pkill -9 google-earth >/dev/null 2>&1 || true
        fi
    done < <(selected_job_records)
}

parse_args "$@"

if (( emit_jobs == 1 )); then
    emit_selected_jobs
    exit 0
fi

if (( direct_mode == 1 )); then
    run_direct
    exit 0
fi

delegate_to_superpipeline

# Generated by scripts/GenerateRunWorld.java.
# Blocks are stored by descending land-like coverage on a 320x180 world mask;
# selected_job_records consumes that catalog in reverse order.
# Classification only uses quadtree levels 0..5 from /samples/datasets/googleEarth/toplevel.
# The generated blocks below are the job catalog consumed by selected_job_records;
# all execution paths above exit or exec before reaching their legacy command bodies.

# trueCount=64 lat=[52,60) lon=[-148,-140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 -148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 -148 180000 3200 100000 8 8"

# trueCount=64 lat=[-60,-52) lon=[-148,-140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 -148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 -148 180000 3200 100000 8 8"

# trueCount=64 lat=[52,60) lon=[-76,-68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 -76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 -76 180000 3200 100000 8 8"

# trueCount=64 lat=[-4,4) lon=[-76,-68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 -76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 -76 180000 3200 100000 8 8"

# trueCount=64 lat=[-60,-52) lon=[-76,-68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 -76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 -76 180000 3200 100000 8 8"

# trueCount=64 lat=[52,60) lon=[-4,4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 -4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 -4 180000 3200 100000 8 8"

# trueCount=64 lat=[-60,-52) lon=[-4,4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 -4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 -4 180000 3200 100000 8 8"

# trueCount=64 lat=[52,60) lon=[68,76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 68 180000 3200 100000 8 8"

# trueCount=64 lat=[-60,-52) lon=[68,76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 68 180000 3200 100000 8 8"

# trueCount=64 lat=[52,60) lon=[140,148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 140 180000 3200 100000 8 8"

# trueCount=64 lat=[-60,-52) lon=[140,148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 140 180000 3200 100000 8 8"

# trueCount=63 lat=[20,28) lon=[68,76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 68 180000 3200 100000 8 8"

# trueCount=61 lat=[12,20) lon=[68,76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 68 180000 3200 100000 8 8"

# trueCount=60 lat=[28,36) lon=[68,76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 68 180000 3200 100000 8 8"

# trueCount=59 lat=[20,28) lon=[-76,-68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 -76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 -76 180000 3200 100000 8 8"

# trueCount=59 lat=[-20,-12) lon=[140,148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 140 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[-180,-172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 -180 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 -180 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[-180,-172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 -180 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 -180 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[-172,-164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 -172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 -172 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[-172,-164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 -172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 -172 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[-164,-156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 -164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 -164 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[-164,-156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 -164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 -164 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[-156,-148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 -156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 -156 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[-156,-148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 -156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 -156 180000 3200 100000 8 8"

# trueCount=56 lat=[44,52) lon=[-148,-140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 -148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 -148 180000 3200 100000 8 8"

# trueCount=56 lat=[-52,-44) lon=[-148,-140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 -148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 -148 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[-140,-132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 -140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 -140 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[-140,-132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 -140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 -140 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[-132,-124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 -132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 -132 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[-132,-124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 -132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 -132 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[-124,-116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 -124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 -124 180000 3200 100000 8 8"

# trueCount=56 lat=[20,28) lon=[-124,-116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 -124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 -124 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[-124,-116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 -124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 -124 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[-116,-108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 -116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 -116 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[-116,-108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 -116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 -116 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[-108,-100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 -108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 -108 180000 3200 100000 8 8"

# trueCount=56 lat=[20,28) lon=[-108,-100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 -108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 -108 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[-108,-100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 -108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 -108 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[-100,-92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 -100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 -100 180000 3200 100000 8 8"

# trueCount=56 lat=[20,28) lon=[-100,-92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 -100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 -100 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[-100,-92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 -100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 -100 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[-92,-84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 -92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 -92 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[-92,-84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 -92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 -92 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[-84,-76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 -84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 -84 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[-84,-76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 -84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 -84 180000 3200 100000 8 8"

# trueCount=56 lat=[44,52) lon=[-76,-68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 -76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 -76 180000 3200 100000 8 8"

# trueCount=56 lat=[-52,-44) lon=[-76,-68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 -76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 -76 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[-68,-60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 -68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 -68 180000 3200 100000 8 8"

# trueCount=56 lat=[-4,4) lon=[-68,-60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 -68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 -68 180000 3200 100000 8 8"

# trueCount=56 lat=[-12,-4) lon=[-68,-60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 -68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 -68 180000 3200 100000 8 8"

# trueCount=56 lat=[-20,-12) lon=[-68,-60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 -68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 -68 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[-68,-60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 -68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 -68 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[-60,-52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 -60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 -60 180000 3200 100000 8 8"

# trueCount=56 lat=[-12,-4) lon=[-60,-52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 -60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 -60 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[-60,-52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 -60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 -60 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[-52,-44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 -52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 -52 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[-52,-44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 -52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 -52 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[-44,-36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 -44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 -44 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[-44,-36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 -44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 -44 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[-36,-28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 -36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 -36 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[-36,-28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 -36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 -36 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[-28,-20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 -28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 -28 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[-28,-20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 -28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 -28 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[-20,-12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 -20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 -20 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[-20,-12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 -20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 -20 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[-12,-4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 -12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 -12 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[-12,-4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 -12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 -12 180000 3200 100000 8 8"

# trueCount=56 lat=[44,52) lon=[-4,4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 -4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 -4 180000 3200 100000 8 8"

# trueCount=56 lat=[-52,-44) lon=[-4,4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 -4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 -4 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[4,12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 4 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[4,12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 4 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[12,20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 12 180000 3200 100000 8 8"

# trueCount=56 lat=[-4,4) lon=[12,20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 12 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[12,20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 12 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[20,28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 20 180000 3200 100000 8 8"

# trueCount=56 lat=[-4,4) lon=[20,28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 20 180000 3200 100000 8 8"

# trueCount=56 lat=[-12,-4) lon=[20,28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 20 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[20,28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 20 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[28,36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 28 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[28,36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 28 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[36,44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 36 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[36,44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 36 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[44,52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 44 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[44,52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 44 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[52,60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 52 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[52,60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 52 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[60,68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 60 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[60,68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 60 180000 3200 100000 8 8"

# trueCount=56 lat=[44,52) lon=[68,76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 68 180000 3200 100000 8 8"

# trueCount=56 lat=[-52,-44) lon=[68,76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 68 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[76,84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 76 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[76,84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 76 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[84,92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 84 180000 3200 100000 8 8"

# trueCount=56 lat=[28,36) lon=[84,92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 84 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[84,92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 84 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[92,100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 92 180000 3200 100000 8 8"

# trueCount=56 lat=[28,36) lon=[92,100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 92 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[92,100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 92 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[100,108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 100 180000 3200 100000 8 8"

# trueCount=56 lat=[28,36) lon=[100,108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 100 180000 3200 100000 8 8"

# trueCount=56 lat=[12,20) lon=[100,108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 100 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[100,108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 100 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[108,116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 108 180000 3200 100000 8 8"

# trueCount=56 lat=[28,36) lon=[108,116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 108 180000 3200 100000 8 8"

# trueCount=56 lat=[12,20) lon=[108,116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 108 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[108,116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 108 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[116,124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 116 180000 3200 100000 8 8"

# trueCount=56 lat=[28,36) lon=[116,124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 116 180000 3200 100000 8 8"

# trueCount=56 lat=[20,28) lon=[116,124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 116 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[116,124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 116 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[124,132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 124 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[124,132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 124 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[132,140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 132 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[132,140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 132 180000 3200 100000 8 8"

# trueCount=56 lat=[44,52) lon=[140,148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 140 180000 3200 100000 8 8"

# trueCount=56 lat=[-52,-44) lon=[140,148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 140 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[148,156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 148 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[148,156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 148 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[156,164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 156 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[156,164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 156 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[164,172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 164 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[164,172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 164 180000 3200 100000 8 8"

# trueCount=56 lat=[52,60) lon=[172,180)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 52 172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 52 172 180000 3200 100000 8 8"

# trueCount=56 lat=[-60,-52) lon=[172,180)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -60 172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -60 172 180000 3200 100000 8 8"

# trueCount=55 lat=[20,28) lon=[-116,-108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 -116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 -116 180000 3200 100000 8 8"

# trueCount=55 lat=[12,20) lon=[-108,-100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 -108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 -108 180000 3200 100000 8 8"

# trueCount=55 lat=[-12,-4) lon=[-52,-44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 -52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 -52 180000 3200 100000 8 8"

# trueCount=55 lat=[20,28) lon=[20,28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 20 180000 3200 100000 8 8"

# trueCount=55 lat=[20,28) lon=[60,68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 60 180000 3200 100000 8 8"

# trueCount=55 lat=[28,36) lon=[76,84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 76 180000 3200 100000 8 8"

# trueCount=55 lat=[28,36) lon=[124,132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 124 180000 3200 100000 8 8"

# trueCount=55 lat=[28,36) lon=[132,140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 132 180000 3200 100000 8 8"

# trueCount=54 lat=[20,28) lon=[76,84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 76 180000 3200 100000 8 8"

# trueCount=54 lat=[12,20) lon=[92,100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 92 180000 3200 100000 8 8"

# trueCount=54 lat=[20,28) lon=[108,116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 108 180000 3200 100000 8 8"

# trueCount=54 lat=[20,28) lon=[124,132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 124 180000 3200 100000 8 8"

# trueCount=53 lat=[-4,4) lon=[28,36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 28 180000 3200 100000 8 8"

# trueCount=53 lat=[20,28) lon=[84,92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 84 180000 3200 100000 8 8"

# trueCount=53 lat=[20,28) lon=[92,100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 92 180000 3200 100000 8 8"

# trueCount=53 lat=[20,28) lon=[100,108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 100 180000 3200 100000 8 8"

# trueCount=52 lat=[12,20) lon=[84,92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 84 180000 3200 100000 8 8"

# trueCount=51 lat=[28,36) lon=[-116,-108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 -116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 -116 180000 3200 100000 8 8"

# trueCount=51 lat=[-4,4) lon=[-60,-52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 -60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 -60 180000 3200 100000 8 8"

# trueCount=51 lat=[4,12) lon=[-12,-4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 -12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 -12 180000 3200 100000 8 8"

# trueCount=51 lat=[12,20) lon=[-4,4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 -4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 -4 180000 3200 100000 8 8"

# trueCount=51 lat=[-12,-4) lon=[28,36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 28 180000 3200 100000 8 8"

# trueCount=51 lat=[28,36) lon=[52,60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 52 180000 3200 100000 8 8"

# trueCount=51 lat=[12,20) lon=[76,84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 76 180000 3200 100000 8 8"

# trueCount=50 lat=[20,28) lon=[52,60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 52 180000 3200 100000 8 8"

# trueCount=50 lat=[28,36) lon=[60,68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 60 180000 3200 100000 8 8"

# trueCount=50 lat=[28,36) lon=[140,148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 140 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[-180,-172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 -180 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 -180 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[-180,-172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 -180 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 -180 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[-172,-164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 -172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 -172 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[-172,-164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 -172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 -172 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[-164,-156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 -164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 -164 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[-164,-156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 -164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 -164 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[-156,-148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 -156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 -156 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[-156,-148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 -156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 -156 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[-140,-132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 -140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 -140 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[-140,-132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 -140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 -140 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[-132,-124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 -132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 -132 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[-132,-124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 -132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 -132 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[-124,-116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 -124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 -124 180000 3200 100000 8 8"

# trueCount=49 lat=[28,36) lon=[-124,-116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 -124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 -124 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[-124,-116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 -124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 -124 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[-116,-108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 -116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 -116 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[-116,-108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 -116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 -116 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[-108,-100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 -108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 -108 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[-108,-100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 -108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 -108 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[-100,-92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 -100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 -100 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[-100,-92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 -100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 -100 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[-92,-84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 -92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 -92 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[-92,-84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 -92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 -92 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[-84,-76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 -84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 -84 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[-84,-76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 -84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 -84 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[-68,-60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 -68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 -68 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[-68,-60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 -68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 -68 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[-60,-52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 -60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 -60 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[-60,-52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 -60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 -60 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[-52,-44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 -52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 -52 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[-52,-44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 -52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 -52 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[-44,-36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 -44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 -44 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[-44,-36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 -44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 -44 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[-36,-28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 -36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 -36 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[-36,-28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 -36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 -36 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[-28,-20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 -28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 -28 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[-28,-20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 -28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 -28 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[-20,-12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 -20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 -20 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[-20,-12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 -20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 -20 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[-12,-4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 -12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 -12 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[-12,-4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 -12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 -12 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[4,12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 4 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[4,12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 4 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[12,20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 12 180000 3200 100000 8 8"

# trueCount=49 lat=[-12,-4) lon=[12,20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 12 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[12,20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 12 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[20,28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 20 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[20,28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 20 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[28,36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 28 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[28,36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 28 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[36,44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 36 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[36,44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 36 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[44,52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 44 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[44,52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 44 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[52,60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 52 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[52,60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 52 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[60,68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 60 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[60,68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 60 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[76,84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 76 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[76,84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 76 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[84,92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 84 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[84,92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 84 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[92,100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 92 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[92,100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 92 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[100,108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 100 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[100,108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 100 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[108,116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 108 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[108,116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 108 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[116,124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 116 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[116,124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 116 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[124,132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 124 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[124,132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 124 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[132,140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 132 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[132,140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 132 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[148,156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 148 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[148,156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 148 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[156,164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 156 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[156,164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 156 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[164,172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 164 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[164,172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 164 180000 3200 100000 8 8"

# trueCount=49 lat=[44,52) lon=[172,180)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 44 172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 44 172 180000 3200 100000 8 8"

# trueCount=49 lat=[-52,-44) lon=[172,180)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -52 172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -52 172 180000 3200 100000 8 8"

# trueCount=48 lat=[28,36) lon=[-132,-124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 -132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 -132 180000 3200 100000 8 8"

# trueCount=48 lat=[4,12) lon=[-4,4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 -4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 -4 180000 3200 100000 8 8"

# trueCount=48 lat=[4,12) lon=[20,28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 20 180000 3200 100000 8 8"

# trueCount=48 lat=[20,28) lon=[44,52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 44 180000 3200 100000 8 8"

# trueCount=47 lat=[28,36) lon=[-108,-100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 -108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 -108 180000 3200 100000 8 8"

# trueCount=46 lat=[4,12) lon=[28,36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 28 180000 3200 100000 8 8"

# trueCount=46 lat=[20,28) lon=[36,44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 36 180000 3200 100000 8 8"

# trueCount=45 lat=[20,28) lon=[-92,-84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 -92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 -92 180000 3200 100000 8 8"

# trueCount=45 lat=[28,36) lon=[28,36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 28 180000 3200 100000 8 8"

# trueCount=45 lat=[12,20) lon=[60,68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 60 180000 3200 100000 8 8"

# trueCount=44 lat=[12,20) lon=[36,44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 36 180000 3200 100000 8 8"

# trueCount=43 lat=[28,36) lon=[-100,-92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 -100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 -100 180000 3200 100000 8 8"

# trueCount=43 lat=[12,20) lon=[-100,-92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 -100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 -100 180000 3200 100000 8 8"

# trueCount=43 lat=[4,12) lon=[4,12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 4 180000 3200 100000 8 8"

# trueCount=43 lat=[20,28) lon=[12,20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 12 180000 3200 100000 8 8"

# trueCount=43 lat=[-4,4) lon=[36,44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 36 180000 3200 100000 8 8"

# trueCount=42 lat=[-20,-12) lon=[-60,-52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 -60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 -60 180000 3200 100000 8 8"

# trueCount=42 lat=[4,12) lon=[12,20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 12 180000 3200 100000 8 8"

# trueCount=42 lat=[28,36) lon=[44,52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 44 180000 3200 100000 8 8"

# trueCount=41 lat=[12,20) lon=[-116,-108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 -116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 -116 180000 3200 100000 8 8"

# trueCount=41 lat=[28,36) lon=[-76,-68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 -76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 -76 180000 3200 100000 8 8"

# trueCount=41 lat=[-12,-4) lon=[-76,-68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 -76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 -76 180000 3200 100000 8 8"

# trueCount=41 lat=[20,28) lon=[4,12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 4 180000 3200 100000 8 8"

# trueCount=41 lat=[28,36) lon=[148,156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 148 180000 3200 100000 8 8"

# trueCount=40 lat=[28,36) lon=[20,28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 20 180000 3200 100000 8 8"

# trueCount=40 lat=[20,28) lon=[28,36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 28 180000 3200 100000 8 8"

# trueCount=40 lat=[12,20) lon=[52,60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 52 180000 3200 100000 8 8"

# trueCount=40 lat=[20,28) lon=[132,140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 132 180000 3200 100000 8 8"

# trueCount=40 lat=[28,36) lon=[156,164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 156 180000 3200 100000 8 8"

# trueCount=39 lat=[28,36) lon=[-164,-156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 -164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 -164 180000 3200 100000 8 8"

# trueCount=39 lat=[-28,-20) lon=[-76,-68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 -76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 -76 180000 3200 100000 8 8"

# trueCount=39 lat=[4,12) lon=[36,44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 36 180000 3200 100000 8 8"

# trueCount=39 lat=[4,12) lon=[100,108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 100 180000 3200 100000 8 8"

# trueCount=38 lat=[28,36) lon=[-140,-132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 -140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 -140 180000 3200 100000 8 8"

# trueCount=38 lat=[20,28) lon=[-4,4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 -4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 -4 180000 3200 100000 8 8"

# trueCount=38 lat=[28,36) lon=[36,44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 36 180000 3200 100000 8 8"

# trueCount=37 lat=[20,28) lon=[-84,-76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 -84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 -84 180000 3200 100000 8 8"

# trueCount=37 lat=[-20,-12) lon=[116,124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 116 180000 3200 100000 8 8"

# trueCount=36 lat=[28,36) lon=[-156,-148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 -156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 -156 180000 3200 100000 8 8"

# trueCount=36 lat=[28,36) lon=[-148,-140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 -148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 -148 180000 3200 100000 8 8"

# trueCount=36 lat=[-12,-4) lon=[140,148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 140 180000 3200 100000 8 8"

# trueCount=35 lat=[12,20) lon=[-92,-84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 -92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 -92 180000 3200 100000 8 8"

# trueCount=35 lat=[28,36) lon=[12,20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 12 180000 3200 100000 8 8"

# trueCount=35 lat=[-20,-12) lon=[20,28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 20 180000 3200 100000 8 8"

# trueCount=35 lat=[4,12) lon=[76,84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 76 180000 3200 100000 8 8"

# trueCount=35 lat=[12,20) lon=[116,124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 116 180000 3200 100000 8 8"

# trueCount=35 lat=[-12,-4) lon=[132,140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 132 180000 3200 100000 8 8"

# trueCount=35 lat=[-20,-12) lon=[132,140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 132 180000 3200 100000 8 8"

# trueCount=35 lat=[28,36) lon=[164,172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 164 180000 3200 100000 8 8"

# trueCount=34 lat=[-12,-4) lon=[-44,-36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 -44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 -44 180000 3200 100000 8 8"

# trueCount=34 lat=[12,20) lon=[44,52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 44 180000 3200 100000 8 8"

# trueCount=34 lat=[-12,-4) lon=[124,132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 124 180000 3200 100000 8 8"

# trueCount=33 lat=[12,20) lon=[-84,-76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 -84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 -84 180000 3200 100000 8 8"

# trueCount=31 lat=[-20,-12) lon=[-76,-68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 -76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 -76 180000 3200 100000 8 8"

# trueCount=31 lat=[12,20) lon=[-12,-4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 -12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 -12 180000 3200 100000 8 8"

# trueCount=31 lat=[4,12) lon=[92,100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 92 180000 3200 100000 8 8"

# trueCount=30 lat=[12,20) lon=[4,12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 4 180000 3200 100000 8 8"

# trueCount=29 lat=[-4,4) lon=[-52,-44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 -52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 -52 180000 3200 100000 8 8"

# trueCount=29 lat=[-20,-12) lon=[124,132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 124 180000 3200 100000 8 8"

# trueCount=28 lat=[20,28) lon=[-68,-60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 -68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 -68 180000 3200 100000 8 8"

# trueCount=28 lat=[12,20) lon=[28,36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 28 180000 3200 100000 8 8"

# trueCount=27 lat=[28,36) lon=[172,180)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 172 180000 3200 100000 8 8"

# trueCount=25 lat=[4,12) lon=[44,52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 44 180000 3200 100000 8 8"

# trueCount=23 lat=[28,36) lon=[-92,-84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 -92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 -92 180000 3200 100000 8 8"

# trueCount=23 lat=[-4,4) lon=[108,116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 108 180000 3200 100000 8 8"

# trueCount=22 lat=[36,44) lon=[-92,-84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 -92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 -92 180000 3200 100000 8 8"

# trueCount=22 lat=[-4,4) lon=[4,12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 4 180000 3200 100000 8 8"

# trueCount=22 lat=[-20,-12) lon=[148,156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 148 180000 3200 100000 8 8"

# trueCount=21 lat=[4,12) lon=[-20,-12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 -20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 -20 180000 3200 100000 8 8"

# trueCount=21 lat=[4,12) lon=[68,76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 68 180000 3200 100000 8 8"

# trueCount=21 lat=[-4,4) lon=[100,108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 100 180000 3200 100000 8 8"

# trueCount=20 lat=[20,28) lon=[-132,-124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 -132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 -132 180000 3200 100000 8 8"

# trueCount=20 lat=[-4,4) lon=[-84,-76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 -84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 -84 180000 3200 100000 8 8"

# trueCount=20 lat=[-12,-4) lon=[44,52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 44 180000 3200 100000 8 8"

# trueCount=19 lat=[4,12) lon=[-92,-84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 -92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 -92 180000 3200 100000 8 8"

# trueCount=18 lat=[28,36) lon=[-84,-76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 -84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 -84 180000 3200 100000 8 8"

# trueCount=18 lat=[4,12) lon=[-76,-68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 -76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 -76 180000 3200 100000 8 8"

# trueCount=18 lat=[-12,-4) lon=[36,44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 36 180000 3200 100000 8 8"

# trueCount=18 lat=[-12,-4) lon=[116,124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 116 180000 3200 100000 8 8"

# trueCount=17 lat=[12,20) lon=[-124,-116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 -124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 -124 180000 3200 100000 8 8"

# trueCount=17 lat=[28,36) lon=[4,12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 4 180000 3200 100000 8 8"

# trueCount=17 lat=[12,20) lon=[12,20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 12 180000 3200 100000 8 8"

# trueCount=17 lat=[-20,-12) lon=[12,20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 12 180000 3200 100000 8 8"

# trueCount=17 lat=[36,44) lon=[92,100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 92 180000 3200 100000 8 8"

# trueCount=15 lat=[4,12) lon=[-100,-92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 -100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 -100 180000 3200 100000 8 8"

# trueCount=15 lat=[-4,4) lon=[-44,-36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 -44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 -44 180000 3200 100000 8 8"

# trueCount=15 lat=[36,44) lon=[100,108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 100 180000 3200 100000 8 8"

# trueCount=14 lat=[4,12) lon=[-108,-100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 -108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 -108 180000 3200 100000 8 8"

# trueCount=14 lat=[36,44) lon=[-100,-92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 -100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 -100 180000 3200 100000 8 8"

# trueCount=14 lat=[-4,4) lon=[140,148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 140 180000 3200 100000 8 8"

# trueCount=13 lat=[-20,-12) lon=[28,36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 28 180000 3200 100000 8 8"

# trueCount=13 lat=[4,12) lon=[52,60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 52 180000 3200 100000 8 8"

# trueCount=13 lat=[-4,4) lon=[116,124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 116 180000 3200 100000 8 8"

# trueCount=12 lat=[36,44) lon=[-116,-108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 -116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 -116 180000 3200 100000 8 8"

# trueCount=12 lat=[28,36) lon=[-68,-60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 -68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 -68 180000 3200 100000 8 8"

# trueCount=12 lat=[20,28) lon=[-60,-52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 -60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 -60 180000 3200 100000 8 8"

# trueCount=12 lat=[36,44) lon=[108,116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 108 180000 3200 100000 8 8"

# trueCount=12 lat=[-4,4) lon=[132,140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 132 180000 3200 100000 8 8"

# trueCount=11 lat=[12,20) lon=[20,28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 20 180000 3200 100000 8 8"

# trueCount=11 lat=[-4,4) lon=[44,52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 44 180000 3200 100000 8 8"

# trueCount=11 lat=[4,12) lon=[108,116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 108 180000 3200 100000 8 8"

# trueCount=10 lat=[36,44) lon=[-84,-76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 -84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 -84 180000 3200 100000 8 8"

# trueCount=10 lat=[-28,-20) lon=[-68,-60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 -68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 -68 180000 3200 100000 8 8"

# trueCount=10 lat=[36,44) lon=[-28,-20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 -28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 -28 180000 3200 100000 8 8"

# trueCount=10 lat=[36,44) lon=[84,92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 84 180000 3200 100000 8 8"

# trueCount=10 lat=[20,28) lon=[140,148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 140 180000 3200 100000 8 8"

# trueCount=9 lat=[28,36) lon=[-180,-172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 -180 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 -180 180000 3200 100000 8 8"

# trueCount=9 lat=[4,12) lon=[-84,-76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 -84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 -84 180000 3200 100000 8 8"

# trueCount=9 lat=[36,44) lon=[-76,-68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 -76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 -76 180000 3200 100000 8 8"

# trueCount=9 lat=[4,12) lon=[-68,-60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 -68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 -68 180000 3200 100000 8 8"

# trueCount=9 lat=[-20,-12) lon=[-52,-44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 -52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 -52 180000 3200 100000 8 8"

# trueCount=9 lat=[4,12) lon=[84,92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 84 180000 3200 100000 8 8"

# trueCount=9 lat=[20,28) lon=[156,164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 156 180000 3200 100000 8 8"

# trueCount=8 lat=[28,36) lon=[-28,-20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 -28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 -28 180000 3200 100000 8 8"

# trueCount=8 lat=[20,28) lon=[-12,-4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 -12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 -12 180000 3200 100000 8 8"

# trueCount=8 lat=[-4,4) lon=[-4,4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 -4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 -4 180000 3200 100000 8 8"

# trueCount=8 lat=[12,20) lon=[124,132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 124 180000 3200 100000 8 8"

# trueCount=7 lat=[28,36) lon=[-172,-164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 -172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 -172 180000 3200 100000 8 8"

# trueCount=7 lat=[36,44) lon=[-124,-116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 -124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 -124 180000 3200 100000 8 8"

# trueCount=7 lat=[-4,4) lon=[-12,-4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 -12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 -12 180000 3200 100000 8 8"

# trueCount=7 lat=[12,20) lon=[132,140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 132 180000 3200 100000 8 8"

# trueCount=7 lat=[-28,-20) lon=[164,172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 164 180000 3200 100000 8 8"

# trueCount=6 lat=[36,44) lon=[-108,-100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 -108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 -108 180000 3200 100000 8 8"

# trueCount=6 lat=[4,12) lon=[116,124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 116 180000 3200 100000 8 8"

# trueCount=6 lat=[36,44) lon=[140,148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 140 180000 3200 100000 8 8"

# trueCount=5 lat=[28,36) lon=[-52,-44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 -52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 -52 180000 3200 100000 8 8"

# trueCount=5 lat=[-4,4) lon=[92,100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 92 180000 3200 100000 8 8"

# trueCount=5 lat=[-20,-12) lon=[108,116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 108 180000 3200 100000 8 8"

# trueCount=5 lat=[36,44) lon=[124,132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 124 180000 3200 100000 8 8"

# trueCount=4 lat=[36,44) lon=[-60,-52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 -60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 -60 180000 3200 100000 8 8"

# trueCount=4 lat=[-12,-4) lon=[108,116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 108 180000 3200 100000 8 8"

# trueCount=4 lat=[36,44) lon=[116,124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 116 180000 3200 100000 8 8"

# trueCount=4 lat=[-12,-4) lon=[148,156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 148 180000 3200 100000 8 8"

# trueCount=3 lat=[-12,-4) lon=[-84,-76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 -84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 -84 180000 3200 100000 8 8"

# trueCount=3 lat=[36,44) lon=[-68,-60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 -68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 -68 180000 3200 100000 8 8"

# trueCount=3 lat=[36,44) lon=[-52,-44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 -52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 -52 180000 3200 100000 8 8"

# trueCount=3 lat=[28,36) lon=[-20,-12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 -20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 -20 180000 3200 100000 8 8"

# trueCount=3 lat=[36,44) lon=[76,84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 76 180000 3200 100000 8 8"

# trueCount=3 lat=[-28,-20) lon=[140,148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 140 180000 3200 100000 8 8"

# trueCount=3 lat=[-20,-12) lon=[172,180)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 172 180000 3200 100000 8 8"

# trueCount=2 lat=[20,28) lon=[-140,-132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 -140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 -140 180000 3200 100000 8 8"

# trueCount=2 lat=[-44,-36) lon=[-84,-76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 -84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 -84 180000 3200 100000 8 8"

# trueCount=2 lat=[12,20) lon=[-76,-68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 -76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 -76 180000 3200 100000 8 8"

# trueCount=2 lat=[-36,-28) lon=[-68,-60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 -68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 -68 180000 3200 100000 8 8"

# trueCount=2 lat=[28,36) lon=[-60,-52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 -60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 -60 180000 3200 100000 8 8"

# trueCount=2 lat=[28,36) lon=[-44,-36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 -44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 -44 180000 3200 100000 8 8"

# trueCount=2 lat=[36,44) lon=[-36,-28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 -36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 -36 180000 3200 100000 8 8"

# trueCount=2 lat=[36,44) lon=[-20,-12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 -20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 -20 180000 3200 100000 8 8"

# trueCount=2 lat=[12,20) lon=[-20,-12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 -20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 -20 180000 3200 100000 8 8"

# trueCount=2 lat=[28,36) lon=[-4,4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 -4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 -4 180000 3200 100000 8 8"

# trueCount=2 lat=[36,44) lon=[52,60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 52 180000 3200 100000 8 8"

# trueCount=2 lat=[36,44) lon=[68,76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 68 180000 3200 100000 8 8"

# trueCount=2 lat=[-4,4) lon=[76,84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 76 180000 3200 100000 8 8"

# trueCount=2 lat=[-4,4) lon=[124,132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 124 180000 3200 100000 8 8"

# trueCount=2 lat=[36,44) lon=[132,140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 132 180000 3200 100000 8 8"

# trueCount=2 lat=[12,20) lon=[140,148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 140 180000 3200 100000 8 8"

# trueCount=2 lat=[-44,-36) lon=[156,164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 156 180000 3200 100000 8 8"

# trueCount=2 lat=[-28,-20) lon=[172,180)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 172 180000 3200 100000 8 8"

# trueCount=1 lat=[20,28) lon=[-164,-156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 -164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 -164 180000 3200 100000 8 8"

# trueCount=1 lat=[36,44) lon=[-132,-124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 -132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 -132 180000 3200 100000 8 8"

# trueCount=1 lat=[-44,-36) lon=[-124,-116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 -124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 -124 180000 3200 100000 8 8"

# trueCount=1 lat=[4,12) lon=[-116,-108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 -116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 -116 180000 3200 100000 8 8"

# trueCount=1 lat=[-44,-36) lon=[-92,-84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 -92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 -92 180000 3200 100000 8 8"

# trueCount=1 lat=[-36,-28) lon=[-76,-68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 -76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 -76 180000 3200 100000 8 8"

# trueCount=1 lat=[-44,-36) lon=[-68,-60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 -68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 -68 180000 3200 100000 8 8"

# trueCount=1 lat=[-44,-36) lon=[-44,-36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 -44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 -44 180000 3200 100000 8 8"

# trueCount=1 lat=[28,36) lon=[-36,-28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 -36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 -36 180000 3200 100000 8 8"

# trueCount=1 lat=[-4,4) lon=[-36,-28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 -36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 -36 180000 3200 100000 8 8"

# trueCount=1 lat=[-12,-4) lon=[-36,-28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 -36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 -36 180000 3200 100000 8 8"

# trueCount=1 lat=[-44,-36) lon=[-28,-20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 -28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 -28 180000 3200 100000 8 8"

# trueCount=1 lat=[-36,-28) lon=[-20,-12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 -20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 -20 180000 3200 100000 8 8"

# trueCount=1 lat=[28,36) lon=[-12,-4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 28 -12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 28 -12 180000 3200 100000 8 8"

# trueCount=1 lat=[-36,-28) lon=[-12,-4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 -12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 -12 180000 3200 100000 8 8"

# trueCount=1 lat=[36,44) lon=[20,28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 20 180000 3200 100000 8 8"

# trueCount=1 lat=[-20,-12) lon=[44,52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 44 180000 3200 100000 8 8"

# trueCount=1 lat=[-44,-36) lon=[60,68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 60 180000 3200 100000 8 8"

# trueCount=1 lat=[-36,-28) lon=[68,76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 68 180000 3200 100000 8 8"

# trueCount=1 lat=[-36,-28) lon=[76,84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 76 180000 3200 100000 8 8"

# trueCount=1 lat=[4,12) lon=[124,132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 124 180000 3200 100000 8 8"

# trueCount=1 lat=[20,28) lon=[148,156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 148 180000 3200 100000 8 8"

# trueCount=1 lat=[-4,4) lon=[148,156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 148 180000 3200 100000 8 8"

# trueCount=1 lat=[-44,-36) lon=[164,172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 164 180000 3200 100000 8 8"

# trueCount=0 lat=[36,44) lon=[-180,-172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 -180 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 -180 180000 3200 100000 8 8"

# trueCount=0 lat=[20,28) lon=[-180,-172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 -180 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 -180 180000 3200 100000 8 8"

# trueCount=0 lat=[12,20) lon=[-180,-172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 -180 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 -180 180000 3200 100000 8 8"

# trueCount=0 lat=[4,12) lon=[-180,-172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 -180 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 -180 180000 3200 100000 8 8"

# trueCount=0 lat=[-4,4) lon=[-180,-172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 -180 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 -180 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[-180,-172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 -180 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 -180 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[-180,-172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 -180 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 -180 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[-180,-172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 -180 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 -180 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[-180,-172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 -180 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 -180 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[-180,-172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 -180 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 -180 180000 3200 100000 8 8"

# trueCount=0 lat=[36,44) lon=[-172,-164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 -172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 -172 180000 3200 100000 8 8"

# trueCount=0 lat=[20,28) lon=[-172,-164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 -172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 -172 180000 3200 100000 8 8"

# trueCount=0 lat=[12,20) lon=[-172,-164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 -172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 -172 180000 3200 100000 8 8"

# trueCount=0 lat=[4,12) lon=[-172,-164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 -172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 -172 180000 3200 100000 8 8"

# trueCount=0 lat=[-4,4) lon=[-172,-164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 -172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 -172 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[-172,-164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 -172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 -172 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[-172,-164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 -172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 -172 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[-172,-164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 -172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 -172 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[-172,-164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 -172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 -172 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[-172,-164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 -172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 -172 180000 3200 100000 8 8"

# trueCount=0 lat=[36,44) lon=[-164,-156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 -164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 -164 180000 3200 100000 8 8"

# trueCount=0 lat=[12,20) lon=[-164,-156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 -164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 -164 180000 3200 100000 8 8"

# trueCount=0 lat=[4,12) lon=[-164,-156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 -164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 -164 180000 3200 100000 8 8"

# trueCount=0 lat=[-4,4) lon=[-164,-156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 -164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 -164 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[-164,-156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 -164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 -164 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[-164,-156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 -164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 -164 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[-164,-156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 -164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 -164 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[-164,-156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 -164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 -164 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[-164,-156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 -164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 -164 180000 3200 100000 8 8"

# trueCount=0 lat=[36,44) lon=[-156,-148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 -156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 -156 180000 3200 100000 8 8"

# trueCount=0 lat=[20,28) lon=[-156,-148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 -156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 -156 180000 3200 100000 8 8"

# trueCount=0 lat=[12,20) lon=[-156,-148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 -156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 -156 180000 3200 100000 8 8"

# trueCount=0 lat=[4,12) lon=[-156,-148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 -156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 -156 180000 3200 100000 8 8"

# trueCount=0 lat=[-4,4) lon=[-156,-148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 -156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 -156 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[-156,-148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 -156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 -156 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[-156,-148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 -156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 -156 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[-156,-148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 -156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 -156 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[-156,-148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 -156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 -156 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[-156,-148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 -156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 -156 180000 3200 100000 8 8"

# trueCount=0 lat=[36,44) lon=[-148,-140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 -148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 -148 180000 3200 100000 8 8"

# trueCount=0 lat=[20,28) lon=[-148,-140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 -148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 -148 180000 3200 100000 8 8"

# trueCount=0 lat=[12,20) lon=[-148,-140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 -148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 -148 180000 3200 100000 8 8"

# trueCount=0 lat=[4,12) lon=[-148,-140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 -148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 -148 180000 3200 100000 8 8"

# trueCount=0 lat=[-4,4) lon=[-148,-140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 -148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 -148 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[-148,-140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 -148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 -148 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[-148,-140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 -148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 -148 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[-148,-140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 -148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 -148 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[-148,-140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 -148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 -148 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[-148,-140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 -148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 -148 180000 3200 100000 8 8"

# trueCount=0 lat=[36,44) lon=[-140,-132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 -140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 -140 180000 3200 100000 8 8"

# trueCount=0 lat=[12,20) lon=[-140,-132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 -140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 -140 180000 3200 100000 8 8"

# trueCount=0 lat=[4,12) lon=[-140,-132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 -140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 -140 180000 3200 100000 8 8"

# trueCount=0 lat=[-4,4) lon=[-140,-132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 -140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 -140 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[-140,-132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 -140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 -140 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[-140,-132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 -140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 -140 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[-140,-132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 -140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 -140 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[-140,-132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 -140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 -140 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[-140,-132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 -140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 -140 180000 3200 100000 8 8"

# trueCount=0 lat=[12,20) lon=[-132,-124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 -132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 -132 180000 3200 100000 8 8"

# trueCount=0 lat=[4,12) lon=[-132,-124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 -132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 -132 180000 3200 100000 8 8"

# trueCount=0 lat=[-4,4) lon=[-132,-124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 -132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 -132 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[-132,-124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 -132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 -132 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[-132,-124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 -132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 -132 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[-132,-124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 -132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 -132 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[-132,-124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 -132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 -132 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[-132,-124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 -132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 -132 180000 3200 100000 8 8"

# trueCount=0 lat=[4,12) lon=[-124,-116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 -124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 -124 180000 3200 100000 8 8"

# trueCount=0 lat=[-4,4) lon=[-124,-116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 -124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 -124 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[-124,-116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 -124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 -124 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[-124,-116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 -124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 -124 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[-124,-116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 -124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 -124 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[-124,-116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 -124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 -124 180000 3200 100000 8 8"

# trueCount=0 lat=[-4,4) lon=[-116,-108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 -116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 -116 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[-116,-108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 -116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 -116 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[-116,-108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 -116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 -116 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[-116,-108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 -116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 -116 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[-116,-108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 -116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 -116 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[-116,-108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 -116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 -116 180000 3200 100000 8 8"

# trueCount=0 lat=[-4,4) lon=[-108,-100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 -108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 -108 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[-108,-100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 -108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 -108 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[-108,-100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 -108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 -108 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[-108,-100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 -108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 -108 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[-108,-100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 -108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 -108 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[-108,-100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 -108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 -108 180000 3200 100000 8 8"

# trueCount=0 lat=[-4,4) lon=[-100,-92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 -100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 -100 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[-100,-92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 -100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 -100 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[-100,-92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 -100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 -100 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[-100,-92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 -100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 -100 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[-100,-92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 -100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 -100 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[-100,-92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 -100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 -100 180000 3200 100000 8 8"

# trueCount=0 lat=[-4,4) lon=[-92,-84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 -92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 -92 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[-92,-84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 -92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 -92 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[-92,-84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 -92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 -92 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[-92,-84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 -92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 -92 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[-92,-84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 -92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 -92 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[-84,-76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 -84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 -84 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[-84,-76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 -84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 -84 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[-84,-76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 -84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 -84 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[-76,-68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 -76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 -76 180000 3200 100000 8 8"

# trueCount=0 lat=[12,20) lon=[-68,-60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 -68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 -68 180000 3200 100000 8 8"

# trueCount=0 lat=[12,20) lon=[-60,-52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 -60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 -60 180000 3200 100000 8 8"

# trueCount=0 lat=[4,12) lon=[-60,-52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 -60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 -60 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[-60,-52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 -60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 -60 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[-60,-52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 -60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 -60 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[-60,-52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 -60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 -60 180000 3200 100000 8 8"

# trueCount=0 lat=[20,28) lon=[-52,-44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 -52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 -52 180000 3200 100000 8 8"

# trueCount=0 lat=[12,20) lon=[-52,-44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 -52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 -52 180000 3200 100000 8 8"

# trueCount=0 lat=[4,12) lon=[-52,-44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 -52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 -52 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[-52,-44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 -52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 -52 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[-52,-44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 -52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 -52 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[-52,-44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 -52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 -52 180000 3200 100000 8 8"

# trueCount=0 lat=[36,44) lon=[-44,-36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 -44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 -44 180000 3200 100000 8 8"

# trueCount=0 lat=[20,28) lon=[-44,-36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 -44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 -44 180000 3200 100000 8 8"

# trueCount=0 lat=[12,20) lon=[-44,-36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 -44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 -44 180000 3200 100000 8 8"

# trueCount=0 lat=[4,12) lon=[-44,-36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 -44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 -44 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[-44,-36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 -44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 -44 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[-44,-36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 -44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 -44 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[-44,-36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 -44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 -44 180000 3200 100000 8 8"

# trueCount=0 lat=[20,28) lon=[-36,-28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 -36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 -36 180000 3200 100000 8 8"

# trueCount=0 lat=[12,20) lon=[-36,-28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 -36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 -36 180000 3200 100000 8 8"

# trueCount=0 lat=[4,12) lon=[-36,-28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 -36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 -36 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[-36,-28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 -36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 -36 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[-36,-28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 -36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 -36 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[-36,-28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 -36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 -36 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[-36,-28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 -36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 -36 180000 3200 100000 8 8"

# trueCount=0 lat=[20,28) lon=[-28,-20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 -28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 -28 180000 3200 100000 8 8"

# trueCount=0 lat=[12,20) lon=[-28,-20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 -28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 -28 180000 3200 100000 8 8"

# trueCount=0 lat=[4,12) lon=[-28,-20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 -28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 -28 180000 3200 100000 8 8"

# trueCount=0 lat=[-4,4) lon=[-28,-20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 -28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 -28 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[-28,-20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 -28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 -28 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[-28,-20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 -28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 -28 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[-28,-20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 -28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 -28 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[-28,-20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 -28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 -28 180000 3200 100000 8 8"

# trueCount=0 lat=[20,28) lon=[-20,-12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 -20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 -20 180000 3200 100000 8 8"

# trueCount=0 lat=[-4,4) lon=[-20,-12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 -20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 -20 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[-20,-12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 -20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 -20 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[-20,-12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 -20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 -20 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[-20,-12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 -20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 -20 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[-20,-12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 -20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 -20 180000 3200 100000 8 8"

# trueCount=0 lat=[36,44) lon=[-12,-4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 -12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 -12 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[-12,-4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 -12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 -12 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[-12,-4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 -12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 -12 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[-12,-4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 -12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 -12 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[-12,-4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 -12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 -12 180000 3200 100000 8 8"

# trueCount=0 lat=[36,44) lon=[-4,4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 -4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 -4 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[-4,4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 -4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 -4 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[-4,4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 -4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 -4 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[-4,4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 -4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 -4 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[-4,4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 -4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 -4 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[-4,4)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 -4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 -4 180000 3200 100000 8 8"

# trueCount=0 lat=[36,44) lon=[4,12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 4 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[4,12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 4 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[4,12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 4 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[4,12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 4 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[4,12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 4 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[4,12)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 4 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 4 180000 3200 100000 8 8"

# trueCount=0 lat=[36,44) lon=[12,20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 12 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[12,20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 12 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[12,20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 12 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[12,20)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 12 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 12 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[20,28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 20 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[20,28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 20 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[20,28)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 20 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 20 180000 3200 100000 8 8"

# trueCount=0 lat=[36,44) lon=[28,36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 28 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[28,36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 28 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[28,36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 28 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[28,36)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 28 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 28 180000 3200 100000 8 8"

# trueCount=0 lat=[36,44) lon=[36,44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 36 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[36,44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 36 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[36,44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 36 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[36,44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 36 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[36,44)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 36 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 36 180000 3200 100000 8 8"

# trueCount=0 lat=[36,44) lon=[44,52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 44 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[44,52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 44 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[44,52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 44 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[44,52)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 44 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 44 180000 3200 100000 8 8"

# trueCount=0 lat=[-4,4) lon=[52,60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 52 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[52,60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 52 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[52,60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 52 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[52,60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 52 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[52,60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 52 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[52,60)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 52 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 52 180000 3200 100000 8 8"

# trueCount=0 lat=[36,44) lon=[60,68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 60 180000 3200 100000 8 8"

# trueCount=0 lat=[4,12) lon=[60,68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 60 180000 3200 100000 8 8"

# trueCount=0 lat=[-4,4) lon=[60,68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 60 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[60,68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 60 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[60,68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 60 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[60,68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 60 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[60,68)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 60 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 60 180000 3200 100000 8 8"

# trueCount=0 lat=[-4,4) lon=[68,76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 68 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[68,76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 68 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[68,76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 68 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[68,76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 68 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[68,76)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 68 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 68 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[76,84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 76 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[76,84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 76 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[76,84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 76 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[76,84)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 76 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 76 180000 3200 100000 8 8"

# trueCount=0 lat=[-4,4) lon=[84,92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 84 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[84,92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 84 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[84,92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 84 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[84,92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 84 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[84,92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 84 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[84,92)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 84 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 84 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[92,100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 92 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[92,100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 92 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[92,100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 92 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[92,100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 92 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[92,100)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 92 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 92 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[100,108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 100 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[100,108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 100 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[100,108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 100 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[100,108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 100 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[100,108)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 100 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 100 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[108,116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 108 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[108,116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 108 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[108,116)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 108 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 108 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[116,124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 116 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[116,124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 116 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[116,124)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 116 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 116 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[124,132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 124 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[124,132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 124 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[124,132)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 124 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 124 180000 3200 100000 8 8"

# trueCount=0 lat=[4,12) lon=[132,140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 132 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[132,140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 132 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[132,140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 132 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[132,140)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 132 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 132 180000 3200 100000 8 8"

# trueCount=0 lat=[4,12) lon=[140,148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 140 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[140,148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 140 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[140,148)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 140 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 140 180000 3200 100000 8 8"

# trueCount=0 lat=[36,44) lon=[148,156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 148 180000 3200 100000 8 8"

# trueCount=0 lat=[12,20) lon=[148,156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 148 180000 3200 100000 8 8"

# trueCount=0 lat=[4,12) lon=[148,156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 148 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[148,156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 148 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[148,156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 148 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[148,156)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 148 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 148 180000 3200 100000 8 8"

# trueCount=0 lat=[36,44) lon=[156,164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 156 180000 3200 100000 8 8"

# trueCount=0 lat=[12,20) lon=[156,164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 156 180000 3200 100000 8 8"

# trueCount=0 lat=[4,12) lon=[156,164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 156 180000 3200 100000 8 8"

# trueCount=0 lat=[-4,4) lon=[156,164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 156 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[156,164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 156 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[156,164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 156 180000 3200 100000 8 8"

# trueCount=0 lat=[-28,-20) lon=[156,164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -28 156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -28 156 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[156,164)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 156 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 156 180000 3200 100000 8 8"

# trueCount=0 lat=[36,44) lon=[164,172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 164 180000 3200 100000 8 8"

# trueCount=0 lat=[20,28) lon=[164,172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 164 180000 3200 100000 8 8"

# trueCount=0 lat=[12,20) lon=[164,172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 164 180000 3200 100000 8 8"

# trueCount=0 lat=[4,12) lon=[164,172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 164 180000 3200 100000 8 8"

# trueCount=0 lat=[-4,4) lon=[164,172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 164 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[164,172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 164 180000 3200 100000 8 8"

# trueCount=0 lat=[-20,-12) lon=[164,172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -20 164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -20 164 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[164,172)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 164 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 164 180000 3200 100000 8 8"

# trueCount=0 lat=[36,44) lon=[172,180)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 36 172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 36 172 180000 3200 100000 8 8"

# trueCount=0 lat=[20,28) lon=[172,180)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 20 172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 20 172 180000 3200 100000 8 8"

# trueCount=0 lat=[12,20) lon=[172,180)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 12 172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 12 172 180000 3200 100000 8 8"

# trueCount=0 lat=[4,12) lon=[172,180)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag 4 172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag 4 172 180000 3200 100000 8 8"

# trueCount=0 lat=[-4,4) lon=[172,180)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -4 172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -4 172 180000 3200 100000 8 8"

# trueCount=0 lat=[-12,-4) lon=[172,180)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -12 172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -12 172 180000 3200 100000 8 8"

# trueCount=0 lat=[-36,-28) lon=[172,180)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -36 172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -36 172 180000 3200 100000 8 8"

# trueCount=0 lat=[-44,-36) lon=[172,180)
cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

cd "$PATH_PLANNER_DIR"
./run.sh zigzag -44 172 180000 3200 100000 8 8

cd "$PROJECT_DIR"
./scripts/runFullProcess.sh \
    --route-command "./run.sh zigzag -44 172 180000 3200 100000 8 8"
