# FrameTextureNormalizer

`FrameTextureNormalizer` is a Java 17 + Gradle utility that consumes preprocessed frame data
from the directory configured as `output.directory` in
[src/main/resources/application.properties](src/main/resources/application.properties)
(default: `/media/ramdisk/output`).

## Input

This program expects input produced previously by:

- `tracer` (texture image exports as `.png` and trace artifacts)
- `dumpAnalyzer` (per-frame `frame.json` files with tile and neighbor metadata)

Expected layout example:

- `/media/ramdisk/output/00003/frame.json`
- `/media/ramdisk/output/00003/*.png`

## Normalization pipeline

On every run (interactive or offline) the program executes this pipeline:

1. Loads traced frames, filtering tiles by connected components and geometric null
   neighbors, and dropping frames with too few tiles.
2. Verifies/creates SHA-256 signature files for tile textures.
3. Builds (or loads a cached) duplicated-texture filename mapping, grouping repeated
   texture contents across frames.
4. Normalizes tile textures and converts each frame's tile set into a matrix
   representation (`TileMatrix`), discarding frames with matrix-assembly errors.
5. Exports one deduplicated matrix per frame as `matrix.json` inside each frame folder,
   removing obsolete `matrix.json`/`matrix.txt` files from discarded frames.
6. Restores previously marked west-cutter tiles from `westCutters.json` (at the root of
   the data directory) for the interactive editor.

The exported `matrix.json` files and `westCutters.json` are the input of `31_matrixMerger`.

## Requirements

- Java 17
- Gradle
- Vitral artifacts available (same dependency approach as `dumpAnalyzer`)

## Run

From this directory:

```bash
gradle run
```

or `./run.sh`.

## Interactive usage guide

Program-specific keys (generic camera handling comes from Vitral and is not listed here):

| Key | Action |
|---|---|
| `1` / `2` | Select previous / next frame |
| `3` / `4` | Select previous / next tile |
| `c` | Mark the selected tiles as west cutters (persisted to `westCutters.json`) |
| `t` | Toggle textured rendering |
| `ESC` | Exit |

The HUD shows the selected frame (`Frame [1,2]`), selected tile (`Tile [3,4]`), the
texture id of the selected tile, and — when a tile is selected — the
`West cut selected tiles [c]` action hint.

## CLI options

- `--offline`: renders the loaded frame range to image file(s) and exits (no window).
- `--start-frame <id>` or `--start-frame=<id>`: minimum frame id to load (inclusive).
- `--end-frame <id>` or `--end-frame=<id>`: maximum frame id to load (inclusive). When
  omitted, the last frame id found in the data directory is used.
- `--width=<px>` and `--height=<px>`: output image size in offline mode (default 1280x720).
- `--output=<path>`: output image path in offline mode (default
  `/tmp/frameTextureNormalizer_offline.png`). When several frames are in range, one
  image per frame is generated from this base path.
- `--debug-matrix`: enables verbose matrix-assembly debugging output.
- `--debug-frame=<id>`: restricts matrix debugging output to one frame.

### Offline example (frame 150)

```bash
gradle run --args="--offline --start-frame 150 --end-frame 150 --output=/tmp/frame150.png"
```

## Notes for agentic coding agents

- The whole normalization pipeline (steps 1-6 above, including `matrix.json` export)
  runs before the GUI opens, so `--offline` is the way to drive this program
  non-interactively: it processes the data and produces PNG snapshot(s) as a visual
  verification artifact.
- Use `--start-frame`/`--end-frame` to bound the working set for fast iteration, and
  `--offline --start-frame N --end-frame N --output=/tmp/frameN.png` to render a single
  frame for inspection.
- If no graphics system is available, offline rendering degrades to a warning message,
  but data-processing side effects (exported matrices, signature files, west-cutter
  cache) still happen.
