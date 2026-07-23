# Iterative Download and Consolidation Protocol

## Purpose and scope

This is the operational protocol for turning one interactive Google Earth capture into a
validated delta pyramid and then adding that delta to the consolidated dataset. Repeating
the protocol gradually increases the geographic coverage and/or depth of:

```text
/samples/datasets/googleEarth/toplevel
```

The path uses `datasets` (plural), which is the path used by the project configuration and
the module 42 defaults. `/samples/dataset/googleEarth/toplevel` does not currently exist.

Route generation by `11_pathPlanner` is intentionally outside this protocol. A future
outer script will run module 11 and then invoke `./runFullProcess.sh`. Before this protocol
starts, `~/.googleearth/myplaces.kml` must therefore already contain the intended,
expanded `turtle` route.

One execution is one **iteration**. Capture and processing artifacts belong only to that
iteration. The consolidated pyramid is the only long-lived result.

## Non-negotiable invariants

1. `/media/ramdisk/output` is one session-lifetime data unit. It may be cleared before a
   new capture, but it must not be changed or cleared between modules 22 and 32.
   `32_pyramidalImageExporter` reads `topLevelTiles.json` and may read original
   per-frame `frame.json` and texture paths even after modules 23 and 31 have finished.
2. Module 31 must run in `--mode auto --offline` mode for unattended automation. Its
   grouping tile-set conservation check must succeed before any explicit filtering, and
   it must export contract-v3 `matrixLayer.json` files plus every referenced texture.
   During the current operator-assisted protocol, a reviewed small-matrix filter may
   intentionally discard tiles only when its threshold and discarded tile IDs/count are
   reported separately from the conservation check.
3. Module 32 must run with `--export`. A successful process exit is not sufficient:
   some exporter failures are reported in the log without a non-zero exit status. The
   automation must also check the placement report, the completion message, and the
   generated quadtree.
4. A matrix layer is accepted only when all its native tiles receive absolute quadkeys.
   An unplaced layer, an ambiguous placement, a duplicate-path conflict, a failed PNG
   write, or a missing root tile rejects the whole iteration. Sparse output caused by
   genuinely absent child tiles is valid; losing a captured native tile during placement
   is not.
5. Module 42 must first run with `--dry-run`. If it reports a conflict (exit status `2`),
   the real merge must not run. The iteration is rejected and the consolidated pyramid
   remains untouched.
6. Only module 42 may modify the consolidated pyramid. Modules 31 and 32 operate in a
   new per-iteration staging directory under `/tmp`.
7. Quadtree paths use `0` as the root marker and digits `0..3` for descendants. New files
   use one directory per digit after the root marker; for example, `0303301` is stored at
   `3/0/3/3/0/1/0303301.png`.

## Safety rules for deletion and replacement

Never paste an unguarded command such as `rm -rf "$variable"/*` into this workflow.
Empty variables, unexpected symlinks, spelling mistakes, and execution from the wrong
directory can turn it into a system-wide deletion.

The automation follows these rules:

- Resolve a target with `realpath` and compare it with the one exact allowed path before
  deleting children.
- Reject `/`, `/tmp`, the repository root, the destination dataset, empty paths, and
  unresolved paths as cleanup targets.
- Clear only the children of the verified `/media/ramdisk/output`; never remove that
  directory itself.
- Create staging with `mktemp -d` instead of clearing a shared `/tmp/matrix` directory.
- Preserve staging after a rejected iteration so its logs and delta can be inspected.
- Let `14_sessionController` perform its own narrowly scoped cleanup of
  `/opt/google/earth/pro/googleearth-bin*trace`; do not duplicate it with a broader
  command.

`32_pyramidalImageExporter` clears and rebuilds only the session-local
`<staging>/matrix/pyramidalImage`, after it has built a non-empty valid manifest. Module
42 validates the complete delta before copying it to the consolidated tree. However,
module 42 does not currently implement rollback for a filesystem I/O failure during the
copy phase. For a dataset that cannot be reconstructed cheaply, use filesystem snapshots
or a backup around the final merge.

