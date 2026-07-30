#!/usr/bin/env bash

# Bash reads a script incrementally.  A long capture can therefore be corrupted
# if this file is saved while the process is waiting for an external module:
# the running shell may continue with a mixture of the old and new contents.
# Execute each invocation from a private snapshot so its source cannot change
# underneath it.  The snapshot unlinks itself once Bash has opened it.
if [[ -z "${RUN_FULL_PROCESS_SNAPSHOT_ACTIVE:-}" ]]; then
    run_full_process_source="${BASH_SOURCE[0]}"
    run_full_process_dir="$(
        cd "$(dirname "$run_full_process_source")" && pwd
    )"
    run_full_process_snapshot="$(
        mktemp /tmp/google-earth-runFullProcess.snapshot.XXXXXX
    )"
    cp -- "$run_full_process_source" "$run_full_process_snapshot"
    RUN_FULL_PROCESS_SNAPSHOT_ACTIVE=1 \
    RUN_FULL_PROCESS_SCRIPT_DIR="$run_full_process_dir" \
    RUN_FULL_PROCESS_SNAPSHOT_PATH="$run_full_process_snapshot" \
        exec bash "$run_full_process_snapshot" "$@"
fi

set -Eeuo pipefail
IFS=$'\n\t'

readonly SCRIPT_DIR="$RUN_FULL_PROCESS_SCRIPT_DIR"
readonly SCRIPT_SNAPSHOT_PATH="$RUN_FULL_PROCESS_SNAPSHOT_PATH"
case "$SCRIPT_SNAPSHOT_PATH" in
    /tmp/google-earth-runFullProcess.snapshot.[A-Za-z0-9]*)
        find "$SCRIPT_SNAPSHOT_PATH" -maxdepth 0 -type f -delete
        ;;
    *)
        printf 'Refusing to remove unexpected script snapshot: %s\n' \
            "$SCRIPT_SNAPSHOT_PATH" >&2
        exit 2
        ;;
esac
unset RUN_FULL_PROCESS_SNAPSHOT_ACTIVE
unset RUN_FULL_PROCESS_SCRIPT_DIR
unset RUN_FULL_PROCESS_SNAPSHOT_PATH

readonly CAPTURE_ROOT="/media/ramdisk/output"
readonly TRACE_DUMP_DIR="/media/ramdisk/output"
readonly DEFAULT_DESTINATION="/samples/datasets/googleEarth/toplevel"
readonly TRACE_DIRECTORY="/opt/google/earth/pro"
readonly TRACE_PATTERN="googleearth-bin*trace"
readonly SESSION_LOG="/tmp/googleEarthSession.log"

destination="$DEFAULT_DESTINATION"
reuse_capture=0
dry_run=0
keep_work=0
route_command=""
run_dir=""
trace_dump_dir="$TRACE_DUMP_DIR"
session_started_epoch="$(date +%s)"
original_args=("$@")

exec > >(tee -a "$SESSION_LOG") 2>&1
printf '\n===== googleEarth full-process session started %s pid=%d =====\n' \
    "$(date --iso-8601=seconds)" "$$"
printf '[runFullProcess] Command: %q' "$0"
printf ' %q' "${original_args[@]}"
printf '\n[runFullProcess] Working directory: %s\n' "$PWD"
printf '[runFullProcess] Master log: %s\n' "$SESSION_LOG"

usage() {
    cat <<'EOF'
Usage: ./runFullProcess.sh [options]

Runs modules 14, 21, 22, 23, 31, 32 and 42 for one capture iteration,
including an offline `apitrace dump` between modules 14 and 21.
Route generation by module 11 must already have been completed.

Options:
  --destination PATH  Consolidated pyramidal image (default:
                      /samples/datasets/googleEarth/toplevel)
  --reuse-capture     Do not clear/capture; reprocess /media/ramdisk/output
  --dry-run           Build and validate the delta, but do not merge it
  --keep-work         Preserve staging when the script exits
  --route-command CMD Record the module-11 command that prepared the current KML
  -h, --help          Show this help
EOF
}

