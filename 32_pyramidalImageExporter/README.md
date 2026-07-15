# 32_pyramidalImageExporter

`32_pyramidalImageExporter` is the last stage of the pipeline: it imports the consolidated
layer matrices exported by `31_matrixMerger`, plus the global top-level tile data produced
by `22_dumpAnalyzer`, and visualizes them as the layers of the future quadtree "Pyramidal
Image".

## Status

The top quadtree levels (`0..5`) are now fully reconstructed: every cell of every
top-level layer is textured and the layer visualization corresponds to the map of planet
Earth. Deeper `matrix_<n>` tiles are exported too whenever they can be anchored to an
absolute quadtree path, either directly or through their `uncles` relationships (see
"Exporting the pyramidal image" below). The pyramid can be exported to disk as a quadtree
of PNG files with the `e` key.

## Inputs

- `<inputFolder>` (positional, required, no default): directory containing the
  `matrix_<n>` folders exported by `31_matrixMerger`, each with a `matrixLayer.json` and
  its tile textures. Contract-v2 files also contain `hierarchyLevel`,
  `parentMatrixIndex`, and full `hierarchyRelationshipsByTileId`; these relationships are
  merged back into the tiles before placement. The top-level pyramid (levels `0..5`, see below) does not depend on
  this folder having any `matrix_<n>` subfolders, only `topLevelTiles.json` does. Neither
  the program nor `./run.sh` assumes a default: if this argument is missing, both exit
  with code `1` and an English error message explaining that it must point to
  `31_matrixMerger`'s export folder.
- `topLevelTiles.json` read from the root of `output.directory` (configured in
  [src/main/resources/application.properties](src/main/resources/application.properties),
  default `/media/ramdisk/output`). This is where the actual source data (strips,
  appearances and their images) comes from.

There is no destination argument: the session's pyramidal image quadtree is always
written inside the input folder itself, to `<inputFolder>/pyramidalImage` (created on
first export). This tool is strictly **session-local**: it never reads tiles from any
existing pyramidal image (not even its own previous export) and never writes anywhere
outside `<inputFolder>`. Merging the pyramidal images of different capture sessions into
one consolidated pyramid is the responsibility of a separate, future program. Passing a
second positional argument (the old destination parameter) is an error: the program
prints an English message explaining this contract and exits with code `1`.

### Top-level reconstruction from texCoords

`TopLevelsMatricesImporter` rebuilds levels `0..5` (sizes `1x1` to `32x32`) from the
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
| `e` | Export the pyramidal image quadtree to `<inputFolder>/pyramidalImage` |
| `ESC` | Exit |

HUD:

- `Layer [1, 2]: i/N | frame <id> | Matrix: <rows>x<cols>`: selected layer and its size.
- `Toggle textures [t], Export [e], orbit camera with mouse, source: <inputFolder>`.
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
- `topLevelTiles.json` is still required to recover those absolute quadkeys. If it is missing
  and imported matrix ids cannot otherwise be anchored, export fails without clearing the
  previous pyramid instead of reporting a successful zero-tile export.

This replaces the earlier `matrix_<n>/matrixLayer.json` copy-based layout used by
`31_matrixMerger`'s `ResultsExporter`: the pyramidal image is written directly as a
quadtree of images, with no JSON manifest.

### Which tiles are exported

A tile is exportable only if `pyramidalimageexporter.processing.uncles.RootPathResolver`
can anchor it to a full path from the root (a string of quadrant digits, e.g. `"0021"`):

- Tiles whose own `id` already is such a path (every `topLevel_matrix_*` tile, by
  construction) are anchored directly.
- Any other tile can still be anchored if one of its `uncles` relationships
  (`ToUncleRelationship(direction, uncleContentId)`) points, by id, to a tile that is
  already anchored. The uncle is the immediately coarser adjacent tile; `direction`
  identifies the half of its border touched by the finer tile. Resolution crosses that
  border to the neighboring parent cell and selects the corresponding child quadrant.
  This propagates as a fixpoint, so a chain of several uncle hops resolves one hop per
  pass.
