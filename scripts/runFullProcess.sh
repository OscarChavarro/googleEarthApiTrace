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

readonly SCRIPT_FILE_DIR="$RUN_FULL_PROCESS_SCRIPT_DIR"
readonly SCRIPT_DIR="$(
    cd "$SCRIPT_FILE_DIR/.." && pwd
)"
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

CAPTURE_ROOT="${PIPELINE_OUTPUT_DIRECTORY:-/media/ramdisk/output}"
LOGICAL_OUTPUT_ROOT="${PIPELINE_LOGICAL_OUTPUT_DIRECTORY:-$CAPTURE_ROOT}"
TRACE_DUMP_DIR="${PIPELINE_TRACE_DUMP_DIR:-$CAPTURE_ROOT}"
trace_dump_dir_explicit=0
if [[ -n "${PIPELINE_TRACE_DUMP_DIR:-}" ]]; then
    trace_dump_dir_explicit=1
fi
readonly DEFAULT_DESTINATION="/samples/datasets/googleEarth/toplevel"
readonly TRACE_DIRECTORY="/opt/google/earth/pro"
readonly TRACE_PATTERN="googleearth-bin*trace"
SESSION_LOG="${PIPELINE_SESSION_LOG:-/tmp/googleEarthSession.log}"
readonly ERRORS_LOG="$SCRIPT_DIR/errors.log"
MATRIX_DIR="${PIPELINE_MATRIX_DIR:-/tmp/matrix}"

destination="$DEFAULT_DESTINATION"
reuse_capture=0
dry_run=0
keep_work=0
route_command=""
run_dir=""
trace_dump_dir="$TRACE_DUMP_DIR"
session_started_epoch="$(date +%s)"
original_args=("$@")