log() {
    printf '[runFullProcess][%s] %s\n' "$(date --iso-8601=seconds)" "$*"
}

die() {
    printf '[runFullProcess][%s][ERROR] %s\n' "$(date --iso-8601=seconds)" "$*" >&2
    exit 1
}

on_early_exit() {
    local status=$?
    local elapsed
    elapsed=$(($(date +%s) - session_started_epoch))
    log "Session finished during argument parsing status=$status elapsed_seconds=$elapsed"
    printf '===== googleEarth full-process session ended %s pid=%d status=%d =====\n' \
        "$(date --iso-8601=seconds)" "$$" "$status"
}
trap on_early_exit EXIT

while (($# > 0)); do
    case "$1" in
        --destination)
            (($# >= 2)) || die "--destination requires a path."
            destination="$2"
            shift 2
            ;;
        --reuse-capture)
            reuse_capture=1
            shift
            ;;
        --dry-run)
            dry_run=1
            shift
            ;;
        --keep-work)
            keep_work=1
            shift
            ;;
        --route-command)
            (($# >= 2)) || die "--route-command requires a description."
            route_command="$2"
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

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "Required command is not available: $1"
}

canonical_directory() {
    local path="$1"
    [[ -d "$path" ]] || die "Required directory does not exist: $path"
    realpath -e -- "$path"
}

safe_clear_capture_root() {
    local resolved
    resolved="$(canonical_directory "$CAPTURE_ROOT")"
    [[ "$resolved" == "$CAPTURE_ROOT" ]] ||
        die "Capture root resolved to unexpected path '$resolved'; expected '$CAPTURE_ROOT'."
    [[ -w "$resolved" ]] || die "Capture root is not writable: $resolved"

    log "Clearing children of verified capture root $resolved"
    find "$resolved" -xdev -mindepth 1 -depth -delete
    if [[ -n "$(find "$resolved" -xdev -mindepth 1 -print -quit)" ]]; then
        die "Capture root cleanup left files behind: $resolved"
    fi
    sync "$resolved"
}

safe_remove_run_dir() {
    local resolved
    [[ -n "$run_dir" && -d "$run_dir" ]] || return 0
    resolved="$(realpath -e -- "$run_dir")"
    case "$resolved" in
        /tmp/google-earth-full-process.[A-Za-z0-9]*) ;;
        *)
            log "Refusing to delete unexpected staging path: $resolved"
            return 1
            ;;
    esac
    find "$resolved" -xdev -mindepth 1 -depth -delete
    rmdir -- "$resolved"
}

on_exit() {
    local status=$?
    local elapsed
    trap - EXIT
    if [[ -n "$run_dir" ]] && ((keep_work == 0)); then
        run_timed_step temporary_staging_cleanup safe_remove_run_dir || true
    elif [[ -n "$run_dir" ]]; then
        log "Staging preserved at $run_dir"
    fi
    elapsed=$(($(date +%s) - session_started_epoch))
    log "Session finished status=$status elapsed_seconds=$elapsed"
    printf '===== googleEarth full-process session ended %s pid=%d status=%d =====\n' \
        "$(date --iso-8601=seconds)" "$$" "$status"
    exit "$status"
}
trap on_exit EXIT

run_logged() {
    local name="$1"
    local started started_at finished_at status
    shift
    started="$(date +%s)"
    started_at="$(date --iso-8601=seconds)"
    log "Starting $name started_at=$started_at"
    if "$@" 2>&1 | tee "$run_dir/logs/$name.log"; then
        status=0
    else
        status=$?
    fi
    finished_at="$(date --iso-8601=seconds)"
    log "Finished $name finished_at=$finished_at status=$status elapsed_seconds=$(($(date +%s) - started))"
    return "$status"
}