## Phase 0: preflight

Verify all of the following before changing any data:

- Run from the repository root.
- Java 17, Gradle/the Gradle wrapper, CMake, `apitrace`, `jq`, ImageMagick,
  `realpath`, `find`, and `flock` are available.
- `/media/ramdisk/output` exists, is writable, and resolves to that exact path.
- `/samples/datasets/googleEarth/toplevel` is a readable/writable pyramid with `0.png`.
- Module 12 has already been built at
  `12_fileSystemChangesDetector/build/fileSystemChangesDetector`.
- Google Earth uses the modified tracer and its launcher exports
  `QT_LINUX_ACCESSIBILITY_ALWAYS_ON=1`.
- A live X11 desktop is available through `DISPLAY` and `DBUS_SESSION_BUS_ADDRESS`.
- The `turtle` folder exists in `~/.googleearth/myplaces.kml`, is expanded, and contains
  the route produced by module 11.
- No other capture or consolidation process is using the same capture root or destination.

The automation acquires an advisory lock for this workflow. Manual invocations of the
individual modules do not honor that lock, so they must not run concurrently.

## Capacity, duration, and progress baseline

Capacity is a correctness condition, not only an operational concern. Running out of
space can leave an incomplete trace, a partial dump, missing capture blobs, or a
partially copied destination. A later process may still exit successfully while
consuming those incomplete artifacts. Every filesystem used by the workflow must
therefore have its own preflight and runtime budget:

- `/media/ramdisk` holds the capture and is constrained by both bytes and inodes.
- The filesystem containing `/opt/google/earth/pro` holds the binary trace while capture
  data is written to the RAMDISK.
- The filesystem containing the iteration staging directory (currently `/tmp`) holds
  controller logs, the textual dump, the duplicate split tree, matrices, and the delta
  pyramid. Rejected staging directories from earlier iterations also consume this
  filesystem.
- The filesystem containing the consolidated destination needs room for the complete
  delta and a safety reserve. This is especially important because module 42 does not
  currently provide rollback after a copy I/O failure.

Use bytes from `df -B1` and inode counts from `df -Pi` in automation. Human-readable
`df -h` output is useful for operator reports but must not be parsed. In this document,
`GB` means decimal bytes as reported by file sizes and `GiB` means powers of 1024.

The first baseline run on 2026-07-23 used route
`zigzag -20.0 -170.000 200000 6000 200000 10.0 30.0`. It was rejected during the
module-21 frame-set validation by an initial, overly strict implementation. That rejection
does not invalidate the capture: frames are allowed to have no detector-managed images or
blobs. The measurements below cover capture through split, not modules 22 through 42:

| Measurement | Observed value |
|---|---:|
| Route points reported by the controller | 128 |
| Capture duration, including startup and controlled shutdown | about 24 min 27 s |
| Capture frame directories | 24,628 |
| Split `gl.txt` files | 24,634 |
| Capture files | 10,222,362 |
| RAMDISK allocation after capture | about 49 GiB |
| RAMDISK inodes after capture | 10,246,992 |
| Binary trace | 14.27 GB |
| `apitrace dump` duration | about 5 min 42 s |
| Text dump | 5.97 GB |
| Module-21 split duration | about 13 s |
| Isolated split tree | 6.08 GB |
| Module-14 runtime logs | 1.14 GB |

The operational planning envelope for similarly sized areas of interest is at most
120 GiB of RAMDISK allocation and at most 25,000 frames. These are provisional upper
bounds, not evidence that a capture is complete. The observed run was already within
372 frame directories of the frame bound despite using only about 49 GiB. Frame count,
byte consumption, and inode consumption must therefore be monitored independently.

Before starting a normal capture, the initial automation should conservatively require:

- At least the full 120 GiB expected RAMDISK budget plus a 10 GiB emergency reserve, and
  enough free inodes for 12.5 million files. The inode figure is the observed 10.2
  million files plus roughly 20 percent margin; it must be recalibrated with more runs.
