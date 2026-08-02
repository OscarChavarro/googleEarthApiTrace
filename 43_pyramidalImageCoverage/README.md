# 43_pyramidalImageCoverage

Pure Java 17 AWT/Swing viewer for inspecting which tiles exist at every depth of a
folder-based pyramidal image. It intentionally does not use JOGL: every output pixel is
drawn by Java2D with nearest-neighbor sampling and Java UI scaling fixed to `1.0`.

## Input and execution

The only argument is a pyramidal image folder. It must contain `0.png`; child tiles use
the per-digit directory layout produced by `32_pyramidalImageExporter` (for example,
`0/00.png` and `0/2/002.png`; tile `0303301` lives at `3/0/3/3/0/1/0303301.png`). The
scanner also accepts the previous cumulative-folder layout during migration.

```bash
cd 43_pyramidalImageCoverage
./run.sh /samples/datasets/googleEarth/sesion1Madrid
```

or:

```bash
gradle run --args="/samples/datasets/googleEarth/sesion1Madrid"
```

## Controls

| Key | Action |
|---|---|
| `1` | Previous quadtree depth |
| `2` | Next available quadtree depth |
| `F` | Toggle borderless full screen |
| `ESC` | Exit |

Left-clicking any matrix cell selects it as the primary tile, including a red cell with
missing data. Right-clicking selects it as the secondary tile. Primary tiles use a green
border and secondary tiles use a yellow border. Clicking the same tile again with the
corresponding button clears that selection; clicking outside the matrix clears only the
selection associated with that button.

The HUD shows both centers in decimal degrees. When both tiles are selected it also shows
`deltaLat` and `deltaLon` as `secondary - primary`, plus their great-circle distance in
kilometers. These values can be used directly to derive a region of interest expressed
as `(x0, y0), (dx, dy)`.

The selected depth starts at `0` and is clamped to the deepest level containing data.
The English HUD in the upper-right corner reports depth, matrix dimensions and active
LOD mode.

## Pixel LOD modes

Through depth 9, the complete level is laid out as a square `2^depth x 2^depth` tile
matrix, north up. From depth 10 onward, the viewer crops the display to the smallest
tile-aligned rectangle containing all tiles available at that depth, centers it, and
chooses the largest LOD that fits the viewport. This makes a permanently local area of
interest occupy as much of the screen as possible instead of representing it as a few
pixels inside the whole-world matrix. The HUD continues to report the global matrix size
and additionally reports the focused rectangle size.

Cells backed by a tile PNG at the selected depth show the available image data. Missing
cells inside the displayed extent are painted red. Scaled cells continue to obtain their
pixels from the appropriate available ancestor tile.

The square quadtree has a nominal vertical extent of 360 degrees, while valid Earth
latitudes are limited to `[-90, 90]`. Consequently, level 0 uses the middle half of its
image, each level-1 tile uses only half of its height, and levels 2 and deeper use only
the central rows. Rows and image areas outside the valid latitude range remain dark,
are never marked as missing, and cannot be selected.

1. **Native/scaled:** the viewer tries image sides `256`, `128`, `64`, `32`, `16`, `8`,
   `4` and `2` pixels, in that order, and selects the largest one for which the displayed
   extent fits. Every image is surrounded by one unpainted background pixel on each side,
   so tile boundaries remain visible. Sampling uses nearest-neighbor filtering.
2. **Coverage:** if even the `2x2` image plus border does not fit, each matrix cell is
   one pixel; missing cells are red. A level-0 image supplies colors
   through level 8; level 1 supplies level 9, and so on. When the complete one-pixel
   matrix does not fit, both scrollbars are enabled.

When scrollbars first appear at a depth, the viewport is initialized over the center of
the displayed data. This keeps sparse captured regions in view instead of blindly
centering the complete world matrix.

AWT key events are converted through Vitral's `AwtSystem`. The command mapping itself
operates on Vitral events and action interfaces, without depending on AWT or Swing.

Images are loaded lazily into a bounded LRU cache. Scanning startup indexes paths and
coordinates but does not decode every PNG.

## Tests

```bash
gradle test
```
