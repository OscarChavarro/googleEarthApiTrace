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

Both must be valid folder-based pyramidal images with `0.png` at the root.

## Execution

From this directory:

```bash
gradle run --args="/samples/datasets/googleEarth/topLevel /samples/datasets/googleEarth/secondLevelStep1"
```

or `./run.sh`, which defaults to those same sample folders when called without arguments.

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
- if both files exist and differ, that tile id becomes a conflict.

Conflicts are reported in the HUD and drawn as red borders in both views at the same tile
location.

## Command-line contract

The program requires exactly two positional arguments. Any other invocation exits immediately
with an English usage message.

## Notes for agentic coding agents

- `--offline` is a full headless renderer: it runs the whole folder scan and writes a PNG
  snapshot of the stack, so results can be verified without user interaction. On a machine
  without a display, run it under `xvfb-run -a`.
- Startup diagnostics on stdout are parseable: `PlanetViewer: loaded pyramidal image
  <folder>: <n> tiles, height <h>` per loaded image, and `Offline image written to: <path>`.
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
