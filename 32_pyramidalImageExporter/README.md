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

- `<inputFolder>` (positional, required): directory containing the `matrix_<n>` folders
  exported by `31_matrixMerger`, each with a `matrixLayer.json` and its tile textures. The
  top-level pyramid (levels `0..5`, see below) does not depend on this folder having any
  `matrix_<n>` subfolders, only `topLevelTiles.json` does; `./run.sh` defaults it to
  `/media/ramdisk/output`, the same folder `output.directory` points to.
- `<sessionPyramidalImageExportPath>` (positional, required): destination directory for
  the pyramidal image quadtree export triggered with the `e` key (see below), e.g.
  `/samples/datasets/googleEarth` (the default used by `./run.sh`). It does not need to
  exist beforehand; it is created on first export. This is an output-only folder — do not
  point it at `<inputFolder>` or at `output.directory`, both of which are read from, not
  written to.
- `topLevelTiles.json` read from the root of `output.directory` (configured in
  [src/main/resources/application.properties](src/main/resources/application.properties),
  default `/media/ramdisk/output`). This is where the actual source data (strips,
  appearances and their images) comes from.

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
gradle run --args="/media/ramdisk/output /samples/datasets/googleEarth"
```

or `./run.sh`, which forwards its own positional arguments to the same two parameters
(`./run.sh <inputFolder> <sessionPyramidalImageExportPath>`) and otherwise defaults to the
command above.

## Interactive usage guide

Program-specific keys (generic camera handling comes from Vitral and is not listed here):

| Key | Action |
|---|---|
| `1` / `2` | Select previous / next matrix layer |
| `t` | Toggle textured rendering |
| `e` | Export the pyramidal image quadtree to `<sessionPyramidalImageExportPath>` |
| `ESC` | Exit |

HUD:

- `Layer [1, 2]: i/N | frame <id> | Matrix: <rows>x<cols>`: selected layer and its size.
- `Toggle textures [t], Export [e], orbit camera with mouse, source: <inputFolder>`.
- `Export destination: <sessionPyramidalImageExportPath> | <last export status>`.
- When the bounding-volume display mode is enabled, each visible tile is annotated with
  its id and `(i, j)` cell coordinates.

## Exporting the pyramidal image

Pressing `e` writes every tile that can be anchored to an absolute quadtree path to
`<sessionPyramidalImageExportPath>` as a quadtree of PNG files, matching the pyramid
already drawn in the interactive viewer:

- The root tile is `0.png`, directly in the destination directory.
- Its 4 children are folders `00`, `01`, `02`, `03`; each one holds its own tile image
  (e.g. `00/00.png`) and, recursively, its own 4 child folders (e.g. `00/000/`, `00/001/`,
  `00/002/`, `00/003/`), one level deeper per quadtree level.
- Each tile image is a `256x256` PNG cropped (nearest-neighbor) from its source texture's
  sub-rectangle, the same sub-rectangle used to texture that tile on screen.

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
  already anchored: the uncle is the immediately coarser tile that contains this tile in
  one of its 4 quadrants, `direction` names that quadrant, and the tile's path is the
  uncle's path with that quadrant digit appended. This propagates as a fixpoint, so a
  chain of several uncle hops resolves one hop per pass.
- If a tile has several `uncles` relationships that resolve to different candidate paths,
  the tile is ambiguous and is permanently discarded (never exported); the export log
  reports how many tiles were discarded this way.
- A tile with no way to reach an anchored path (directly or through `uncles`) is skipped.

### Additive-destructive export

The destination directory is treated as additive-destructive: if it does not exist yet,
it is created and filled as described above. If it already exists, no extra validation of
its prior contents is performed (the caller is responsible for the integrity of whatever
is already there); each tile about to be written is instead compared against whatever PNG
already occupies its slot:

- No existing file at that slot: the new image is written (a "new" tile).
- An existing file with pixel-identical content: the existing file is left untouched (an
  "ignored" tile).
- An existing file with different content: it is overwritten with the new image (an
  "updated" tile).

These three counters are accumulated in
`pyramidalimageexporter.common.statistics.PyramidalImageAdditionStatistics` and printed to
the console (via its `toString()`) once the export finishes.

The selected layer is drawn as textured quads on plane `Z=0`, with frustum culling,
distance-based LOD (near: textured; far: untextured, 98% scale) and a FIFO GPU texture
memory budget, like `31_matrixMerger`.

When tile borders are shown (Vitral wires toggle, `F2`), the border color encodes the
texture source:

- White: the tile has a texture at native (full) resolution for its level.
- Green: the tile borrows a partial sub-rectangle from a parent/ancestor level image.

## Command-line options

- `<inputFolder>` (positional, required): see Inputs.
- `<sessionPyramidalImageExportPath>` (positional, required): see Inputs.
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

Both positional arguments may appear in either order relative to the flags. If either is
missing, an English usage message is printed to stderr and the program exits with code `1`.

### Offline example (level-4 world map, 16x16 tiles)

```bash
gradle run --args="--offline /media/ramdisk/output /samples/datasets/googleEarth --layer 4 --output /tmp/level4.png"
```

## Notes for agentic coding agents

- `--offline` is a full headless renderer: it runs the whole import (including top-level
  reconstruction) and writes a PNG snapshot of one layer, so results can be verified
  without user interaction. On a machine without a display, run it under `xvfb-run -a`.
- Startup diagnostics on stdout are parseable: `TopLevelTilesReader: loaded strips=...`,
  `TopLevelsMatricesImporter: level L matrix=SxS, nativeResolutionCells=..., derivedCells=...,
  emptyCells=...`, `Offline image written to: <path>`, and, after pressing `e`,
  `PyramidalImageExporter: PyramidalImageAdditionStatistics{new=..., ignoredExisting=...,
  updated=...}` followed by `PyramidalImageExporter: Export complete: N tiles processed to
  <path>`.
- Missing required positional arguments prints a `Usage: ...` message to stderr and exits
  with code `1`. Missing or unreadable `<inputFolder>` exits with code `1` and an `ERROR:`
  message on stderr.

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
- `common/statistics`: `PyramidalImageAdditionStatistics`, the new/ignored/updated tile
  counters accumulated during an additive-destructive export.
- `render`: JOGL renderer, culling, LOD and HUD.
- `gui`: keyboard/mouse handling.