- A configurable trace-filesystem budget. Scaling the observed trace-to-capture ratio to
  the 120 GiB capture envelope gives approximately 35 GB; 45 GB free is a reasonable
  initial alert threshold, not a guaranteed upper bound.
- A configurable staging budget for controller logs and post-capture work. Once the
  binary trace exists, the current hard check of `4 * trace_bytes + 1 GiB` before dump
  remains deliberately conservative. It covered the observed simultaneous dump, split,
  and log footprint with substantial margin.
- No rejected staging directories that the operator has not explicitly chosen to keep.
  Automation must list their sizes and refuse to delete them implicitly.

During capture, sample at a fixed interval and record timestamp, frame-directory count,
capture bytes, free RAMDISK bytes/inodes, trace bytes, and free trace-filesystem bytes.
The detector should warn when any budget reaches 80 percent or when recent growth
projects exhaustion before the route can finish. It should request a controlled module-14
shutdown before free RAMDISK space falls below 10 GiB, before fewer than 10 percent of
RAMDISK inodes remain, or before the trace filesystem reaches its reserve. A hard process
kill is a last resort; regardless of shutdown quality, a capacity-triggered stop rejects
the capture and must never advance to `apitrace dump`.

Progress detection must complement capacity detection:

- Warn if neither frame count, capture allocation, nor trace size changes for a
  configurable interval. A five-minute no-progress interval is a reasonable initial
  candidate, but it must be tuned from additional sessions rather than treated as a
  protocol invariant.
- For `apitrace dump`, track the partial file's size and modification time as well as
  free staging space. No growth, an I/O error, a non-zero exit, or encroaching on the
  reserve deletes the guarded partial dump and rejects the iteration.
- For module 21 and later stages, track both free bytes and output counts. A process that
  produces no new output can be stalled even when disk space is plentiful.
- Report elapsed time at every phase boundary. Use the baseline for warnings and
  estimates, not as a hard success condition; content validation and process exit status
  remain authoritative.

The following capacity checks still need measurements from a successful iteration:
peak module-22/23 RAMDISK growth, matrix staging size, delta-pyramid size, module
31/32 duration, dry-run duration, commit duration, and destination growth. Until those
measurements exist, modules 22 through 42 require extra operator supervision and
conservative free-space reserves.

## Phase 1: capture and regenerate the per-frame GL logs

Phase 1 has four ordered sub-phases. Do not combine them with `;`: that shell separator
would run the next command even if the previous one failed. In particular, module 21 must
never consume a partial dump.

### Phase 1.1: start a clean capture session

After the exact-path safety check, clear the children of `/media/ramdisk/output` and call:

```bash
./14_sessionController/run.sh
```

Module 14 starts modules 12 and 13 plus Google Earth. Do not use the mouse or keyboard
during the capture. The only successful capture condition is module 13 printing:

```text
[OK] Finished traversing N points.
```

Module 14 returns `0` only for that condition, `1` for validation/runtime failure, and
`130` when interrupted. Any non-zero result rejects the iteration. Its per-process logs
remain under `/tmp/14_sessionController-PID/`.

Do not process a capture while Google Earth or the asynchronous tracer workers are still
writing it. Successful module-14 shutdown is the synchronization boundary.

For diagnostic reprocessing, `runFullProcess.sh --reuse-capture` skips both the cleanup
and module 14 and starts from the existing `/media/ramdisk/output`. This option must never
be used while a capture is active. It still requires and reprocesses the matching `.trace`
file in the following sub-phases.

### Phase 1.2: select and dump the completed trace

Module 14 removes `/opt/google/earth/pro/googleearth-bin*trace` before capture. After a
successful capture, require **exactly one** non-empty, readable regular file matching that
pattern. Zero matches means the trace was not produced; more than one means trace identity
is ambiguous. Reject both cases instead of choosing the newest file heuristically.

Write the textual dump to the unique iteration staging directory, first with a `.partial`
suffix:

