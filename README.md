# Google Earth OpenGL Trace Experiments

This repository contains **experimental Linux-only code** to extract Google Earth image data by leveraging the connection between the application and the GPU through **OpenGL API calls**.

The core idea is to observe and process the OpenGL command stream, including large binary payloads (BLOBs) such as textures and geometry-related data.

This work started from a minimal fork of the original `apitrace` tool, which makes this interception-based approach possible: [apitrace on GitHub](https://github.com/apitrace/apitrace).

## Pipeline Overview

The extraction workflow is split into stages. Each stage has separate codebases, which is why projects are numbered (`01`, `11`, `12`, `13`, `21`, `22`, `23`, `31`, etc.).

- Projects starting with `0` are the **Tracer stage**.
  - They capture OpenGL API activity, produce trace logs, and expose BLOB data for textures and geometry.
- Projects starting with `1` are the **Tracing Execution stage**.
  - They include route/path planning and a Google Earth session controller.
  - The controller simulates user interaction and keeps the session moving at the highest safe speed so data is not lost.
  - This is done by monitoring texture-writing activity through the tracer output.
- Projects starting with `2` are the **Data Normalization stage**.
  - They identify and remove repeated textures and represent texture groups as matrix-like structures for downstream processing.
- Projects starting with `3` are the **Final Gigapixel Build stage**.
  - Goal: build a quadtree of tiles ("Pyramidal Image") from normalized data.
  - Status: **in progress**. `31_matrixMerger` merges per-frame matrices into consolidated
    layer matrices and exports them; `32_pyramidalImageExporter` imports those layers plus
    the global top-level tile data, anchors every layer into absolute quadtree coordinates
    (reconstructing levels 0-5 from per-appearance texture coordinates and resolving deeper
    layers through uncle relationships), and writes the session's pyramidal image as a
    quadtree of PNG files inside its own input folder. Each capture session produces its
    own independent pyramid. `42_pyramidalImageMerger` validates and transactionally merges
    the selected session delta into the consolidated production pyramid.
- Projects starting with `4` are the **Visualization / Inspection stage**.
  - They consume already exported pyramidal images and provide interactive or offline
    inspection tools. `41_planetViewer` is the current viewer. The on-disk pyramid layout
    now uses one folder per quadrant digit after the root marker, so a tile such as
    `0303301` is stored as `3/0/3/3/0/1/0303301.png`. `43_pyramidalImageCoverage` is the
    coverage/debugging viewer used to verify which levels and geographic areas really exist.
- Projects starting with `5` are the **Geospatial Derivative stage**.
  - `51_demResampler` converts the global FABDEM V1.2 GeoTIFF elevation collection into
    the same quadtree address/folder topology, using raw little-endian Int16 DEM tiles
    instead of PNG imagery.
  - `52_planetDemViewer` visualizes those raw DEM pyramids with JOGL, converting the
    256x256 tile cores to textures through selectable Vitral color palettes while retaining
    each 258x258 elevation tile (including its halo) for future terrain triangulation.

## Projects

- [01_tracer/README.md](01_tracer/README.md): Minimal `apitrace`-based tracer fork. Keeps writing `.trace` while exporting runtime artifacts per frame, including texture blobs and GL operation logs.
- [21_traceLogSplitter/README.md](21_traceLogSplitter/README.md): Splits large GL trace logs into per-frame `gl.txt` files.
- [11_pathPlanner/README.md](11_pathPlanner/README.md): Generates planned geographic traversal routes (`spiral`/`zigzag`) and writes KML for Google Earth.
- [12_fileSystemChangesDetector/README.md](12_fileSystemChangesDetector/README.md): Monitors tracer output folder activity with `inotify` to support safe controller pacing.
- [13_googleEarthController/README.md](13_googleEarthController/README.md): Automates Google Earth session progression based on detector inactivity.
- [22_dumpAnalyzer/README.md](22_dumpAnalyzer/README.md): Parses per-frame GL logs (`gl.txt`) and counts/analyses OpenGL calls with ANTLR-based processing.
- [23_frameTextureNormalizer/README.md](23_frameTextureNormalizer/README.md): Consumes frame-level artifacts and performs normalization-oriented preprocessing for later composition workflows.
- [31_matrixMerger/README.md](31_matrixMerger/README.md): Loads per-frame tile matrices, visualizes one matrix at a time, supports interactive/automatic matrix merging and west-cutter splitting, and exports consolidated layer matrices either interactively or through a headless `--offline` run.
- [32_pyramidalImageExporter/README.md](32_pyramidalImageExporter/README.md): Imports consolidated layer matrices from `31_matrixMerger` plus global top-level tile data from `22_dumpAnalyzer`, visualizes the quadtree layers, and exports the session's pyramidal image into the input folder itself (`<inputFolder>/pyramidalImage`); it never writes the production pyramid directly—cross-session merging is handled by `42_pyramidalImageMerger`.
- [42_pyramidalImageMerger/README.md](42_pyramidalImageMerger/README.md): Validates a session delta against the production pyramid and commits it only after a successful dry run.
- [43_pyramidalImageCoverage/README.md](43_pyramidalImageCoverage/README.md): Displays and indexes production-pyramid coverage, including deep-level existence caches for the large production dataset.
- [41_planetViewer/README.md](41_planetViewer/README.md): Loads one or more exported pyramidal images and visualizes them interactively or offline, including stacked overlays and multi-view inspection.
- [51_demResampler/README.md](51_demResampler/README.md): Uses GDAL from a Java 17 CLI to resample FABDEM at quadtree level 12 and recursively build raw `256x256` Int16 little-endian elevation tiles through level 0.
- [52_planetDemViewer/README.md](52_planetDemViewer/README.md): Interactive Java 17 + JOGL/Vitral viewer for raw `258x258` Int16 DEM pyramids with selectable elevation color palettes.

## Tile Download Script Examples

Run these examples from the repository root:

```bash
cd /paradigmas/master/algoritmos_basicos_3d/googleEarthApiTrace
```

`runSpain16.sh` and `runWorld10.sh` use the super-pipeline by default. It overlaps the
capture of the next tile with processing of the previous capture. Only one instance can
run at a time because both scripts share the Google Earth process, the two pipeline slots,
and an exclusive lock.

Preview selected jobs without running Google Earth or writing tiles:

```bash
./scripts/runSpain16.sh --emit-jobs --start-from 30 --limit 5
./scripts/runWorld10.sh --emit-jobs --start-from 100 --limit 5
```

Run a small batch through the default overlapped super-pipeline:

```bash
./scripts/runSpain16.sh --start-from 30 --limit 5
./scripts/runWorld10.sh --start-from 100 --limit 5
```

Resume either download from an ordered tile number. `PIPELINE_TMP_DIR` controls temporary
pipeline state; `/media/ramdisk` is the default:

```bash
PIPELINE_TMP_DIR=/media/ramdisk ./scripts/runSpain16.sh --start-from 30
PIPELINE_TMP_DIR=/media/ramdisk ./scripts/runWorld10.sh --start-from 100
```

For the normal world level-10 restart, omit `--start-from`: the controller inventories
the complete level-10 extent of every selected 8-by-8-degree block and skips only blocks
whose required PNGs are all already present. This preserves partial blocks for retry while
avoiding a re-download of committed blocks.

Use `--direct` only when sequential execution without the super-pipeline is required:

```bash
./scripts/runSpain16.sh --direct --start-from 30 --limit 5
./scripts/runWorld10.sh --direct --start-from 100 --limit 5
```

These are long-running batch jobs, not quick examples. On a system processing roughly
3--4 tiles per hour, they take several days and should be run in a persistent terminal
such as `tmux` or `screen`. As observed reference points, `runSpain15.sh` took about one
week, while `runSpain16.sh` can take almost one month. Start with `--limit`, inspect the
logs and output, and only then launch or resume the complete range.

The controller can also be called directly by selecting the provider explicitly:

```bash
./scripts/runSuperPipeline.sh --job-source spain16 --start-from 120 --limit 10
./scripts/runSuperPipeline.sh --job-source world10 --start-from 120 --limit 10
```

## Production Batch Operating Contract

This section records the operational rules learned while downloading Madrid levels 17
and 18. Future agents should preserve these properties when changing or restarting the
pipeline. A successful process exit, exported matrix, or `COMMITTED` label is not by
itself proof that the requested imagery exists.

### Madrid geometry and commands

The Madrid target is the half-degree rectangle:

- latitude `[40.125, 40.625]`
- longitude `[-3.875, -3.375]`

Level 17 divides it into a `4x4` job grid with `0.125 x 0.125` degree cells:

```bash
./scripts/runSpainMadrid17.sh
# Per-cell route: zigzag LAT LON 1600 50 800 0.125 0.125
```

Level 18 covers exactly the same rectangle with an `8x8` job grid and
`0.0625 x 0.0625` degree cells:

```bash
./scripts/runSpainMadrid18.sh
# Per-cell route: zigzag LAT LON 800 25 400 0.0625 0.0625
```

Coordinates for the level-18 grid require four decimal places. Rounding an odd grid
coordinate such as `-3.6875` to three decimals shifts the capture and is incorrect.
Use `--emit-jobs` to inspect the current pending inventory without starting Google Earth.

### Completion means full geographic coverage

Never decide that a job is complete by checking only its centre quadkey. A capture cell
overlaps thousands of quadtree leaves. The Madrid providers enumerate every leaf at the
requested depth that strictly overlaps each cell and mark the cell downloaded only when
all those PNG files are present in the production pyramid. Rectangle-edge contact alone
does not count as overlap.

The production layout omits the leading root marker from directories. For example, a
quadkey beginning with `031122201...` is below:

```text
/samples/datasets/googleEarth/toplevel/3/1/1/2/2/2/0/1
```

The filename still contains the complete quadkey, including the leading `0`. Level 17
filenames have 18 characters before `.png`; level 18 filenames have 19.

Always verify a reported commit using both of these independent facts:

1. the job snapshot is in `COMMITTED` state and its `runFullProcess.session.log` contains
   `ITERATION COMMITTED SUCCESSFULLY`;
2. the expected level-specific PNG files now exist in the production tree, ideally by
   enumerating the full job extent again.

### Resume from inventory, not from historical sequence numbers

Madrid scripts generate jobs only for cells whose full extent is still incomplete.
Therefore a restart must normally run the script without a historical `--start-from`:

```bash
DISPLAY=:1 XAUTHORITY=/home/jedilink/.Xauthority ./scripts/runSpainMadrid18.sh 2>&1 | tee /tmp/a
```

The resulting sequence numbers describe the current pending list, not the original
64-cell list. Completed cells disappear from that list. This is intentional and prevents
redownloading good data while ensuring partially populated cells are retried.

The super-pipeline stores job descriptors and private traces in two slots:

```text
/media/ramdisk/output
/media/ramdisk/output_incoming
```

On restart it reconciles non-terminal slots by stable quadkey identity. A valid private
capture can resume at processing stage B; a missing/broken capture is marked failed and
captured again; a full geographic extent already present in the destination is treated as
committed. Never delete these slots merely to silence a stale-state error before examining
their `_pipeline/job.json` and `capture.trace` files.

### Overlap, merge isolation, and performance

The super-pipeline overlaps Google Earth capture (stage A) for the next cell with trace
processing/export/merge (stage B) for the previous cell. There must be only one controller
because Google Earth, the capture lock, matrix staging, and both slots are shared.

Deep-level Madrid jobs set `mergeLevelOnly` to their requested level. The exporter may
produce useful ancestors and multiple levels, but the production delta must contain only
the requested leaves plus destination ancestors required for validation. Do not merge all
exported levels for these jobs. When building that delta, deduplicate ancestor quadkeys
before touching the filesystem; thousands of leaves share the same small ancestor set.

Google Earth can occasionally exit during capture (observed exit code 76). Treat this as
a transient capture-session failure and retry the same cell a small bounded number of
times. Never mark it downloaded. If retries are exhausted, leave the failure recorded and
restart from the strict pending inventory after diagnosing display, disk, lock, and trace
state. A capture that yields implausibly few textures must fail exporter validation and
must not reach the production merge.

### Monitoring and evidence

Primary evidence locations are:

```text
/tmp/a
/media/ramdisk/logs/superPipeline/<job-id>/job.json
/media/ramdisk/logs/superPipeline/<job-id>/14_sessionController.log
/media/ramdisk/logs/superPipeline/<job-id>/runFullProcess.session.log
/media/ramdisk/logs/superPipeline/errors.jsonl
```

During a healthy overlapped run, expect messages alternating between stage A and stage B.
Do not assume the batch is alive from an old log timestamp: check for the controller and
inspect the newest job/log update. Also monitor free space on `/media/ramdisk` and the
production filesystem.

`43_pyramidalImageCoverage` provides visual confirmation, but its window may have exited
or may still show a catalogue loaded before the latest merge. Relaunch it when necessary:

```bash
cd 43_pyramidalImageCoverage
DISPLAY=:1 XAUTHORITY=/home/jedilink/.Xauthority \
  ./run.sh /samples/datasets/googleEarth/toplevel
```

Wait for background indexing to report the new maximum depth. The exact filesystem
enumeration remains the authoritative completion check; red areas outside the requested
rectangle or stale viewer state are not proof that a requested job is missing.

## Notes

- This codebase is experimental and tailored to a specific workflow and environment.
- Current focus is Linux execution and OpenGL/GLX-based capture-processing paths.
