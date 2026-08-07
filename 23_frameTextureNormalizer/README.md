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
   neighbors. A singleton referenced by a v6 cross-level relationship is retained as a
   visualization anchor even though it is not inserted into the same-level matrix.
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

Texture normalization changes only the image path. Native tile IDs and cross-level
relationships read from `frame.json` are preserved. Compact `relationshipGeometries` let
the viewer paint isolated coarser references, such as level 11 beside a level-13 island.
A canonical texture-derived export ID
is used only when that texture identifies a single tile occurrence in the frame; repeated
pixel-identical images (for example, blank ocean tiles) retain their native scoped IDs.
Matrix layout never fills a missing neighbor relationship.

Frame deduplication merges relationship metadata from every duplicate occurrence into the
retained representative. Before writing, an external `referenceContentId` is changed to a
texture-derived canonical id only when that exact id is present in the exported tile set;
relationships that become equal after this canonicalization are collapsed. This prevents a
later island frame from losing its ancestor edge merely because an earlier frame supplied the
same matrix tile ids.

`matrix.json` uses contract version 6. New relationship entries contain
`referenceContentId`, `levelDelta`, `rowOffset` and `columnOffset`; v3-v5 entries using
`uncleContentId`, `direction` and `relationshipKind` remain readable.

## Requirements

- Java 17
- Gradle
- Vitral artifacts available (same dependency approach as `dumpAnalyzer`)

## Run

From this directory:

```bash
gradle run
```

or `./run.sh` for the default interactive launch with no extra CLI arguments.

## Interactive usage guide

Program-specific keys (generic camera handling comes from Vitral and is not listed here):

| Key | Action |
|---|---|
| `1` / `2` | Select previous / next frame |
| `3` / `4` | Select previous / next tile |
| Left click | Toggle the clicked tile selection across frames with the same texture |
| `Shift` + left click | Select the connected tile component containing the clicked tile, across frames with the same textures |
| `Delete` | Delete all selected tiles in memory; affected `frame.json` files are written once on exit |
| `c` | Mark the selected tiles as west cutters (persisted to `westCutters.json`) |
| `t` | Toggle textured rendering |
| `ESC` | Exit |

The HUD shows the selected frame (`Frame [1,2]`), selected tile (`Tile [3,4]`), the
texture id of the selected tile, and — when a tile is selected — the
`West cut selected tiles [c]` action hint.

## CLI options

- `--offline`: processes the loaded frame range, writes the normalized matrices and exits
  without opening a window or rendering PNG files.
- `--start-frame <id>` or `--start-frame=<id>`: minimum frame id to load (inclusive).
- `--end-frame <id>` or `--end-frame=<id>`: maximum frame id to load (inclusive). When
  omitted, the last frame id found in the data directory is used.
- `--debug-matrix`: enables verbose matrix-assembly debugging output.
- `--debug-frame=<id>`: restricts matrix debugging output to one frame.

### Offline example (frame 150)

```bash
gradle run --args="--offline --start-frame 150 --end-frame 150"
```

## Notes for agentic coding agents

- The whole normalization pipeline (steps 1-6 above, including `matrix.json` export)
  runs before the GUI opens, so `--offline` is the way to drive this program
  non-interactively: it processes the data, writes the exported matrices and exits
  without rendering frames.
- `--debug-frame` currently supports only the `--flag=value` form; `--start-frame` and
  `--end-frame` accept both spaced and `=` forms.
- Use `--start-frame`/`--end-frame` to bound the working set for fast iteration, and
  `--offline --start-frame N --end-frame N` to process a single frame.