```bash
# In the complete workflow this directory has already been created once.
staging="$(mktemp -d /tmp/google-earth-full-process.XXXXXX)"
mapfile -d '' traces < <(
  find /opt/google/earth/pro -maxdepth 1 -type f \
    -name 'googleearth-bin*trace' -print0
)
((${#traces[@]} == 1)) || exit 1
trace_file="${traces[0]}"

apitrace dump "$trace_file" > "$staging/bigtrace.log.partial" &&
  mv "$staging/bigtrace.log.partial" "$staging/bigtrace.log"
```

The rename is allowed only after `apitrace dump` exits with status `0`, the file is
non-empty, and it contains at least one `glXSwapBuffers` boundary. The textual dump may be
several times larger than the binary trace, so check free space in the filesystem holding
`<staging>` before starting. A failed/partial dump must be deleted from the guarded staging
directory and must never be passed to module 21.

Do not use the shared `/tmp/bigtrace.log` name. Concurrent or interrupted runs could
overwrite it, and a later run could accidentally accept a stale dump.

With `--reuse-capture`, the operator must ensure the sole trace and RAMDISK output are the
same capture. Unlike a normal run that cleans both sides before capture, historical data
has no durable session identifier with which the script could prove that provenance.

### Phase 1.3: split the dump in isolation with module 21

Configure/build module 21 when necessary, create an empty staging subdirectory, and run
the splitter with that subdirectory as its current working directory:

```bash
cmake -S 21_traceLogSplitter -B 21_traceLogSplitter/build
cmake --build 21_traceLogSplitter/build --parallel

mkdir <staging>/trace-split
cd <staging>/trace-split
<repo>/21_traceLogSplitter/build/traceLogSplitter <staging>/bigtrace.log
```

The working directory is part of module 21's contract: it always writes
`./output/%05d/gl.txt`. Running it directly from the repository root, `/tmp`, or
`/media/ramdisk` would write to a different tree and could overwrite unrelated files.

Required postconditions:

- Module 21 exits with status `0` and reports positive line and file counts.
- At least one staged `output/%05d/gl.txt` is non-empty.
- Every result has exactly the `output/<five digits>/gl.txt` shape.
- A staged frame is not required to have an existing `/media/ramdisk/output/%05d`
  directory. Some frames legitimately contain GL calls but no images or blobs emitted by
  the filesystem detector. Publication creates the missing numeric directory.
- Existing capture directories are not required to have a staged `gl.txt`; detector
  artifacts may be written before the first split frame. If the tracer also emitted a
  live `gl.txt` for a particular frame, compare that file with the regenerated file when
  both exist, but do not require equality of the complete directory sets.

Module 21 creates one final empty file after a trailing `glXSwapBuffers`; that file is
allowed. The dump as a whole and the other split files must still contain data.

The 2026-07-23 baseline exposed this expected startup scenario: the capture contained
frame directories `00000` and `00008..24634`, while module 21 produced
`00001..24634`. Frames `00001..00007` have GL logs but no detector-managed resources;
`00000` has a detector artifact but no split log. Neither condition should reject the
iteration. Modules 21 and 22 must be allowed to complete, and useful reconstructed
content is first required at the module-23 boundary.

### Phase 1.4: publish the regenerated `gl.txt` files

Only after validating the complete staged split, create any missing numeric destination
frame folders and copy every result to a temporary sibling inside its destination frame
folder. Folder creation must remain restricted to the exact
`/media/ramdisk/output/%05d` shape derived from validated splitter output. After **all**
copies succeed:

1. Preserve an existing live log as `gl.txt.before-trace-split` the first time that frame
   is regenerated.
2. Rename the temporary sibling to `gl.txt`.
3. Compare every installed file byte-for-byte with its staged source.
4. Call `sync` on `/media/ramdisk/output`.

