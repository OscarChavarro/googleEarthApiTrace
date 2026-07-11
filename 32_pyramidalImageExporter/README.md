# 32_pyramidalImageExporter

`32_pyramidalImageExporter` is the last stage of the pipeline: it imports the consolidated
layer matrices exported by `31_matrixMerger`, plus the global top-level tile data produced
by `22_dumpAnalyzer`, and visualizes them as the layers of the future quadtree "Pyramidal
Image".

## Status

The top quadtree levels (`0..5`) are now fully reconstructed: every cell of every
top-level layer is textured and the layer visualization corresponds to the map of planet
Earth. What remains pending is anchoring the deeper `matrix_<n>` layers into absolute
quadtree coordinates and writing the final pyramidal image to disk.

## Inputs

- `<inputFolder>` (positional, required): directory containing the `matrix_<n>` folders
  exported by `31_matrixMerger`, each with a `matrixLayer.json` and its tile textures.
  Example: `/samples/datasets/googleEarth/take01` (used by `./run.sh`).
- `topLevelTiles.json` read from the root of `output.directory` (configured in
  [src/main/resources/application.properties](src/main/resources/application.properties),
  default `/media/ramdisk/output`).

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

The `pyramidal.image.directory` property is reserved for the final pyramid output
location and is not used by the code yet.

## Execution

From this directory:

```bash
gradle run --args="/samples/datasets/googleEarth/take01"
```

or `./run.sh`.

## Interactive usage guide

Program-specific keys (generic camera handling comes from Vitral and is not listed here):

| Key | Action |
|---|---|
| `1` / `2` | Select previous / next matrix layer |
| `t` | Toggle textured rendering |
| `ESC` | Exit |

HUD:

- `Layer [1, 2]: i/N | frame <id> | Matrix: <rows>x<cols>`: selected layer and its size.
- `Toggle textures [t], orbit camera with mouse, source: <inputFolder>`.
- When the bounding-volume display mode is enabled, each visible tile is annotated with
  its id and `(i, j)` cell coordinates.

The selected layer is drawn as textured quads on plane `Z=0`, with frustum culling,
distance-based LOD (near: textured; far: untextured, 98% scale) and a FIFO GPU texture
memory budget, like `31_matrixMerger`.

When tile borders are shown (Vitral wires toggle, `F2`), the border color encodes the
texture source:

- White: the tile has a texture at native (full) resolution for its level.
- Green: the tile borrows a partial sub-rectangle from a parent/ancestor level image.

## Command-line options

- `<inputFolder>` (positional, required): see Inputs.
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

### Offline example (level-4 world map, 16x16 tiles)

```bash
gradle run --args="--offline /samples/datasets/googleEarth/take01 --layer 4 --output /tmp/level4.png"
```

## Notes for agentic coding agents

- `--offline` is a full headless renderer: it runs the whole import (including top-level
  reconstruction) and writes a PNG snapshot of one layer, so results can be verified
  without user interaction. On a machine without a display, run it under `xvfb-run -a`.
- Startup diagnostics on stdout are parseable: `TopLevelTilesReader: loaded strips=...`,
  `TopLevelsMatricesImporter: level L matrix=SxS, nativeResolutionCells=..., derivedCells=...,
  emptyCells=...`, and `Offline image written to: <path>`.
- Missing or unreadable `<inputFolder>` exits with code `1` and an `ERROR:` message on
  stderr.

## Configuration

In `pyramidalimageexporter.config.Configuration`:

- `MAX_GPU_TEXTURE_MEMORY`: GPU texture memory limit.
- `MAX_TEXTURED_QUAD_DISTANCE`: distance threshold for using textures.
- `FAR_QUAD_SCALE`: scale of far (untextured) quads.
- `outputDirectory()`: root directory holding `topLevelTiles.json`.

## Package structure

- `io`: `matrixLayer.json` and `topLevelTiles.json` reading.
- `model`: matrix layers, tile coordinates, selection state, GPU texture budget.
- `processing/toplevels`: synthesis of top-level (0..5) matrix layers.
- `processing/uncles`: uncle relationship metadata support.
- `render`: JOGL renderer, culling, LOD and HUD.
- `gui`: keyboard/mouse handling.
