#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly CAPTURE_ROOT="/media/ramdisk/output"
readonly DEFAULT_DESTINATION="/samples/datasets/googleEarth/toplevel"
readonly TRACE_DIRECTORY="/opt/google/earth/pro"
readonly TRACE_PATTERN="googleearth-bin*trace"

destination="$DEFAULT_DESTINATION"
reuse_capture=0
dry_run=0
keep_work=0
run_dir=""
completed=0

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
  --keep-work         Preserve staging after a successful execution
  -h, --help          Show this help
EOF
}

log() {
    printf '[runFullProcess] %s\n' "$*"
}

die() {
    printf '[runFullProcess][ERROR] %s\n' "$*" >&2
    exit 1
}

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
    trap - EXIT
    if ((status != 0)); then
        [[ -z "$run_dir" ]] || log "Iteration rejected; diagnostic staging preserved at $run_dir"
        exit "$status"
    fi
    if ((completed == 1 && keep_work == 0)); then
        safe_remove_run_dir || true
    elif [[ -n "$run_dir" ]]; then
        log "Staging preserved at $run_dir"
    fi
}
trap on_exit EXIT

run_logged() {
    local name="$1"
    shift
    log "Starting $name"
    "$@" 2>&1 | tee "$run_dir/logs/$name.log"
}