This ensures module 22 sees one coherent offline-regenerated log set co-located with the
same session's manifests/blobs/textures. It never reads module 21's isolated output tree
directly. The large text dump and duplicate split tree can then be removed from the
guarded staging directory to recover space; the binary `.trace` remains the reproducible
source. Keep `gl.txt.before-trace-split` only until module 22 has completed and its TOP
postconditions have passed; then remove those exact backup filenames to recover RAMDISK
space. At that point the regenerated `gl.txt` plus module-22 outputs are authoritative.

This workflow intentionally regenerates `gl.txt` on every automated iteration, even when
the tracer wrote live copies. The live copies are used only as a frame-set consistency
check and short-lived rollback source; module 22 always consumes the validated offline
split.

## Phase 2: reconstruct frames and the top-level catalogue (module 22)

Run the complete data-processing pass headlessly:

```bash
./gradlew :22_dumpAnalyzer:run \
  --args="--offline --start-frame 3 --output /tmp/dumpAnalyzer.png"
```

The offline renderer is incidental; frame parsing, neighbor construction,
`frame.json`, `globalPatches.json`, and `topLevelTiles.json` are produced before it.
Exit code `666` from the Java application indicates a fatal parse error and rejects the
iteration.

Required postconditions:

- At least one numeric frame directory contains `gl.txt`.
- At least one frame contains a newly generated `frame.json`.
- `/media/ramdisk/output/topLevelTiles.json` is valid JSON.
- `.byStripId | length` is exactly `320` for the current tracer contract.
- The total number of `.byStripId[].appearances[]` entries is greater than zero.

A syntactically valid catalogue with an empty `byStripId` is a failed reconstruction,
even if Gradle returned success.

## Phase 3: normalize frame textures and matrices (module 23)

During the current operator-assisted protocol, run:

```bash
./23_frameTextureNormalizer/run.sh
```

The operator must review and define the west cutters in the interactive window, then
close the application to let the workflow continue. Do not silently substitute offline
mode while this review is required. Offline execution is acceptable only for a region
that does not expose the `-180` meridian seam, or when a previously validated
`westCutters.json` makes the result deterministic for that capture.

For unattended processing, run module 23 offline over the complete frame range:

```bash
./gradlew :23_frameTextureNormalizer:run \
  --args="--offline --width=1 --height=1 --output=/tmp/normalizer.png"
```

Both interactive and offline modes execute the same normalization pipeline before
rendering. The `1x1` output minimizes the diagnostic rendering cost, but the current
offline implementation still attempts one snapshot per surviving frame. A future small
refactor should add a `--process-only` mode; until then this is the available unattended
contract.

Required postconditions:

- At least one frame has `matrix.json`.
- Every `matrix.json` is valid JSON and has positive `rows` and `cols`.
- Every tile has a non-empty ID, in-range zero-based `(i,j)`, and a readable texture.
- Tile IDs are unique within each matrix. The same native ID may intentionally occur in
  different frame matrices; those shared IDs are the overlap anchors used by module 31.
- `westCutters.json`, when present, remains in the schema shared by modules 23 and 31.

Module 23 deliberately deletes obsolete matrices for filtered or invalid frames. That is
valid normalization behavior; zero surviving matrices is not a useful iteration and must
be rejected.

## Phase 4: consolidate matrix layers in isolated staging (module 31)

Create a unique staging directory and run:

```bash
./gradlew :31_matrixMerger:run \
  --args="--mode auto --offline --diagnose-order <staging>/matrix"
```

Do not use the interactive `31_matrixMerger/run.sh` in automation: without `--offline`
it opens a GUI and exports only when the viewer closes.

During operator-assisted iterations, use the interactive launcher and review the
automatically grouped matrices before closing the viewer. The empirical criterion used
in the 2026-07-23 baseline was to delete every resulting matrix with fewer than 10 tiles.
This reduced 12 grouped layers to 8 retained layers and intentionally reduced the native
tile set from 1,859 to 1,848 IDs. The retained layer sizes were 45, 52, 80, 1,290, 43,
32, 177, and 129 tiles.

The `< 10` rule is a provisional manual quality filter, not yet an unattended invariant.
Before automating it, module 31 must report the deleted matrix identities, tile counts,
and native tile IDs, then confirm conservation of every retained tile through export.
This makes intentional quality filtering distinguishable from an accidental lossy merge.

