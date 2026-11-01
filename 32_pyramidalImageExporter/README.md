# 32_pyramidalImageExporter

`32_pyramidalImageExporter` is the last stage of the pipeline: it imports the consolidated
layer matrices exported by `31_matrixMerger`, plus the global top-level tile data produced
by `22_dumpAnalyzer`, and visualizes them as the layers of the future quadtree "Pyramidal
Image".

## Status

The top quadtree levels (`0..5`) are now fully reconstructed: every cell of every
top-level layer is textured and the layer visualization corresponds to the map of planet
Earth. Deeper `matrix_<n>` tiles are exported too whenever they can be anchored to an
absolute quadtree path: directly, through cross-level references, through a contract-v3
`parentGridTransform`, or through accepted visual anchors (see "Exporting the pyramidal
image" below). The pyramid can be exported to disk as a quadtree of PNG files with the
`e` key.

## Inputs

- `<inputFolder>` (positional, required, no default): directory containing the
  `matrix_<n>` folders exported by `31_matrixMerger`, each with a `matrixLayer.json` and
  its tile textures. `MatrixLayerJsonReader` reads these folders in numeric `matrix_<n>`
  order. Contract-v3 files also contain `hierarchyLevel`, `parentMatrixIndex`, an
  optional `parentGridTransform`, and full `hierarchyRelationshipsByTileId`; the reader
  merges those relationship records back into each tile's `uncles` list before placement.
  The top-level pyramid (levels `0..5`, see below) does not depend on this folder having
  any `matrix_<n>` subfolders, only `topLevelTiles.json` does. Neither the program nor
  `./run.sh` assumes a default: if this argument is missing, both exit with code `1` and
  an English error message explaining that it must point to `31_matrixMerger`'s export
  folder.
- `topLevelTiles.json` read from the root of `output.directory` (configured in
  [src/main/resources/application.properties](src/main/resources/application.properties),
  default `/media/ramdisk/output`). This is where the actual source data (strips,
  appearances and their images) comes from.

There is no destination argument: the session's pyramidal image quadtree is always
written inside the input folder itself, to `<inputFolder>/pyramidalImage` (created on
first export). It never reads its own previous export and never writes outside
`<inputFolder>`. An explicitly supplied reference pyramid is read-only; its upper levels
may provide the delta scaffold described below. Merging the remaining imagery from
different capture sessions is the responsibility of program 42. Passing a
second positional argument (the old destination parameter) is an error: the program
prints an English message explaining this contract and exits with code `1`.

### Top-level reconstruction from texCoords

`TopLevelMatrixRebuilder` rebuilds levels `0..5` (sizes `1x1` to `32x32`) from the
per-appearance `texCoord` data in `topLevelTiles.json`, using two facts of the traced
data:

- Each globe strip covers exactly `1/32 x 1/32` of the world texture space, so the strip
  lattice is the level-5 quadtree grid.
- Each appearance image is itself a quadtree-aligned `256x256` tile at some level
  `k (0..4)`. An appearance whose `texCoord` spans `1/32` identifies a whole-world image
  and anchors the strip's world rectangle; every other appearance is affinely unmapped to
  recover its image's level and world cell.

Each layer cell is then textured with the deepest catalogued image containing it, using
the corresponding texture sub-rectangle (`TileCoord.texU0/texV0/texU1/texV1`, OpenGL
convention: `v = 0` at the image bottom / south side). The legacy `pathFromRoot`,
`row` and `col` fields of `topLevelTiles.json` are no longer used.

## Execution

From this directory:

```bash
gradle run --args="<inputFolder>"
```

or `./run.sh <inputFolder>`, which forwards its arguments unchanged. The single
positional argument is mandatory and has no default; when it is missing, `./run.sh` (and
the program itself) prints an English error message explaining the missing argument and
exits with code `1`. The exported pyramid always lands in `<inputFolder>/pyramidalImage`.

## Interactive usage guide

Program-specific keys (generic camera handling comes from Vitral and is not listed here):

| Key | Action |
|---|---|
| `1` / `2` | Select previous / next matrix layer |
| `t` | Toggle textured rendering |
| `c` | Toggle the relative RMS color map for fine-to-coarse relationships |
| `e` | Export the pyramidal image quadtree to `<inputFolder>/pyramidalImage` |
| `ESC` | Exit |

The RMS relationship map is loaded lazily when `--rms-map` is supplied or the
`c` key enables it for the first time. Export therefore avoids this diagnostic
image pass. Native tile copies run concurrently; set the JVM system property
`pyramidalimageexporter.exportThreads` to override the default worker count.
RMS comparisons also use a bounded worker pool, configurable with
`pyramidalimageexporter.rmsThreads`.

HUD:

- `Layer [1, 2]: i/N | frame <id> | Matrix: <rows>x<cols>`: selected layer and its size.
- `Toggle textures [t], RMS color map [c]: ON/OFF (<n> comparisons), Export [e], ...`.
- `Export destination: <inputFolder>/pyramidalImage | <last export status>`.
- When the bounding-volume display mode is enabled, each visible tile is annotated with
  its id and `(i, j)` cell coordinates.

## Exporting the pyramidal image

Pressing `e` writes every tile that can be anchored to an absolute quadtree path to
`<inputFolder>/pyramidalImage` as a quadtree of PNG files, matching the pyramid
already drawn in the interactive viewer:

- The root tile is `0.png`, directly in the destination directory.
- Every deeper tile uses one folder per quadrant digit after the root marker, while the
  file name remains the complete absolute quadkey. For example, `00` is written as
  `0/00.png`, `002` as `0/2/002.png`, and `0303301` as `3/0/3/3/0/1/0303301.png`.
- This omits the redundant root marker from the directory names and avoids repeating the
  cumulative prefixes in every folder segment.
- A tile is written only when its source texture is natively `256x256` and the tile uses
  that complete texture. Partial ancestor sub-rectangles remain available for visualization
  but are not upscaled/exported; the on-disk quadtree is intentionally allowed to have holes.
- Native top-level catalog images are added directly to the export manifest at their resolved
  quadkeys and copied byte-for-byte. Export does not depend on the ancestor-filled matrices
  used by the GUI.
- If `topLevelTiles.json` is empty or missing, an explicitly supplied reference pyramid
  contributes its native levels `0..5` as the delta scaffold. Without either source for
  `0.png`, export fails without clearing the previous pyramid.

This replaces the earlier `matrix_<n>/matrixLayer.json` copy-based layout used by
`31_matrixMerger`'s `MatrixLayerExportWriter`: the pyramidal image is written directly as a
quadtree of images, with no JSON manifest.

### Recovering incomplete continental L10 groups

A successful export or a `COMMITTED` log alone does not establish coverage. For
reviewed repairs, count physical PNGs using
`scripts/superPipelineController.py::destination_contains_job_area`, require a
successful program-42 dry-run and commit, repeat the physical check, and inspect
program 43 at depth 10 in `SHOW_GROUPS_BEING_DOWNLOADED`. The yellow rectangle
must lose its red/mottled interior. Refresh the viewer before taking `/tmp/b.png`;
a screenshot of a stale catalogue is not evidence of a failed or successful merge.
Never delete production tiles to force a repair. Preserve failed slots and logs.

The Canada `(52,-116)` recovery demonstrated two distinct failure modes:

- Program 31's `absoluteLevel` diagnostic is inferred from its configured maximum;
  it is not an independently measured geographic level. Its apparent L9–L14 chain
  was actually L5–L10. Program 32 previously assigned disconnected descendants to
  L21/L22 by using unrelated deepest reference islands. Use resolved session
  hierarchy offsets and parent/child evidence instead. Diagnose from isolated
  matrix staging and inspect the exporter's per-layer placement report before
  changing capture parameters or publishing a delta.
- After correcting placement, the original capture exported 840 native L10 tiles,
  but destination plus delta covered only 437 of the rectangle's 552 cells. Five
  northern rows remained at L9. Recapturing only the north strip at 70,000 m,
  with 70,000 m between path points, supplied the missing L10 images. Selecting
  the reviewed rectangle then added 529 tiles, retained 23 existing tiles, and
  produced 552/552 physical coverage. The yellow rectangle's red interior
  disappeared in 43. Logs and before/after images are preserved under
  `/media/ramdisk/logs/superPipeline/world10-priority-continental-000642-52--116/hardPlan-recovery`.

A later offline rerun of Hudson Bay `(52,-92)` showed a different limit: 31
with minimum tile count 1 and 32 exported 726 native L10 images, but only 370
of the rectangle's 576 cells had native L10 coverage. Auditing all 13,669 frame
files found no native L10 source for the remaining 206 cells; 178 have captured
L9 parents and 28 have L8 parents. Their original pixels could supply geographic
L10 crops at 128×128 and 64×64, respectively, but those are lower-resolution
derived tiles and must not be represented as native 256×256 imagery. Under the
native-only contract the merge remains blocked.

The next `(52,-92)` trial halves the height parameter from 77,000 m to 38,500 m
and spacing from 70,000 m to 35,000 m. `KmlPersistence` writes three times that
height to `LookAt.altitude`, so this changes the camera altitude from 231,000 m
to 115,500 m. Other reviewed groups retain the previous pattern. Compare the
370/576 baseline with both direct L10 images and complete L10 parents rebuilt
from finer children; counting only direct L10 images can miss a genuine
improvement from native L11 capture. `PIPELINE_MINIMUM_TILE_COUNT=1` keeps the
same program-31 threshold as the diagnostic rerun. Keep the earlier capture
and cleanup backup; do not use lower-resolution ancestor crops for this trial.

The structural L10 anchor `[10,338,242]` in that capture was confirmed against
frame vertices. Its rejected camera cluster `[10,337,245]` was displaced by one
row and three columns. A high count of "exact" centre-tile observations does
not remove the four-cell clustering tolerance: do not lower the coarse-layer
camera override threshold merely to force completeness. Rebuilding 31 also
need not produce one consecutive hierarchy chain; inspect measured geographic
levels rather than deleting matrices because their inferred absolute levels repeat.
Evidence is in
`/media/data/samples/datasets/googleEarth/canada-52--92-reprocess-20261026`.

The path-planner argument order matters:

```text
./run.sh zigzag latitude longitude step_m max_distance_m altitude_m lat_span lon_span
```

For example, `180000 3200 100000 8 8` means **180,000 m point spacing and
100,000 m altitude**. Changing `180000` to `162000` does not change camera height.
In a parsed route including `./run.sh`, spacing is index 4 and altitude is index 6.
Move the camera farther away only when measured detail is too deep; a region
still supplied at L9 may require a closer camera to obtain native L10. Inspect the
actual layer levels and the coordinates of missing cells before choosing a retry.
Do not assume that more raw PNGs imply complete coverage.

`scripts/buildLevelOnlyDelta.py` selects existing L10 parents even when deeper
tiles are also present. If a parent is absent, it can reconstruct it from four
complete child quadrants, recursively, with the project's quadrant orientation.
An incomplete child set cannot manufacture coverage, and L9 tiles must not be
upscaled and labelled as native L10. Ancestors below the selected merge level
are copied from the existing destination. This helper requires Python Pillow.
The expected count depends on quadtree alignment: an 8×8-degree group is not
always 529 cells; the reviewed rectangles below require 552 or 576 cells.

The current reviewed batch is exactly `(52,-100)`, `(52,-92)`, `(44,-76)`,
`(36,44)`, and `(36,52)`, provided by `scripts/runWorld10PriorityContinental.sh`.
All five have `mustCommit=true`, `mergeLevelOnly=10`, and explicit capture spacing
and altitude metadata. The controller stops on failure; the level-only pipeline
rejects an incomplete destination-plus-delta rectangle before invoking program 42.
The verified `(52,-116)` and `(52,-108)` groups and Australia are excluded.
The current pattern uses 70,000 m spacing and 77,000 m altitude (10% farther away).
`PIPELINE_REFERENCE_PYRAMID` can point to an isolated geographic reference snapshot
for module 32, avoiding a full scan of unrelated deep islands. The actual merge
destination and the L10 completeness checks still use `--destination`. Use sequential capture
and adequate disk staging for full rectangles; retain the earlier recovery evidence.
Program 42's offline mode reads only the delta footprint and checks that same
footprint after committing, while preserving unrelated branches and descendants.

### Which tiles are exported

A tile is exportable only if `pyramidalimageexporter.processing.uncles.TileRootPathResolver`
can anchor it to a full path from the root (a string of quadrant digits, e.g. `"0021"`):

- Tiles whose own `id` already is such a path (every `topLevel_matrix_*` tile, by
  construction) are anchored directly.
- A partial imported matrix that meets the reconstructed levels `0..5` can be anchored
  visually even when none of its native images is byte-identical to a catalogue image.
  Up to 16 distributed tiles are compared with reconstructed cell sub-rectangles; each
  match must beat the second candidate by a conservative margin, and at least three
  matches plus a strict majority must vote for one rigid `(level,rowOffset,colOffset)`.
  Matching also considers the four level-6 quadrants of reconstructed level-5 cells,
  because the first native imported layer may already be one level below TOP.
- Imported descendant layers can also be anchored visually against already canonicalized
  imported parent layers from the same session. If `parentMatrixIndex` names a designated
  parent, visual matching is restricted to that parent; otherwise all strongly anchored
  imported layers are candidates. Accepted matches become explicit retained
  `tileId -> quadkey` seeds for the export pass.
- Any other tile can still be anchored if one of its `uncles` relationships
  (`ToUncleRelationship(direction, uncleContentId, relationshipKind)`) points, by id, to
  an anchored coarse tile. `CONTAINING_QUADRANT` appends the quadrant selected by
  `direction`; `ADJACENT_BORDER` crosses the named coarse-cell border and selects the
  touching child quadrant. For contract-v3 and older records without `relationshipKind`,
  both interpretations are hypotheses and only a unique rigid-grid consensus supported
  by at least three distinct relationships can place the matrix.
  Before a readable relationship votes, the fine `256x256` image is scaled to `128x128`
  and compared by RMS with all four `128x128` quadrants of the coarse image. There is no
  absolute RMS threshold: the relationship votes when its declared quadrant has the
  minimum RMS for that image pair (exact ties remain valid). Missing image evidence is
  neutral for legacy datasets. This propagates as a fixpoint, one newly canonicalized
  matrix per pass, so all relationships made available by a parent grid vote before its
  child grid can propagate.
- Contract v6 generalizes that edge as
  `(referenceContentId, levelDelta, rowOffset, columnOffset)`. If the reference cell is
  `(r,c)` and `s = 2^levelDelta`, the target is `(s*r + rowOffset,
  s*c + columnOffset)` at the deeper level. Offsets may lie outside `[0,s)`, which
  represents cousins across a border and lets level-13 islands link directly to a
  level-11 reference when level-12 sea tiles are absent.
- When session placements establish the scale of an imported hierarchy but a hierarchy root has
  only an ambiguous visual placement, frame camera vectors provide a geographic rigid-grid
  anchor. The quadtree uses the project's square 360-degree latitude model, not Web Mercator:
  longitude maps to columns, latitude maps to south-origin rows over `[-180,180]`, and only
  the middle half represents valid Earth latitudes. Camera offsets within four cells are
  clustered, but the winning cluster must still have a strict majority.
- A camera-anchored root owns its complete hierarchy component. `parentMatrixIndex` and v6
  `referenceContentId` edges discover all descendants; stale visual seeds for those layers
  are suppressed and every descendant with sufficient camera evidence receives its own
  rigid-grid anchor. This prevents unrelated continental imagery from outvoting an island
  across missing level-11/12 sea tiles.
- The absolute hierarchy scale uses a strict majority of resolved session layer offsets
  (`absoluteLevel - hierarchyLevel`). Unrelated deepest reference islands cannot assign
  an absolute level to an unresolved layer. Without a session scale, that layer remains
  unanchored rather than inheriting the reference maximum.
- Camera anchors below level 11 require at least three exact center-tile votes. Approximate
  per-frame fallback votes remain available for deep isolated components, but cannot move
  coarse continental layers when only weak geographic evidence is available.
- A contract-v3+ `parentGridTransform` propagates a containing-parent placement after,
  and only after, the referenced parent matrix has an accepted absolute grid anchor. It
  is a matrix-to-matrix transform, not a substitute for an observed per-tile uncle
  relationship. A top-level matrix merged into the reconstructed TOP layers is retained
  as placement support while any child still references it through this transform.
- If a tile has several `uncles` relationships that resolve to different candidate paths,
  the matrix grid votes for a common `(level,rowOffset,colOffset)`. A strict majority
  canonicalizes every tile in that rigid grid, correcting minority and individually
  ambiguous anchors. Direct quadkey/catalogue anchors take precedence over paths derived
  from relative `uncles`; otherwise several noisy relationships can outvote the only
  absolute observation and their error doubles at each deeper level. Longitude offsets
  are cyclic modulo `2^level`; for a full-world matrix,
  local column zero is canonicalized to the antimeridian before resolving child layers so
  an erroneous phase cannot double at every deeper level. If no complete
  `(level,rowOffset,colOffset)` tuple has a majority, the
  resolver may combine independent strict majorities for its three components. Sparse or
  closely contested relative anchors cannot place the grid. Instead, the exporter compares
  distributed native tiles against quadrants of already canonicalized layers from the same
  session; at least three individually unambiguous probes and a strict majority for one rigid
  offset are required before the descendant layer is placed.
- A tile with no way to reach an anchored path (directly or through `uncles`) is skipped.

Contract-v3+ hierarchy roots repaired by `31_matrixMerger` carry an explicit rigid parent
grid transform. Contract-v4 adds explicit uncle relationship kinds; contract-v6 adds
weighted grid offsets and `parentLevelDelta`. Older relationships
remain supported through legacy rigid-grid consensus. The exporter never
assumes that `matrix_<n+1>` is the child of
`matrix_<n>` merely because of folder order.

In contract-v6, an explicit parent emitted by 31 can also be the result of its last-resort
`BROKEN` resolver: only a structurally broken matrix with multiple incompatible alternatives
is eligible, and a complete four-child image mosaic must beat the other parent candidates by
the documented RMSE margin. Normal relationships are never visually re-anchored by 31. Once
exported, 32 treats that accepted `parentMatrixIndex` and `parentGridTransform` exactly like
other explicit rigid hierarchy evidence; it does not repeat the ambiguity decision.

Before writing any PNG, the exporter builds a manifest with one selected tile per full
path. A native local tile has documented priority over a derived TOP tile at the same
cell. Any other incompatible duplicate path fails the export before writing, so output
correctness no longer depends on layer iteration order.

When a reference pyramid is supplied, every preliminarily placed imported layer is also
checked against byte-identical reference tiles near its claimed footprint. Three or more
unique matches must form a strict majority for one rigid grid offset. That offset validates
the whole layer; if it differs from the relationship-derived offset, the exporter corrects
the layer before building the manifest. This catches phase errors that otherwise double at
successive levels while keeping unmatched or weakly supported layers on the conservative
structural path.

### Session-local export

`<inputFolder>/pyramidalImage` is created if missing. With
`--reference-pyramid <folder>`, levels `0..5` supply missing upper-tree entries and the
two deepest levels let external parent textures anchor a sparse child layer by exact
content hash. Current-session tiles override reference scaffold tiles at the same
quadkey. If holes also remove the immediate parent, conservative visual matching can
bridge up to three hierarchy levels; it requires a low-error, unique match and a strict
majority of grid-offset votes. Without that option no existing pyramid is read. A
re-export also regenerates the session output without reading its old PNGs back. Two counters are
accumulated in `pyramidalimageexporter.model.PyramidalImageWriteStatistics`
and printed to the console (via its `toString()`) once the export finishes:

- `new`: the slot did not exist yet and was written.
- `rewritten`: the slot existed (from a previous run over the same input folder) and was
  written again.

Before root-path resolution runs, the exporter also performs a content-hash anchoring
pass: if a `matrix_<n>/matrixLayer.json` tile uses a texture that is byte-for-byte
identical to an already catalogued image, its `id` is rewritten in memory and persisted
back to that `matrixLayer.json` as the resolved quadtree path. Hashes that occur at more
than one quadtree path are rejected as ambiguous. This is a real source-data side effect
of exporting, not just an in-memory optimization.

Combining this session pyramid with the pyramids of other capture sessions into one
consolidated pyramidal image — including any cross-session verification, conflict
handling or overwrite policy — is the responsibility of a separate, future program.

The selected layer is drawn as textured quads on plane `Z=0`, with frustum culling,
distance-based LOD (near: textured; far: untextured, 98% scale) and a FIFO GPU texture
memory budget, like `31_matrixMerger`.

When tile borders are shown (Vitral wires toggle, `F2`), the border color encodes the
texture source:

- White: the tile has a texture at native (full) resolution for its level.
- Green: the tile borrows a partial sub-rectangle from a parent/ancestor level image.

## Command-line options

- `<inputFolder>` (positional, required, the only positional argument): see Inputs.
- `--reference-pyramid <folder>`: read levels `0..5` plus the two deepest levels of an
  existing pyramid. The deepest levels form the anchoring catalogue; levels `0..5` are
  copied only as fallback entries when the current trace cannot reconstruct the delta's
  upper tree. Current-session tiles retain priority and the reference is never modified.
- `--export`: writes the pyramidal image quadtree to `<inputFolder>/pyramidalImage` and
  exits, with no GUI and no OpenGL/JOGL required (same operation as pressing `e`
  interactively).
- `--ofline` / `--offline`: loads all layers and renders the selected layer to a PNG
  snapshot without opening a window, using an orthographic top view that frames the
  whole matrix (all tiles textured, no culling/LOD).
- `--layer <i>`: 0-based index of the layer to render offline (default `0`; top-level
  layers `0..5` come first, then the imported `matrix_<n>` layers).
- `--width <px>` / `--height <px>`: offline snapshot size (default `1024x1024`).
- `--output <path>`: offline snapshot path (default
  `/tmp/pyramidalImageExporter_offline.png`).
- `--wires`: also draw tile borders in the offline snapshot (white = native resolution,
  green = borrowed from an ancestor image).
- `--rms-map`: overlays the relative relationship diagnostic. RMS values are ranked only
  within each parent/child layer pair: green to yellow denotes declared quadrants that are
  the minimum RMS (low to high pair percentile), while orange to red marks relationships
  for which another quadrant has a lower RMS. Tiles without comparable relationships are
  left uncolored.

The positional argument may appear in any order relative to the flags. If it is missing,
or an extra positional argument is passed (there is no destination parameter), an English
message explaining the problem is printed to stderr and the program exits with code `1`.

### Offline example (level-4 world map, 16x16 tiles)

```bash
gradle run --args="--offline <inputFolder> --layer 4 --output /tmp/level4.png"
```

For an imported relationship layer with the RMS overlay:

```bash
gradle run --args="--offline --rms-map <inputFolder> --layer 8 --output /tmp/rms-map.png"
```

## Notes for agentic coding agents

- `--offline` is a full headless renderer: it runs the whole import (including top-level
  reconstruction) and writes a PNG snapshot of one layer, so results can be verified
  without user interaction. On a machine without a display, run it under `xvfb-run -a`.
- Startup diagnostics on stdout are parseable: `TopLevelTilesReader: loaded strips=...`,
  `TopLevelMatrixRebuilder: level L matrix=SxS, nativeResolutionCells=..., derivedCells=...,
  emptyCells=...`, `Offline image written to: <path>`, and, after pressing `e` (or with
  `--export`), a per-layer placement report followed by
  `SessionPyramidalImageExportService: PyramidalImageWriteStatistics{new=..., rewritten=...}`
  and `SessionPyramidalImageExportService: Export complete: N tiles processed to
  <inputFolder>/pyramidalImage`.
- A missing `<inputFolder>`, an extra positional argument, or an unreadable
  `<inputFolder>` prints an English `ERROR: ...` message to stderr and exits with
  code `1`.

## Configuration

In `pyramidalimageexporter.config.Configuration`:

- `MAX_GPU_TEXTURE_MEMORY`: GPU texture memory limit.
- `MAX_TEXTURED_QUAD_DISTANCE`: distance threshold for using textures.
- `FAR_QUAD_SCALE`: scale of far (untextured) quads.
- `outputDirectory()`: root directory holding `topLevelTiles.json`.

## Package structure

- `io`: `matrixLayer.json`/`topLevelTiles.json` reading and in-place matrix-layer id
  rewrites after content-hash anchoring.
- `model`: matrix layers, tile coordinates, selection state, GPU texture budget, and
  `PyramidalImageWriteStatistics`.
- `processing/toplevels`: synthesis of top-level (0..5) matrix layers.
- `processing/uncles`: uncle relationship metadata, relative RMS analysis/color scores and
  `TileRootPathResolver`, which anchors tiles to a full quadtree path from the root.
- `processing/content`: content-hash anchoring against catalogued top-level images.
- `processing`: session-local pyramidal image export (`SessionPyramidalImageExportService`).
- `render`: JOGL renderer, culling, LOD and HUD.
- `gui`: keyboard/mouse handling.


For a newly captured region, the destination may lack intermediate ancestors.
The level-only delta helper preserves destination ancestors and supplies missing
ones from native 256×256 exported tiles. A missing ancestor can also be reconstructed from four complete finer children.
If neither native imagery nor a complete reconstruction exists, delta preparation
still fails. This avoids rejecting new northern coverage just
because its L9 parents have not previously been published.


Individual visual anchors reject tied best matches, including perfect zero-error
ties. Flat textures shared by multiple positions are ambiguous and must not be
accepted just because a guarded 0/0 ratio evaluates to zero. Whole-layer rigid
majority checks remain available; unrelated external textures cannot bypass this
by contributing several tied plausible matches.


Repeated flat-color visual comparisons reuse results only with identical parent
candidates, excluded layer, designated parent, level limits, dimensions and color.
The cache is cleared after each resolve call. Hit/miss counts are included in
the performance report; the geographic matching and tie rejection rules stay the same.