- If a tile has several `uncles` relationships that resolve to different candidate paths,
  the matrix grid votes for a common `(level,rowOffset,colOffset)`. A strict majority
  canonicalizes every tile in that rigid grid, correcting minority and individually
  ambiguous anchors. Longitude offsets are cyclic modulo `2^level`; for a full-world matrix,
  local column zero is canonicalized to the antimeridian before resolving child layers so
  an erroneous phase cannot double at every deeper level. If no complete
  `(level,rowOffset,colOffset)` tuple has a majority, the
  resolver may combine independent strict majorities for its three components; without
  those component majorities the matrix stays unresolved.
- A tile with no way to reach an anchored path (directly or through `uncles`) is skipped.

Contract-v2 hierarchy roots repaired by `31_matrixMerger` carry explicit inferred uncle
relationships. The exporter never assumes that `matrix_<n+1>` is the child of
`matrix_<n>` merely because of folder order.

Before writing any PNG, the exporter builds a manifest with one selected tile per full
path. A native local tile has documented priority over a derived TOP tile at the same
cell. Any other incompatible duplicate path fails the export before writing, so output
correctness no longer depends on layer iteration order.

### Session-local export

The export is strictly session-local: `<inputFolder>/pyramidalImage` is created if
missing, and every placed tile is simply written there from this session's data. No
existing pyramidal image is ever read — not the tiles of a shared/consolidated pyramid
(the tool has no access to one), and not even the PNGs left by a previous export of the
same session (a re-export regenerates them without reading them back). Two counters are
accumulated in `pyramidalimageexporter.common.statistics.PyramidalImageAdditionStatistics`
and printed to the console (via its `toString()`) once the export finishes:

- `new`: the slot did not exist yet and was written.
- `rewritten`: the slot existed (from a previous run over the same input folder) and was
  written again.

Before root-path resolution runs, the exporter also performs a content-hash anchoring
pass: if a `matrix_<n>/matrixLayer.json` tile uses a texture that is byte-for-byte
identical to an already catalogued top-level image, its `id` is rewritten in memory and
persisted back to that `matrixLayer.json` as the resolved quadtree path. This is a real
source-data side effect of exporting, not just an in-memory optimization.

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

The positional argument may appear in any order relative to the flags. If it is missing,
or an extra positional argument is passed (there is no destination parameter), an English
message explaining the problem is printed to stderr and the program exits with code `1`.

### Offline example (level-4 world map, 16x16 tiles)

```bash
gradle run --args="--offline <inputFolder> --layer 4 --output /tmp/level4.png"
```

## Notes for agentic coding agents

- `--offline` is a full headless renderer: it runs the whole import (including top-level
  reconstruction) and writes a PNG snapshot of one layer, so results can be verified
  without user interaction. On a machine without a display, run it under `xvfb-run -a`.
- Startup diagnostics on stdout are parseable: `TopLevelTilesReader: loaded strips=...`,
  `TopLevelsMatricesImporter: level L matrix=SxS, nativeResolutionCells=..., derivedCells=...,
  emptyCells=...`, `Offline image written to: <path>`, and, after pressing `e` (or with
  `--export`), a per-layer placement report followed by
  `PyramidalImageExporter: PyramidalImageAdditionStatistics{new=..., rewritten=...}` and
  `PyramidalImageExporter: Export complete: N tiles processed to <inputFolder>/pyramidalImage`.
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

- `io`: `matrixLayer.json` and `topLevelTiles.json` reading, and the pyramidal image
  quadtree writer (`PyramidalImageExporter`).
- `model`: matrix layers, tile coordinates, selection state, GPU texture budget.
- `processing/toplevels`: synthesis of top-level (0..5) matrix layers.
- `processing/uncles`: uncle relationship metadata and `RootPathResolver`, which anchors
  tiles to a full quadtree path from the root.
- `common/statistics`: `PyramidalImageAdditionStatistics`, the new/rewritten tile
  counters accumulated during a session-local export.
- `render`: JOGL renderer, culling, LOD and HUD.
- `gui`: keyboard/mouse handling.