This phase is critical. The automatic pipeline performs retry-merge sweeps, west-cutter
splits, hierarchy ordering, and conservative visual parent inference. Pairwise overlap
conflicts must fail instead of silently consuming only part of a matrix. Its final
tile-set conservation check must report the same unique native tile-ID set before and
after grouping.

Required postconditions:

- The log contains `AutomaticMatrixGroupingPipeline: tile-set conservation OK`.
- At least one `<staging>/matrix/matrix_<n>/matrixLayer.json` exists.
- Every layer has `contractVersion: 3`, a non-empty `matrices` array, valid matrix bounds,
  and readable copied tile PNGs.
- `parentMatrixIndex`, when present, names an earlier exported matrix.
- `hierarchyLevel`, `parentGridTransform`, and
  `hierarchyRelationshipsByTileId` remain relative hierarchy evidence; directory order is
  never treated as an absolute position.

There is no supported "tile-size restriction" retry option in module 31's current CLI.
If automatic grouping fails, do not invent one or fall back to the lossy legacy global
merge. Preserve the staging/logs, fix or recapture the session, and rerun the iteration.

## Phase 5: place matrices and export the session delta (module 32)

Run:

```bash
./gradlew :32_pyramidalImageExporter:run \
  --args="--export <staging>/matrix"
```

The single positional argument is module 31's export folder. Module 32 reads the
top-level catalogue and original frame data from `/media/ramdisk/output`, resolves every
accepted matrix against absolute quadkeys, and writes only to:

```text
<staging>/matrix/pyramidalImage
```

Required postconditions:

- The log contains no `Export failed`, no failed PNG count, no ambiguous discarded tiles,
  and no layer with zero placed tiles.
- For every imported `matrix_<n>`, the placement report says `N/N tiles placed`.
- The log contains `Export complete: N tiles processed` with `N > 0`.
- `<staging>/matrix/pyramidalImage/0.png` exists and is readable.
- Every PNG uses a valid absolute quadkey filename and the per-digit directory layout.

A size-aware operator check complements these structural conditions during the current
manual protocol. For captures near the documented 25,000-frame area-of-interest
baseline:

- More than 1,000 written quadtree tiles is the usual empirical acceptance signal.
- Fewer than 200 written tiles is a strong indication that placement or earlier
  processing failed; stop before module 42 even if the exporter exited successfully.
- Between 200 and 1,000 tiles is an inconclusive review band. Inspect the placement
  report, retained matrices, coverage, and prior-phase counts before accepting it.

These thresholds do not replace full placement or quadtree validation and must be scaled
or recalibrated for materially smaller input sessions. The 2026-07-23 baseline wrote
1,884 new tiles with every retained imported layer fully placed, so it passed both the
structural checks and the empirical size check.

If any matrix cannot be positioned, reject the complete iteration. Do not merge a
top-level-only or partially positioned delta merely because it contains a valid `0.png`.
This prevents a plausible-looking but shifted `-1`, `-2`, `-4`, ... descendant sequence
from entering the long-lived dataset.

## Phase 6: validate and commit the delta (module 42)

First perform the read-only validation:

```bash
./gradlew :42_pyramidalImageMerger:run --args="--dry-run \
  /samples/datasets/googleEarth/toplevel \
  <staging>/matrix/pyramidalImage"
```

Interpret the result as follows:

| Exit status | Meaning | Action |
|---:|---|---|
| `0` | No incompatible overlapping quadkeys | Continue to the real merge |
| `2` | One or more content conflicts | Reject the iteration; do not modify the destination |
| `1` | Invalid input, unreadable tree, or comparison error | Reject and investigate |

The adjacent `-10..0, -170..-140` iteration on 2026-07-23 exercised the conflict path:
module 32 successfully wrote 1,849 tiles and fully placed every retained matrix, but
module 42 found visually incompatible overlaps at levels 6 and 7 against the previously
committed `-20..-10` iteration. The dry run exited with status `2`; the real merge was
not run and the destination remained unchanged. A large, structurally valid delta is
therefore not sufficient evidence to bypass overlap conflicts.