for ((arg_index = 0; arg_index < ${#original_args[@]}; arg_index++)); do
    case "${original_args[arg_index]}" in
        --session-log)
            if ((arg_index + 1 < ${#original_args[@]})); then
                SESSION_LOG="${original_args[arg_index + 1]}"
            fi
            ;;
        --session-log=*)
            SESSION_LOG="${original_args[arg_index]#--session-log=}"
            ;;
    esac
done

exec > >(tee -a "$SESSION_LOG") 2>&1
printf '\n===== googleEarth full-process session started %s pid=%d =====\n' \
    "$(date --iso-8601=seconds)" "$$"
printf '[runFullProcess] Command: %q' "$0"
printf ' %q' "${original_args[@]}"
printf '\n[runFullProcess] Working directory: %s\n' "$PWD"
printf '[runFullProcess] Master log: %s\n' "$SESSION_LOG"

usage() {
    cat <<'EOF'
Usage: ./scripts/runFullProcess.sh [options]

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
  --capture-root PATH Read/write the capture dataset from PATH
                      (default: PIPELINE_OUTPUT_DIRECTORY or /media/ramdisk/output)
  --logical-output-root PATH
                      Prefix serialized by the tracer in manifest file= entries
                      (default: capture root)
  --trace-dump-dir PATH
                      Write bigtrace.log staging under PATH (default: capture root)
  --matrix-dir PATH   Matrix/delta staging directory (default: /tmp/matrix)
  --session-log PATH  Master session log path (default: /tmp/googleEarthSession.log)
  -h, --help          Show this help
EOF
}

log() {
    printf '[runFullProcess][%s] %s\n' "$(date --iso-8601=seconds)" "$*"
}

phase_log() {
    printf '[runFullProcess][%s][PHASE] Starting %s\n' "$(date --iso-8601=seconds)" "$*"
}

die() {
    printf '[runFullProcess][%s][ERROR] %s\n' "$(date --iso-8601=seconds)" "$*" >&2
    exit 1
}

extract_route_coordinates() {
    if [[ "$route_command" =~ zigzag[[:space:]]+(-?[0-9]+([.][0-9]+)?)[[:space:]]+(-?[0-9]+([.][0-9]+)?) ]]; then
        printf 'lat=%s lon=%s' "${BASH_REMATCH[1]}" "${BASH_REMATCH[3]}"
        return 0
    fi
    return 1
}

print_cycle_banner() {
    local title="$1"
    local coordinates="coordinates=unknown"
    local border
    if extracted_coordinates="$(extract_route_coordinates)"; then
        coordinates="$extracted_coordinates"
    fi
    border="$(printf '=%.0s' {1..70})"
    printf '\n%s\n' "$border"
    printf '[runFullProcess] %s %s\n' "$title" "$coordinates"
    printf '%s\n\n' "$border"
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
        --capture-root)
            (($# >= 2)) || die "--capture-root requires a path."
            CAPTURE_ROOT="$2"
            if [[ -z "${PIPELINE_LOGICAL_OUTPUT_DIRECTORY:-}" ]]; then
                LOGICAL_OUTPUT_ROOT="$CAPTURE_ROOT"
            fi
            if ((trace_dump_dir_explicit == 0)); then
                TRACE_DUMP_DIR="$CAPTURE_ROOT"
            fi
            shift 2
            ;;
        --logical-output-root)
            (($# >= 2)) || die "--logical-output-root requires a path."
            LOGICAL_OUTPUT_ROOT="$2"
            shift 2
            ;;
        --trace-dump-dir)
            (($# >= 2)) || die "--trace-dump-dir requires a path."
            TRACE_DUMP_DIR="$2"
            trace_dump_dir_explicit=1
            shift 2
            ;;
        --matrix-dir)
            (($# >= 2)) || die "--matrix-dir requires a path."
            MATRIX_DIR="$2"
            shift 2
            ;;
        --session-log)
            (($# >= 2)) || die "--session-log requires a path."
            SESSION_LOG="$2"
            shift 2
            ;;
        --session-log=*)
            SESSION_LOG="${1#--session-log=}"
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

print_cycle_banner "CYCLE START"
export PIPELINE_OUTPUT_DIRECTORY="$CAPTURE_ROOT"
export PIPELINE_LOGICAL_OUTPUT_DIRECTORY="$LOGICAL_OUTPUT_ROOT"
trace_dump_dir="$TRACE_DUMP_DIR"

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
    print_cycle_banner "CYCLE END (status=$status)"
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
    if "$@" > "$run_dir/logs/$name.log" 2>&1; then
        status=0
    else
        status=$?
    fi
    cat "$run_dir/logs/$name.log" >> "$SESSION_LOG" || true
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
    if (cd "$directory" && "$@") > "$run_dir/logs/$name.log" 2>&1; then
        status=0
    else
        status=$?
    fi
    cat "$run_dir/logs/$name.log" >> "$SESSION_LOG" || true
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

safe_delete_capture_path() {
    local target="$1"
    local capture_dir_resolved target_resolved
    [[ -e "$target" ]] || return 0
    capture_dir_resolved="$(realpath -e -- "$CAPTURE_ROOT")"
    target_resolved="$(realpath -e -- "$target")"
    [[ "$target_resolved" == "$capture_dir_resolved/"* ]] ||
        die "Refusing to delete path outside capture root: $target_resolved"
    find "$target_resolved" -xdev -depth -delete
}

available_bytes() {
    df -B1 --output=avail "$1" | awk 'NR == 2 { print $1 }'
}

select_completed_trace() {
    local private_trace="$CAPTURE_ROOT/_pipeline/capture.trace"
    if [[ -r "$private_trace" && -s "$private_trace" ]]; then
        printf '%s\n' "$private_trace"
        return 0
    fi

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

publish_private_trace() {
    local trace_file pipeline_dir partial private_trace sha_file source_sha private_sha
    trace_file="$(select_completed_trace)"
    [[ "$trace_file" != "$CAPTURE_ROOT/_pipeline/capture.trace" ]] || return 0

    pipeline_dir="$CAPTURE_ROOT/_pipeline"
    partial="$pipeline_dir/capture.trace.partial"
    private_trace="$pipeline_dir/capture.trace"
    sha_file="$pipeline_dir/capture.trace.sha256"

    mkdir -p "$pipeline_dir"
    cp -- "$trace_file" "$partial"
    sync "$partial"
    mv -- "$partial" "$private_trace"
    sync "$private_trace"

    source_sha="$(sha256sum "$trace_file" | awk '{print $1}')"
    private_sha="$(sha256sum "$private_trace" | awk '{print $1}')"
    [[ "$source_sha" == "$private_sha" ]] ||
        die "Private trace SHA-256 mismatch after copy from $trace_file to $private_trace."
    printf '%s  %s\n' "$private_sha" "$private_trace" > "$sha_file"
    rm -f -- "$trace_file"
}

safe_delete_private_trace() {
    local trace_file="$1"
    [[ "$trace_file" == "$CAPTURE_ROOT/_pipeline/capture.trace" ]] || return 0
    safe_delete_capture_path "$trace_file"
    safe_delete_capture_path "$CAPTURE_ROOT/_pipeline/capture.trace.sha256"
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
    if apitrace dump "$trace_file" > "$partial" 2> "$run_dir/logs/apitrace_dump.log"; then
        status=0
    else
        status=$?
    fi
    cat "$run_dir/logs/apitrace_dump.log" >> "$SESSION_LOG" || true
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
    local pyramid="$MATRIX_DIR/pyramidalImage"
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
    local resolved

    [[ ! -L "$MATRIX_DIR" ]] ||
        die "Refusing to use matrix staging through a symbolic link: $MATRIX_DIR"
    if [[ -e "$MATRIX_DIR" ]]; then
        [[ -d "$MATRIX_DIR" ]] ||
            die "Matrix staging path exists but is not a directory: $MATRIX_DIR"
    else
        mkdir -- "$MATRIX_DIR"
    fi

    resolved="$(realpath -e -- "$MATRIX_DIR")"
    [[ "$resolved" == "$MATRIX_DIR" ]] ||
        die "Matrix staging resolved to unexpected path '$resolved'; expected '$MATRIX_DIR'."
    [[ -w "$resolved" ]] || die "Matrix staging is not writable: $resolved"

    log "Clearing verified matrix staging directory $resolved"
    find "$resolved" -xdev -mindepth 1 -depth -delete
    if [[ -n "$(find "$resolved" -xdev -mindepth 1 -print -quit)" ]]; then
        die "Matrix staging cleanup left files behind: $resolved"
    fi
}

module_42_reported_conflicts() {
    local log_file="$1"
    grep -q 'Merge blocked: red\.' "$log_file" &&
        grep -q 'Conflict details:' "$log_file"
}

validate_merge_completed() {
    local merge_log="$1"
    grep -Eq 'Merge completed( as no-op)?\.' "$merge_log" ||
        die "Module 42 did not report a completed merge."
}

append_merge_error() {
    local reason="$1"
    local coordinates="coordinates=unknown"
    if extracted_coordinates="$(extract_route_coordinates)"; then
        coordinates="$extracted_coordinates"
    fi
    printf '%s | %s | route=%s | reason=%s\n' \
        "$(date --iso-8601=seconds)" \
        "$coordinates" \
        "${route_command:-unspecified}" \
        "$reason" >> "$ERRORS_LOG"
    log "Recorded failed merge in $ERRORS_LOG ($coordinates, reason=$reason)."
}

preflight_started="$(date +%s)"
log "Starting preflight started_at=$(date --iso-8601=seconds)"
for command_name in java cmake apitrace realpath find jq awk sed sort grep tee flock sync rmdir cksum sha256sum compare identify mktemp mkdir basename dirname stat df sleep cp mv; do
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
mkdir -p "$run_dir/logs"
log "Staging directory: $run_dir"
log "Matrix staging directory: $MATRIX_DIR"
log "Trace dump directory: $trace_dump_dir"
if [[ -n "$route_command" ]]; then
    log "Prepared route command: $route_command"
else
    log "Prepared route command: unspecified"
fi

lock_key="$(printf '%s' "$destination" | cksum | awk '{print $1}')"
exec 9>"/tmp/google-earth-full-process-${lock_key}.lock"
flock -n 9 || die "Another automated iteration is using destination $destination"
exec 7>"/tmp/google-earth-full-process-matrix.lock"
flock -n 7 || die "Another automated iteration is using matrix staging $MATRIX_DIR"
log "Finished preflight finished_at=$(date --iso-8601=seconds) status=0 elapsed_seconds=$(($(date +%s) - preflight_started))"

phase_log "21 traceLogSplitter configure"
run_logged 21_traceLogSplitter_configure \
    cmake -S "$SCRIPT_DIR/21_traceLogSplitter" -B "$SCRIPT_DIR/21_traceLogSplitter/build"
phase_log "21 traceLogSplitter build"
run_logged 21_traceLogSplitter_build \
    cmake --build "$SCRIPT_DIR/21_traceLogSplitter/build" --parallel
splitter="$SCRIPT_DIR/21_traceLogSplitter/build/traceLogSplitter"
[[ -x "$splitter" ]] || die "Module 21 build did not produce an executable splitter: $splitter"

if ((reuse_capture == 0)); then
    run_timed_step capture_root_cleanup safe_clear_capture_root
    phase_log "14 sessionController"
    run_logged 14_sessionController "$SCRIPT_DIR/14_sessionController/run.sh" \
        --capture-root "$CAPTURE_ROOT" \
        --logical-output-root "$LOGICAL_OUTPUT_ROOT" \
        --log-root "${PIPELINE_LOG_ROOT:-/media/ramdisk/logs}"
    run_timed_step private_trace_publish publish_private_trace
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
phase_log "apitrace dump"
dump_completed_trace "$trace_file"
dump_file="$trace_dump_dir/bigtrace.log"
phase_log "21 traceLogSplitter"
run_logged_in_directory 21_traceLogSplitter \
    "$(dirname "$CAPTURE_ROOT")" "$splitter" "$dump_file"
run_timed_step private_trace_cleanup safe_delete_private_trace "$trace_file"

phase_log "22 dumpAnalyzer"
run_logged 22_dumpAnalyzer \
    "$SCRIPT_DIR/gradlew" --console=plain :22_dumpAnalyzer:run \
    "--args=--offline --start-frame 3 --output $run_dir/dumpAnalyzer.png"

run_timed_step 22_dumpAnalyzer_validation validate_dump_analyzer_outputs
run_timed_step trace_dump_cleanup safe_delete_trace_dump_path "$dump_file"

phase_log "23 frameTextureNormalizer"
run_logged 23_frameTextureNormalizer \
    "$SCRIPT_DIR/gradlew" --console=plain :23_frameTextureNormalizer:run \
    "--args=--offline"

attempt_suffix="attempt_01_offline_auto"
matrix_attempt_started="$(date +%s)"
log "Starting matrix/delta attempt started_at=$(date --iso-8601=seconds) with offline automatic module 31"
run_timed_step "matrix_staging_reset_${attempt_suffix}" reset_matrix_attempt_staging

phase_log "31 matrixMerger"
run_logged "31_matrixMerger_${attempt_suffix}" \
    "$SCRIPT_DIR/31_matrixMerger/runOffline.sh" "$MATRIX_DIR"
log "Automatic offline module 31 completed."

module_32_log="$run_dir/logs/32_pyramidalImageExporter_${attempt_suffix}.log"
phase_log "32 pyramidalImageExporter"
run_logged "32_pyramidalImageExporter_${attempt_suffix}" \
    "$SCRIPT_DIR/32_pyramidalImageExporter/runOffline.sh" "$MATRIX_DIR" "$destination"
run_timed_step "32_pyramidalImageExporter_validation_${attempt_suffix}" \
    validate_export_log_and_pyramid "$module_32_log"

delta="$MATRIX_DIR/pyramidalImage"
module_42_log="$run_dir/logs/42_pyramidalImageMerger_dry_run_${attempt_suffix}.log"
phase_log "42 pyramidalImageMerger dry-run"
if run_logged "42_pyramidalImageMerger_dry_run_${attempt_suffix}" \
    "$SCRIPT_DIR/gradlew" --console=plain :42_pyramidalImageMerger:run \
    "--args=--dry-run $destination $delta"; then
    log "Module 42 dry run accepted matrix/delta attempt."
    log "Finished matrix/delta attempt finished_at=$(date --iso-8601=seconds) status=0 result=accepted elapsed_seconds=$(($(date +%s) - matrix_attempt_started))"
else
    dry_run_status=$?
    if module_42_reported_conflicts "$module_42_log"; then
        append_merge_error "module_42_dry_run_content_conflict"
        log "Module 42 dry run reported content conflicts. Stopping so the failure can be investigated."
        log "Finished matrix/delta attempt finished_at=$(date --iso-8601=seconds) status=$dry_run_status result=failed_content_conflict elapsed_seconds=$(($(date +%s) - matrix_attempt_started))"
        exit "$dry_run_status"
    fi
    append_merge_error "module_42_dry_run_failed_status_${dry_run_status}"
    log "Module 42 dry run failed without a mergeable result. Stopping so the failure can be investigated."
    log "Finished matrix/delta attempt finished_at=$(date --iso-8601=seconds) status=$dry_run_status result=failed elapsed_seconds=$(($(date +%s) - matrix_attempt_started))"
    exit "$dry_run_status"
fi

if ((dry_run == 1)); then
    log "Dry run succeeded. Destination was not modified."
    exit 0
fi

phase_log "42 pyramidalImageMerger commit"
if run_logged 42_pyramidalImageMerger_commit \
    "$SCRIPT_DIR/gradlew" --console=plain :42_pyramidalImageMerger:run \
    "--args=--offline $destination $delta"; then
    if run_timed_step 42_pyramidalImageMerger_commit_validation \
        validate_merge_completed "$run_dir/logs/42_pyramidalImageMerger_commit.log"; then
        :
    else
        append_merge_error "module_42_commit_validation_failed"
        log "Module 42 merge validation failed. Stopping so the failure can be investigated."
        exit 1
    fi
else
    commit_status=$?
    append_merge_error "module_42_commit_failed_status_${commit_status}"
    log "Module 42 merge failed. Stopping so the failure can be investigated."
    exit "$commit_status"
fi

printf '\n'
if grep -q 'Merge completed as no-op\.' "$run_dir/logs/42_pyramidalImageMerger_commit.log"; then
    log "ITERATION COMPLETED AS NO-OP FOR $destination (0 tiles copied or replaced)"
else
    log "ITERATION COMMITTED SUCCESSFULLY TO $destination"
fi
printf '\n'
if ((keep_work == 0)); then
    log "Temporary staging will now be removed. Use --keep-work to retain it."
fi
