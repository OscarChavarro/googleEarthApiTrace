# 42_pyramidalImageMerger

`42_pyramidalImageMerger` is an interactive AWT/Swing + JOGL validator for merging two
folder-based pyramidal image trees. It is based on `41_planetViewer`, but it always starts
with exactly two side-by-side views: destination on the left and delta on the right.

## Status

Loads exactly two pyramidal image trees, keeps both views synchronized with one shared
camera, and validates merge conflicts in memory when the user presses `m`. Conflicting tile
ids are outlined in red in both views. A mergeable state is reported in green in the HUD.

## Inputs

- `<destinationPyramidalImageFolder>`: large target pyramidal image tree.
- `<deltaPyramidalImageFolder>`: usually smaller pyramidal image tree to be merged into the
  destination.

Both must be valid folder-based pyramidal images with `0.png` at the root. Deeper tiles
use one directory per quadrant digit after that root marker; for example, tile `0303301`
lives at `3/0/3/3/0/1/0303301.png`. The reader also accepts the previous cumulative-folder
layout during migration.

## Execution

From this directory:

```bash
gradle run --args="/samples/datasets/googleEarth/topLevel /samples/datasets/googleEarth/secondLevelStep1"
```

or `./run.sh`, which defaults to those same sample folders when called without arguments.

Headless merge validation/copy is available with `--offline`:

```bash
./run.sh --offline /samples/datasets/googleEarth/topLevel /tmp/matrix/pyramidalImage
```

To inspect conflicts without modifying the destination tree, use `--dry-run`:

```bash
./run.sh --dry-run /samples/datasets/googleEarth/topLevel /tmp/matrix/pyramidalImage
```

## Interactive usage guide

Program-specific keys (generic camera handling comes from Vitral's
`CameraControllerAquynza` and is not listed here, except for the zoom keys, which are
implemented by this application, not the controller):

| Key | Action |
|---|---|
| arrows / mouse drag | Move camera (`CameraControllerAquynza`) |
| mouse wheel, `z` / `Z` | Logarithmic zoom out / in towards the z = 0 plane |
| `r` / `R` | Reset the active camera (top view / oblique view) |
| `m` | Run the in-memory merge validation |
| `F1`..`F9` | Vitral `RendererConfigurationController` (wires, etc.) |
| `ESC` | Exit |

Both views always share the same camera, so every movement is replicated on both trees.

## Arbitrary zoom (Power Scaled Coordinates)

Ported from the old `vsdk_aquynzaScales_stage05_trackerSupport` prototype's
`AquynzaUniverse`: the main view's camera z is kept inside `[1, 10]` every frame
(`planetviewer.processing.PscUpdater`); whenever it would leave that band, the camera
position is rescaled by a power of 10 and an integer `currentPSC` counter is
incremented/decremented instead. Every drawn tile is scaled by
`relativeScale(psc) = 10^(psc - currentPSC - 1)`, so the displayed geometry never needs
coordinates outside a small, precision-safe range no matter how deep the zoom goes. The
`z`/`Z` keys and the mouse wheel drive this zoom logarithmically
(`planetviewer.processing.CameraZoom`), decelerating smoothly as the camera nears the plane.
Past the deepest tile level, tiles keep showing the nearest loaded ancestor's texture
(magnified) instead of going blank.

## Merge validation

When `m` is pressed, the program recursively traverses the delta tree. For every delta tile:

- if the corresponding destination tile does not exist, the tile is mergeable;
- if both files exist and are byte-identical, the tile is mergeable;
- if both files differ, ImageMagick rescales the higher-resolution candidate to the lower
  resolution and accepts a normalized RMSE up to 3%; these matches are outlined in green;
- if the visual comparison passes, the higher-resolution tile is kept in destination;
- if the visual comparison exceeds the threshold, that tile id becomes a conflict.

New tiles are written using the destination's folder layout. The current per-quadrant-digit
layout is preferred when a destination already contains a mixture of current and legacy
branches. After rescanning the destination, the merge is reported as completed only if every
delta tile is visible at its expected quadtree id.

Conflicts are reported in the HUD and drawn as red borders in both views at the same tile
location.

Moving the mouse over either viewport prints the path of the projected tile image in the HUD.

## Command-line contract

The program requires exactly two positional arguments, with optional `--offline` or
`--dry-run`. Any other invocation exits immediately with an English usage message.

- Without flags, it opens the interactive JOGL viewer.
- `--offline` executes the same validation/merge operation as the interactive `m` key.
- `--dry-run` executes the validation and prints conflict details without copying or
  replacing destination tiles.

## Notes for agentic coding agents

- `--offline` is a headless merge operation: it runs the folder scan, validates conflicts,
  and copies/replaces tiles when the merge is possible.
- Startup diagnostics on stdout are parseable: `Loaded destination pyramidal image
  <folder>: <n> tiles, height <h>` and `Loaded delta pyramidal image <folder>:
  <n> tiles, height <h>`.
- An invalid pyramidal image folder (missing `0.png`) prints an `ERROR:` message on stderr
  and is skipped, it does not stop the program; zero valid folders is a valid empty scene.
- `/samples/datasets/googleEarth` is input/read-only for this application.

## Configuration

In `planetviewer.config.Configuration`:

- `MAX_GPU_TEXTURE_MEMORY`: GPU texture memory budget (FIFO eviction beyond this).
- `MAX_RAM_TILE_CACHE_BYTES`: RAM budget for background-decoded tiles awaiting GPU upload.
- `SCREEN_AREA_SUBDIVISION_THRESHOLD`: projected-viewport-area LOD subdivision threshold.
- `defaultDatasetDirectory()`: starting directory for the `l` key's directory chooser.

## Package structure

- `options`: command-line argument parsing.
- `config`: tunables and `application.properties` reading.
- `logger`: minimal prefixed console logging.
- `model`: quadtree nodes, pyramidal images and instances, the image stack, PSC state.
- `io`: folder-structure scanning and background tile PNG decoding.
- `processing`: GL-independent quadtree LOD/cull selection, PSC update, camera zoom math.
- `render`: JOGL renderer, texture cache, views and their layout, HUD.
- `gui`: keyboard/mouse handling, the load-image dialog.
- `animation`: repaint coalescing while background tile loads are pending.