run_logged_in_directory() {
    local name="$1"
    local directory="$2"
    shift 2
    log "Starting $name in $directory"
    (cd "$directory" && "$@") 2>&1 | tee "$run_dir/logs/$name.log"
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

available_bytes() {
    df -B1 --output=avail "$1" | awk 'NR == 2 { print $1 }'
}

select_completed_trace() {
    local -a traces=()
    local before after
    mapfile -d '' traces < <(
        find "$TRACE_DIRECTORY" -maxdepth 1 -type f -name "$TRACE_PATTERN" -print0 | sort -z
    )
    ((${#traces[@]} == 1)) ||
        die "Expected exactly one completed $TRACE_DIRECTORY/$TRACE_PATTERN trace; found ${#traces[@]}."
    [[ -r "${traces[0]}" && -s "${traces[0]}" ]] ||
        die "Trace is empty or unreadable: ${traces[0]}"

    before="$(stat -c '%s:%Y' -- "${traces[0]}")"
    sync "${traces[0]}"
    sleep 2
    after="$(stat -c '%s:%Y' -- "${traces[0]}")"
    [[ "$before" == "$after" ]] || die "Trace changed during the stability check: ${traces[0]}"
    printf '%s\n' "${traces[0]}"
}

dump_completed_trace() {
    local trace_file="$1"
    local partial="$run_dir/bigtrace.log.partial"
    local dump_file="$run_dir/bigtrace.log"
    local trace_bytes free_bytes minimum_free

    trace_bytes="$(stat -c '%s' -- "$trace_file")"
    free_bytes="$(available_bytes "$run_dir")"
    minimum_free=$((trace_bytes * 4 + 1073741824))
    ((free_bytes >= minimum_free)) ||
        die "Insufficient staging space for trace dump/split: need at least $minimum_free bytes, have $free_bytes."

    log "Dumping completed trace $trace_file"
    if ! apitrace dump "$trace_file" > "$partial" 2> >(tee "$run_dir/logs/apitrace_dump.log" >&2); then
        safe_delete_staging_path "$partial"
        die "apitrace dump failed; its partial output was discarded."
    fi
    [[ -s "$partial" ]] || {
        safe_delete_staging_path "$partial"
        die "apitrace dump produced an empty file."
    }
    grep -q 'glXSwapBuffers' "$partial" || {
        safe_delete_staging_path "$partial"
        die "Trace dump contains no glXSwapBuffers frame boundary."
    }
    mv -- "$partial" "$dump_file"
}

validate_split_tree() {
    local split_root="$1"
    local splitter_log="$run_dir/logs/21_traceLogSplitter.log"
    local file relative frame nonempty=0
    local split_frames="$run_dir/split-frame-set.txt"
    local live_frames="$run_dir/live-frame-set.txt"
    local live_count

    grep -Eq 'Done\. Files created: [1-9][0-9]*' "$splitter_log" ||
        die "Module 21 did not report a positive output-file count."
    grep -Eq 'Lines processed: [1-9][0-9]*' "$splitter_log" ||
        die "Module 21 did not report a positive processed-line count."

    : > "$split_frames"
    while IFS= read -r -d '' file; do
        relative="${file#"$split_root"/}"
        [[ "$relative" =~ ^[0-9]{5}/gl\.txt$ ]] || die "Unexpected module-21 output file: $file"
        frame="${relative%%/*}"
        [[ -d "$CAPTURE_ROOT/$frame" ]] ||
            die "Split frame $frame has no same-session capture folder for manifests/blobs/textures."
        printf '%s\n' "$frame" >> "$split_frames"
        [[ -s "$file" ]] && nonempty=$((nonempty + 1))
    done < <(find "$split_root" -type f -print0 | sort -z)
    [[ -s "$split_frames" ]] || die "Module 21 produced no per-frame gl.txt files."
    ((nonempty > 0)) || die "All module-21 gl.txt files are empty."

    find "$CAPTURE_ROOT" -mindepth 2 -maxdepth 2 -type f -name gl.txt \
        -printf '%h\n' | sed 's#^.*/##' | sort > "$live_frames"
    live_count="$(awk 'END { print NR + 0 }' "$live_frames")"
    if ((live_count > 0)); then
        cmp -s "$split_frames" "$live_frames" ||
            die "Live and regenerated gl.txt frame-folder sets differ; refusing to mix them."
    fi
}

install_split_logs() {
    local split_root="$1"
    local token="${run_dir##*.}"
    local source frame directory current partial rollback canonical_backup
    local i j verification_failed=0 split_bytes capture_free minimum_free
    local -a sources=() currents=() partials=() rollbacks=()

    mapfile -d '' sources < <(find "$split_root" -type f -name gl.txt -print0 | sort -z)
    ((${#sources[@]} > 0)) || die "There are no validated split logs to install."
    split_bytes="$(du -sb "$split_root" | awk '{print $1}')"
    capture_free="$(available_bytes "$CAPTURE_ROOT")"
    minimum_free=$((split_bytes + 67108864))
    ((capture_free >= minimum_free)) ||
        die "Insufficient RAMDISK space to stage regenerated logs safely: need $minimum_free bytes, have $capture_free."

    for source in "${sources[@]}"; do
        frame="$(basename "$(dirname "$source")")"
        directory="$CAPTURE_ROOT/$frame"
        current="$directory/gl.txt"
        partial="$directory/.gl.txt.from-traceLogSplitter.$token.partial"
        rollback="$directory/.gl.txt.before-install.$token"
        [[ ! -e "$partial" && ! -e "$rollback" ]] ||
            die "Refusing to overwrite an existing integration temporary in $directory"
        if ! cp -- "$source" "$partial" || ! cmp -s "$source" "$partial"; then
            for partial in "${partials[@]}" "$partial"; do
                [[ -e "$partial" ]] && find "$partial" -maxdepth 0 -type f -delete
            done
            die "Could not stage and verify regenerated log for frame $frame."
        fi
        currents+=("$current")
        partials+=("$partial")
        rollbacks+=("$rollback")
    done

    for ((i = 0; i < ${#sources[@]}; i++)); do
        current="${currents[i]}"
        partial="${partials[i]}"
        rollback="${rollbacks[i]}"
        if [[ -e "$current" ]]; then
            if ! mv -- "$current" "$rollback"; then
                break
            fi
        fi
        if ! mv -- "$partial" "$current"; then
            [[ ! -e "$rollback" ]] || mv -- "$rollback" "$current"
            break
        fi
    done

    if ((i < ${#sources[@]})); then
        for ((j = 0; j < i; j++)); do
            current="${currents[j]}"
            rollback="${rollbacks[j]}"
            find "$current" -maxdepth 0 -type f -delete
            [[ ! -e "$rollback" ]] || mv -- "$rollback" "$current"
        done
        for partial in "${partials[@]}"; do
            [[ -e "$partial" ]] && find "$partial" -maxdepth 0 -type f -delete
        done
        die "Publishing regenerated gl.txt files failed; previous live logs were restored."
    fi

    for ((i = 0; i < ${#sources[@]}; i++)); do
        if ! cmp -s "${sources[i]}" "${currents[i]}"; then
            verification_failed=1
            break
        fi
    done
    if ((verification_failed == 1)); then
        for ((j = 0; j < ${#sources[@]}; j++)); do
            current="${currents[j]}"
            rollback="${rollbacks[j]}"
            [[ ! -e "$current" ]] || find "$current" -maxdepth 0 -type f -delete
            [[ ! -e "$rollback" ]] || mv -- "$rollback" "$current"
        done
        die "Installed gl.txt verification failed; previous live logs were restored."
    fi

    for ((i = 0; i < ${#sources[@]}; i++)); do
        rollback="${rollbacks[i]}"
        [[ -e "$rollback" ]] || continue
        directory="$(dirname "${currents[i]}")"
        canonical_backup="$directory/gl.txt.before-trace-split"
        if [[ -e "$canonical_backup" ]]; then
            find "$rollback" -maxdepth 0 -type f -delete
        else
            mv -- "$rollback" "$canonical_backup"
        fi
    done
    sync "$CAPTURE_ROOT"
}

discard_live_log_backups_after_analysis() {
    local backup_count
    backup_count="$(find "$CAPTURE_ROOT" -mindepth 2 -maxdepth 2 -type f \
        -name gl.txt.before-trace-split -print | awk 'END { print NR + 0 }')"
    if ((backup_count > 0)); then
        find "$CAPTURE_ROOT" -mindepth 2 -maxdepth 2 -type f \
            -name gl.txt.before-trace-split -delete
        sync "$CAPTURE_ROOT"
        log "Removed $backup_count validated live-log backup(s) after module 22 succeeded."
    fi
}

count_files() {
    local root="$1"
    local name="$2"
    find "$root" -type f -name "$name" -print | awk 'END { print NR + 0 }'
}

validate_source_matrices() {
    local file rows cols tile_count id_count unique_id_count texture_count invalid_coords missing_texture texture
    local total=0
    while IFS= read -r -d '' file; do
        total=$((total + 1))
        jq -e . "$file" >/dev/null || die "Invalid matrix JSON: $file"
        rows="$(jq -r '.rows // 0' "$file")"
        cols="$(jq -r '.cols // 0' "$file")"
        [[ "$rows" =~ ^[1-9][0-9]*$ && "$cols" =~ ^[1-9][0-9]*$ ]] ||
            die "Invalid matrix dimensions in $file: ${rows}x${cols}"
        tile_count="$(jq -r '(.tiles // []) | length' "$file")"
        ((tile_count > 0)) || die "Matrix contains no tiles: $file"
        id_count="$(jq -r '[.tiles[].id | select(type == "string" and length > 0)] | length' "$file")"
        ((id_count == tile_count)) || die "Matrix has missing/invalid tile IDs: $file"
        unique_id_count="$(jq -r '[.tiles[].id] | unique | length' "$file")"
        ((unique_id_count == tile_count)) || die "Matrix contains duplicate tile IDs: $file"
        invalid_coords="$(jq -r --argjson rows "$rows" --argjson cols "$cols" \
            '[.tiles[] | select((.i < 0) or (.j < 0) or (.i >= $rows) or (.j >= $cols))] | length' "$file")"
        ((invalid_coords == 0)) || die "Matrix has out-of-range tile coordinates: $file"
        missing_texture=0
        texture_count="$(jq -r '[.tiles[].textureFile | select(type == "string" and length > 0)] | length' "$file")"
        ((texture_count == tile_count)) || die "Matrix has missing/invalid texture paths: $file"
        while IFS= read -r texture; do
            [[ -n "$texture" && -r "$texture" ]] || missing_texture=$((missing_texture + 1))
        done < <(jq -r '.tiles[].textureFile // empty' "$file")
        ((missing_texture == 0)) || die "Matrix references $missing_texture unreadable texture(s): $file"
    done < <(find "$CAPTURE_ROOT" -mindepth 2 -maxdepth 2 -type f -name matrix.json -print0)
    ((total > 0)) || die "Module 23 produced no matrix.json files."
}

validate_exported_layers() {
    local file parent index texture tile_count texture_count layer_count=0
    while IFS= read -r -d '' file; do
        layer_count=$((layer_count + 1))
        index="$(basename "$(dirname "$file")")"
        index="${index#matrix_}"
        jq -e '
            .contractVersion == 3 and
            (.matrices | type == "array" and length > 0) and
            ([.matrices[] | select((.rows // 0) <= 0 or (.cols // 0) <= 0)] | length == 0) and
            ([.matrices[].tiles[]? | select((.id | type) != "string" or (.id | length) == 0)] | length == 0)
        ' "$file" >/dev/null || die "Invalid contract-v3 matrix layer: $file"
        parent="$(jq -r '.parentMatrixIndex // empty' "$file")"
        if [[ -n "$parent" ]]; then
            [[ "$parent" =~ ^[0-9]+$ ]] || die "Invalid parentMatrixIndex in $file"
            ((parent < index)) || die "parentMatrixIndex must name an earlier layer in $file"
        fi
        tile_count="$(jq -r '[.matrices[].tiles[]?] | length' "$file")"
        texture_count="$(jq -r '[.matrices[].tiles[]?.textureFile | select(type == "string" and length > 0)] | length' "$file")"
        ((tile_count > 0 && texture_count == tile_count)) ||
            die "Exported layer has missing tiles or texture paths: $file"
        while IFS= read -r texture; do
            [[ -r "$texture" ]] || die "Exported layer references unreadable texture: $texture"
        done < <(jq -r '.matrices[].tiles[].textureFile // empty' "$file")
    done < <(find "$run_dir/matrix" -mindepth 2 -maxdepth 2 -type f -name matrixLayer.json -print0 | sort -z)
    ((layer_count > 0)) || die "Module 31 exported no matrix layers."
}

validate_export_log_and_pyramid() {
    local export_log="$run_dir/logs/32_pyramidalImageExporter.log"
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

for command_name in java cmake apitrace realpath find jq awk sed sort grep tee flock sync rmdir cksum compare identify mktemp mkdir basename dirname stat df du sleep cp mv cmp; do
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

lock_key="$(printf '%s' "$destination" | cksum | awk '{print $1}')"
exec 9>"/tmp/google-earth-full-process-${lock_key}.lock"
flock -n 9 || die "Another automated iteration is using destination $destination"

run_logged 21_traceLogSplitter_configure \
    cmake -S "$SCRIPT_DIR/21_traceLogSplitter" -B "$SCRIPT_DIR/21_traceLogSplitter/build"
run_logged 21_traceLogSplitter_build \
    cmake --build "$SCRIPT_DIR/21_traceLogSplitter/build" --parallel
splitter="$SCRIPT_DIR/21_traceLogSplitter/build/traceLogSplitter"
[[ -x "$splitter" ]] || die "Module 21 build did not produce an executable splitter: $splitter"

if ((reuse_capture == 0)); then
    safe_clear_capture_root
    run_logged 14_sessionController "$SCRIPT_DIR/14_sessionController/run.sh"
else
    log "Reusing the existing capture; cleanup and module 14 are skipped."
fi

trace_file="$(select_completed_trace)"
log "Selected completed trace: $trace_file"
dump_completed_trace "$trace_file"
dump_file="$run_dir/bigtrace.log"
split_work="$run_dir/trace-split"
mkdir "$split_work"
run_logged_in_directory 21_traceLogSplitter "$split_work" "$splitter" "$dump_file"
validate_split_tree "$split_work/output"
install_split_logs "$split_work/output"
safe_delete_staging_path "$dump_file"
safe_delete_staging_path "$split_work"
log "Regenerated gl.txt files were validated and published; large temporary dump/split data was removed."

gl_count="$(count_files "$CAPTURE_ROOT" gl.txt)"
((gl_count > 0)) || die "Capture contains no gl.txt frame files."

run_logged 22_dumpAnalyzer \
    "$SCRIPT_DIR/gradlew" :22_dumpAnalyzer:run \
    "--args=--offline --start-frame 3 --output $run_dir/dumpAnalyzer.png"

frame_count="$(count_files "$CAPTURE_ROOT" frame.json)"
((frame_count > 0)) || die "Module 22 produced no frame.json files."
top_level="$CAPTURE_ROOT/topLevelTiles.json"
jq -e . "$top_level" >/dev/null || die "Missing or invalid topLevelTiles.json."
strip_count="$(jq -r '.byStripId | length' "$top_level")"
appearance_count="$(jq -r '[.byStripId[].appearances[]?] | length' "$top_level")"
((strip_count == 320)) || die "Expected 320 top-level strips; found $strip_count."
((appearance_count > 0)) || die "Top-level catalogue contains no appearances."
discard_live_log_backups_after_analysis

run_logged 23_frameTextureNormalizer \
    "$SCRIPT_DIR/gradlew" :23_frameTextureNormalizer:run \
    "--args=--offline --width=1 --height=1 --output=$run_dir/normalizer.png"
validate_source_matrices

run_logged 31_matrixMerger \
    "$SCRIPT_DIR/gradlew" :31_matrixMerger:run \
    "--args=--mode auto --offline --diagnose-order $run_dir/matrix"
grep -q 'AutomaticMatrixGroupingPipeline: tile-set conservation OK' \
    "$run_dir/logs/31_matrixMerger.log" || die "Module 31 did not confirm tile-set conservation."
validate_exported_layers

run_logged 32_pyramidalImageExporter \
    "$SCRIPT_DIR/gradlew" :32_pyramidalImageExporter:run \
    "--args=--export $run_dir/matrix"
validate_export_log_and_pyramid

delta="$run_dir/matrix/pyramidalImage"
run_logged 42_pyramidalImageMerger_dry_run \
    "$SCRIPT_DIR/gradlew" :42_pyramidalImageMerger:run \
    "--args=--dry-run $destination $delta"

if ((dry_run == 1)); then
    log "Dry run succeeded. Destination was not modified."
    keep_work=1
    completed=1
    exit 0
fi

run_logged 42_pyramidalImageMerger_commit \
    "$SCRIPT_DIR/gradlew" :42_pyramidalImageMerger:run \
    "--args=--offline $destination $delta"
grep -q 'Merge completed\.' "$run_dir/logs/42_pyramidalImageMerger_commit.log" ||
    die "Module 42 did not report a completed merge."

completed=1
log "Iteration committed successfully to $destination"
if ((keep_work == 0)); then
    log "Successful staging will now be removed. Use --keep-work to retain it."
fi