Only after a successful dry run, while still holding the workflow lock, run:

```bash
./gradlew :42_pyramidalImageMerger:run --args="--offline \
  /samples/datasets/googleEarth/toplevel \
  <staging>/matrix/pyramidalImage"
```

Module 42 prevalidates the complete delta, copies new tiles, replaces a destination tile
only when the delta is a visually equivalent higher-resolution image, rescans the
destination, and verifies that every delta quadkey is present. A non-zero result means
the iteration did not commit cleanly. Because there is no rollback for mid-copy I/O
failure, restore a snapshot/backup before retrying if the destination was partially
changed.

An iteration that copies and replaces zero tiles is coherent but made no progress. Record
it as a no-op rather than as new geographic coverage.

## Phase 7: optional visual inspection

The coverage viewer is diagnostic and is deliberately not part of the unattended success
condition:

```bash
./43_pyramidalImageCoverage/run.sh <staging>/matrix/pyramidalImage
./43_pyramidalImageCoverage/run.sh /samples/datasets/googleEarth/toplevel
```

Use it after a rejected iteration to inspect missing levels and after selected successful
iterations as a human regression check. Automated correctness comes from the structural,
placement, conflict, and post-merge checks above.

## Automation decision table

| Boundary | Evidence required to advance | Reject when | Destination modified? |
|---|---|---|---|
| Clean capture root -> module 14 | Exact cleanup target; live desktop; prepared `turtle` | Unsafe path, missing prerequisites | No |
| Module 14 -> `apitrace dump` | Exit `0`; exactly one stable non-empty trace | Capture/controller failure, missing or ambiguous trace | No |
| Dump -> module 21 | Complete non-empty dump with a frame boundary | Partial dump, no space, missing boundary | No |
| Module 21 -> 22 | Valid isolated split; safe frame-folder creation; verified publication | Split failure, invalid path shape, incomplete copy | No |
| Module 22 -> 23 | Frame JSONs; 320 strips; non-zero appearances | Parse failure, empty/broken TOP catalogue | No |
| Module 23 -> 31 | Non-empty valid matrix set | No surviving matrices or invalid references | No |
| Module 31 -> 32 | Conservation OK; contract-v3 layers and copied PNGs | Lossy/conflicting grouping or incomplete export | No |
| Module 32 -> 42 dry run | Every layer fully placed; valid non-empty pyramid | Unplaced/ambiguous tiles, export/write/layout failure | No |
| Dry run -> real module 42 | Exit `0`, zero conflicts | Exit `1` or `2` | No |
| Real module 42 -> complete | Exit `0`; every delta quadkey visible after rescan | Copy or post-merge verification failure | Yes |

## First automation implementation

`./runFullProcess.sh` implements this protocol with these operational choices:

- It starts after route generation (module 11 is not invoked), then executes capture,
  `apitrace dump`, module 21, and modules 22/23/31/32/42 in order.
- In the current operator-assisted phase it opens module 23 interactively and waits for
  the operator to define west cutters and close the window.
- Modules 31 and 32 use their process-only `runOffline.sh` launchers. Module 31 applies
  the documented `< 10` tile filter and module 32 performs the same export action as the
  interactive `e` key without starting JOGL.
- It uses a unique `/tmp/google-earth-full-process.*` staging directory.
- It preserves staging on every failure and deletes it after success unless
  `--keep-work` is given.
- `--reuse-capture` safely reprocesses the current capture without clearing or launching
  Google Earth.
- `--dry-run` completes delta generation and module-42 validation but does not update the
  consolidated dataset.
- It logs every module separately and treats module 32's textual failure states as fatal.

Module 23 remains interactive for the next iterations. Before fully unattended operation,
its west-cutter decision needs a region-safe automatic contract. Module 42 also still
lacks rollback/atomic directory replacement.