run_logged_in_directory() {
    local name="$1"
    local directory="$2"
    local started started_at finished_at status
    shift 2
    started="$(date +%s)"
    started_at="$(date --iso-8601=seconds)"
    log "Starting $name started_at=$started_at working_directory=$directory"
    if (cd "$directory" && "$@") 2>&1 | tee "$run_dir/logs/$name.log"; then
        status=0
    else
        status=$?
    fi
    finished_at="$(date --iso-8601=seconds)"
    log "Finished $name finished_at=$finished_at status=$status elapsed_seconds=$(($(date +%s) - started))"
    return "$status"
}

run_timed_step() {
    local name="$1"
    local started started_at finished_at status
    shift
    started="$(date +%s)"
    started_at="$(date --iso-8601=seconds)"
    log "Starting $name started_at=$started_at"
    if "$@"; then
        status=0
    else
        status=$?
    fi
    finished_at="$(date --iso-8601=seconds)"
    log "Finished $name finished_at=$finished_at status=$status elapsed_seconds=$(($(date +%s) - started))"
    return "$status"
}

safe_delete_staging_path() {
    local target="$1"
    local staging_resolved target_resolved
    [[ -e "$target" ]] || return 0
    staging_resolved="$(realpath -e -- "$run_dir")"
    target_resolved="$(realpath -e -- "$target")"
    [[ "$target_resolved" == "$staging_resolved/"* ]] ||
        die "Refusing to delete path outside iteration staging: $target_resolved"
    find "$target_resolved" -xdev -depth -delete
}

safe_delete_trace_dump_path() {
    local target="$1"
    local dump_dir_resolved target_resolved
    [[ -e "$target" ]] || return 0
    dump_dir_resolved="$(realpath -e -- "$trace_dump_dir")"
    target_resolved="$(realpath -e -- "$target")"
    [[ "$target_resolved" == "$dump_dir_resolved/"* ]] ||
        die "Refusing to delete path outside trace dump staging: $target_resolved"
    find "$target_resolved" -xdev -depth -delete
}

available_bytes() {
    df -B1 --output=avail "$1" | awk 'NR == 2 { print $1 }'
}

