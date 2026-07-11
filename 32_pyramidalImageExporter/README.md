# 32_pyramidalImageExporter

`32_pyramidalImageExporter` is the last stage of the pipeline: it imports the consolidated
layer matrices exported by `31_matrixMerger`, plus the global top-level tile data produced
by `22_dumpAnalyzer`, and visualizes them as the layers of the future quadtree "Pyramidal
Image".

## Status

Work in progress. The program currently loads and visualizes all layers, but it does not
yet write the final pyramidal image. Layers whose extent matches a fully covered quad are
in good shape; synthesizing complete images for the topmost quadtree levels (0 and up),
which no single quad covers, is the pending goal.

## Inputs

- `<inputFolder>` (positional, required): directory containing the `matrix_<n>` folders
  exported by `31_matrixMerger`, each with a `matrixLayer.json` and its tile textures.
  Example: `/samples/datasets/googleEarth/take01` (used by `./run.sh`).
- `topLevelTiles.json` read from the root of `output.directory` (configured in
  [src/main/resources/application.properties](src/main/resources/application.properties),
  default `/media/ramdisk/output`). From it, `TopLevelsMatricesImporter` synthesizes one
  matrix layer per quadtree level `0..5` (sizes `1x1` to `32x32`), mapping each tile's
  path-from-root to row/column cells. These synthetic top-level layers are prepended to
  the imported `matrix_<n>` layers.

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

## Command-line options

- `<inputFolder>` (positional, required): see Inputs.
- `--ofline` / `--offline`: loads all layers, prints how many were loaded, and exits
  without opening a window. No image is exported yet.

## Notes for agentic coding agents

- The only headless capability today is `--offline`, which just validates that the input
  can be loaded (`Offline mode loaded N matrix layers.`). There is no offline image
  renderer yet, unlike `22`/`23`; that is part of the pending work.
- Startup diagnostics on stdout are parseable: `TopLevelTilesReader: loaded strips=...`,
  `TopLevelsMatricesImporter: level L matrix=SxS, occupiedCells=...`.
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