select_completed_trace() {
    local -a traces=()
    mapfile -d '' traces < <(
        find "$TRACE_DIRECTORY" -maxdepth 1 -type f -name "$TRACE_PATTERN" -print0 | sort -z
    )
    ((${#traces[@]} == 1)) ||
        die "Expected exactly one completed $TRACE_DIRECTORY/$TRACE_PATTERN trace; found ${#traces[@]}."
    [[ -r "${traces[0]}" && -s "${traces[0]}" ]] ||
        die "Trace is empty or unreadable: ${traces[0]}"

    sync "${traces[0]}"
    printf '%s\n' "${traces[0]}"
}

dump_completed_trace() {
    local trace_file="$1"
    local partial="$trace_dump_dir/bigtrace.log.partial"
    local dump_file="$trace_dump_dir/bigtrace.log"
    local free_bytes minimum_free

    free_bytes="$(available_bytes "$trace_dump_dir")"
    minimum_free=$((5 * 1024 * 1024 * 1024))
    ((free_bytes >= minimum_free)) ||
        die "Insufficient staging space for trace dump/split: need at least 5 GiB ($minimum_free bytes) free, have $free_bytes bytes."

    local started started_at finished_at status
    started="$(date +%s)"
    started_at="$(date --iso-8601=seconds)"
    log "Starting apitrace_dump started_at=$started_at trace=$trace_file"
    if apitrace dump "$trace_file" > "$partial" 2> >(tee "$run_dir/logs/apitrace_dump.log" >&2); then
        status=0
    else
        status=$?
    fi
    finished_at="$(date --iso-8601=seconds)"
    log "Finished apitrace_dump finished_at=$finished_at status=$status elapsed_seconds=$(($(date +%s) - started))"
    if ((status != 0)); then
        safe_delete_trace_dump_path "$partial"
        die "apitrace dump failed; its partial output was discarded."
    fi
    [[ -s "$partial" ]] || {
        safe_delete_trace_dump_path "$partial"
        die "apitrace dump produced an empty file."
    }
    grep -q 'glXSwapBuffers' "$partial" || {
        safe_delete_trace_dump_path "$partial"
        die "Trace dump contains no glXSwapBuffers frame boundary."
    }
    mv -- "$partial" "$dump_file"
}

count_files() {
    local root="$1"
    local name="$2"
    find "$root" -type f -name "$name" -print | awk 'END { print NR + 0 }'
}

validate_dump_analyzer_outputs() {
    local frame_count top_level strip_count appearance_count
    frame_count="$(count_files "$CAPTURE_ROOT" frame.json)"
    ((frame_count > 0)) || die "Module 22 produced no frame.json files."
    top_level="$CAPTURE_ROOT/topLevelTiles.json"
    jq -e . "$top_level" >/dev/null || die "Missing or invalid topLevelTiles.json."
    strip_count="$(jq -r '.byStripId | length' "$top_level")"
    appearance_count="$(jq -r '[.byStripId[].appearances[]?] | length' "$top_level")"
    ((strip_count == 320)) || die "Expected 320 top-level strips; found $strip_count."
    ((appearance_count > 0)) || die "Top-level catalogue contains no appearances."
}

validate_export_log_and_pyramid() {
    local export_log="$1"
    local pyramid="$run_dir/matrix/pyramidalImage"
    local png_count file filename quadkey digits expected digit

    ! grep -q 'Export failed' "$export_log" || die "Module 32 reported an export failure."
    ! grep -Eq '\([1-9][0-9]* failed\)' "$export_log" || die "Module 32 reported failed PNG writes."
    ! grep -q 'discarded due to ambiguous' "$export_log" || die "Module 32 discarded ambiguous tile placements."
    ! grep -q 'layer(s) with NO placed tiles' "$export_log" || die "Module 32 left complete matrix layers unplaced."
    grep -Eq 'Export complete: [1-9][0-9]* tiles processed' "$export_log" ||
        die "Module 32 did not report a non-empty completed export."
    awk '
        /^  matrix_[0-9]+: [0-9]+\/[0-9]+ tiles placed/ {
            split($2, counts, "/");
            if ((counts[1] + 0) != (counts[2] + 0)) bad = 1;
            seen++;
        }
        END { if (seen == 0 || bad) exit 1; }
    ' "$export_log" || die "Not every imported matrix tile received an absolute position."

    [[ -r "$pyramid/0.png" ]] || die "Delta pyramid has no readable root 0.png."
    png_count="$(count_files "$pyramid" '*.png')"
    ((png_count > 0)) || die "Delta pyramid contains no PNG tiles."
    while IFS= read -r -d '' file; do
        filename="$(basename "$file")"
        quadkey="${filename%.png}"
        [[ "$quadkey" =~ ^0[0-3]*$ ]] || die "Invalid quadkey filename in delta pyramid: $file"
        if [[ "$quadkey" == "0" ]]; then
            expected="$pyramid/0.png"
        else
            expected="$pyramid"
            digits="${quadkey:1}"
            for ((digit = 0; digit < ${#digits}; digit++)); do
                expected="$expected/${digits:digit:1}"
            done
            expected="$expected/$quadkey.png"
        fi
        [[ "$file" == "$expected" ]] ||
            die "Invalid per-digit quadtree layout: found $file, expected $expected"
    done < <(find "$pyramid" -type f -name '*.png' -print0)
}

reset_matrix_attempt_staging() {
    safe_delete_staging_path "$run_dir/matrix"
    mkdir "$run_dir/matrix"
}

module_42_reported_conflicts() {
    local log_file="$1"
    grep -q 'Merge blocked: red\.' "$log_file" &&
        grep -q 'Conflict details:' "$log_file"
}

validate_merge_completed() {
    local merge_log="$1"
    grep -q 'Merge completed\.' "$merge_log" ||
        die "Module 42 did not report a completed merge."
}

preflight_started="$(date +%s)"
log "Starting preflight started_at=$(date --iso-8601=seconds)"
for command_name in java cmake apitrace realpath find jq awk sed sort grep tee flock sync rmdir cksum compare identify mktemp mkdir basename dirname stat df sleep cp mv; do
    require_command "$command_name"
done
[[ -x "$SCRIPT_DIR/gradlew" ]] || die "Gradle wrapper is not executable: $SCRIPT_DIR/gradlew"

destination="$(canonical_directory "$destination")"
[[ -r "$destination/0.png" ]] || die "Destination is not a pyramidal image (missing 0.png): $destination"
[[ -w "$destination" ]] || die "Destination is not writable: $destination"

capture_resolved="$(canonical_directory "$CAPTURE_ROOT")"
[[ "$capture_resolved" == "$CAPTURE_ROOT" ]] ||
    die "Capture root resolved to '$capture_resolved', not the required '$CAPTURE_ROOT'."
[[ -w "$capture_resolved" ]] || die "Capture root is not writable: $capture_resolved"
trace_dump_dir_resolved="$(canonical_directory "$TRACE_DUMP_DIR")"
[[ "$trace_dump_dir_resolved" == "$TRACE_DUMP_DIR" ]] ||
    die "Trace dump directory resolved to '$trace_dump_dir_resolved', not the required '$TRACE_DUMP_DIR'."
[[ -w "$trace_dump_dir_resolved" ]] || die "Trace dump directory is not writable: $trace_dump_dir_resolved"
canonical_directory "$TRACE_DIRECTORY" >/dev/null

if ((reuse_capture == 0)); then
    [[ -n "${DISPLAY:-}" ]] || die "DISPLAY is required for the interactive capture."
    [[ -n "${DBUS_SESSION_BUS_ADDRESS:-}" ]] || die "DBUS_SESSION_BUS_ADDRESS is required for AT-SPI."
    [[ -x "$SCRIPT_DIR/12_fileSystemChangesDetector/build/fileSystemChangesDetector" ]] ||
        die "Build module 12 before capture."
    [[ -x "$SCRIPT_DIR/14_sessionController/run.sh" ]] || die "Module 14 launcher is not executable."
fi

run_dir="$(mktemp -d /tmp/google-earth-full-process.XXXXXX)"
mkdir -p "$run_dir/logs" "$run_dir/matrix"
log "Staging directory: $run_dir"
log "Trace dump directory: $trace_dump_dir"
if [[ -n "$route_command" ]]; then
    log "Prepared route command: $route_command"
else
    log "Prepared route command: unspecified"
fi

lock_key="$(printf '%s' "$destination" | cksum | awk '{print $1}')"
exec 9>"/tmp/google-earth-full-process-${lock_key}.lock"
flock -n 9 || die "Another automated iteration is using destination $destination"
log "Finished preflight finished_at=$(date --iso-8601=seconds) status=0 elapsed_seconds=$(($(date +%s) - preflight_started))"

run_logged 21_traceLogSplitter_configure \
    cmake -S "$SCRIPT_DIR/21_traceLogSplitter" -B "$SCRIPT_DIR/21_traceLogSplitter/build"
run_logged 21_traceLogSplitter_build \
    cmake --build "$SCRIPT_DIR/21_traceLogSplitter/build" --parallel
splitter="$SCRIPT_DIR/21_traceLogSplitter/build/traceLogSplitter"
[[ -x "$splitter" ]] || die "Module 21 build did not produce an executable splitter: $splitter"

if ((reuse_capture == 0)); then
    run_timed_step capture_root_cleanup safe_clear_capture_root
    run_logged 14_sessionController "$SCRIPT_DIR/14_sessionController/run.sh"
else
    log "Reusing the existing capture; cleanup and module 14 are skipped."
fi

trace_selection_started="$(date +%s)"
log "Starting completed_trace_selection started_at=$(date --iso-8601=seconds)"
if trace_file="$(select_completed_trace)"; then
    trace_selection_status=0
else
    trace_selection_status=$?
fi
log "Finished completed_trace_selection finished_at=$(date --iso-8601=seconds) status=$trace_selection_status elapsed_seconds=$(($(date +%s) - trace_selection_started)) trace=${trace_file:-unavailable}"
((trace_selection_status == 0)) || exit "$trace_selection_status"
dump_completed_trace "$trace_file"
dump_file="$trace_dump_dir/bigtrace.log"
run_logged_in_directory 21_traceLogSplitter \
    "$(dirname "$CAPTURE_ROOT")" "$splitter" "$dump_file"

run_logged 22_dumpAnalyzer \
    "$SCRIPT_DIR/gradlew" :22_dumpAnalyzer:run \
    "--args=--offline --start-frame 3 --output $run_dir/dumpAnalyzer.png"

run_timed_step 22_dumpAnalyzer_validation validate_dump_analyzer_outputs
run_timed_step trace_dump_cleanup safe_delete_trace_dump_path "$dump_file"

run_logged 23_frameTextureNormalizer \
    "$SCRIPT_DIR/23_frameTextureNormalizer/run.sh"

matrix_attempt=1
while true; do
    attempt_suffix="$(printf 'attempt_%02d_interactive' "$matrix_attempt")"
    matrix_attempt_started="$(date +%s)"
    log "Starting matrix/delta_attempt_$matrix_attempt started_at=$(date --iso-8601=seconds) with interactive module 31"
    run_timed_step "matrix_staging_reset_${attempt_suffix}" reset_matrix_attempt_staging

    run_logged "31_matrixMerger_${attempt_suffix}" \
        "$SCRIPT_DIR/31_matrixMerger/run.sh" "$run_dir/matrix"
    log "Interactive module 31 closed on attempt $matrix_attempt; continuing without validating its generated matrix set."

    module_32_log="$run_dir/logs/32_pyramidalImageExporter_${attempt_suffix}.log"
    run_logged "32_pyramidalImageExporter_${attempt_suffix}" \
        "$SCRIPT_DIR/32_pyramidalImageExporter/runOffline.sh" "$run_dir/matrix"
    run_timed_step "32_pyramidalImageExporter_validation_${attempt_suffix}" \
        validate_export_log_and_pyramid "$module_32_log"

    delta="$run_dir/matrix/pyramidalImage"
    module_42_log="$run_dir/logs/42_pyramidalImageMerger_dry_run_${attempt_suffix}.log"
    if run_logged "42_pyramidalImageMerger_dry_run_${attempt_suffix}" \
        "$SCRIPT_DIR/gradlew" :42_pyramidalImageMerger:run \
        "--args=--dry-run $destination $delta"; then
        log "Module 42 dry run accepted matrix/delta attempt $matrix_attempt."
        log "Finished matrix/delta_attempt_$matrix_attempt finished_at=$(date --iso-8601=seconds) status=0 result=accepted elapsed_seconds=$(($(date +%s) - matrix_attempt_started))"
        break
    else
        dry_run_status=$?
    fi
    if ! module_42_reported_conflicts "$module_42_log"; then
        die "Module 42 dry run failed for a reason other than content conflicts (status=$dry_run_status); interactive matrix retry is not applicable."
    fi

    log "Module 42 reported content conflicts. Reopening interactive module 31 so the operator can reduce the matrix set before rerunning modules 32 and 42."
    log "Finished matrix/delta_attempt_$matrix_attempt finished_at=$(date --iso-8601=seconds) status=$dry_run_status result=content_conflict elapsed_seconds=$(($(date +%s) - matrix_attempt_started))"
    matrix_attempt=$((matrix_attempt + 1))
done

if ((dry_run == 1)); then
    log "Dry run succeeded. Destination was not modified."
    exit 0
fi

run_logged 42_pyramidalImageMerger_commit \
    "$SCRIPT_DIR/gradlew" :42_pyramidalImageMerger:run \
    "--args=--offline $destination $delta"
run_timed_step 42_pyramidalImageMerger_commit_validation \
    validate_merge_completed "$run_dir/logs/42_pyramidalImageMerger_commit.log"

printf '\n'
log "ITERATION COMMITTED SUCCESSFULLY TO $destination"
printf '\n'
if ((keep_work == 0)); then
    log "Temporary staging will now be removed. Use --keep-work to retain it."
fi
