# 43_pyramidalImageCoverage

Pure Java 17 AWT/Swing viewer for inspecting which tiles exist at every depth of a
folder-based pyramidal image. It intentionally does not use JOGL: every output pixel is
drawn by Java2D with nearest-neighbor sampling and Java UI scaling fixed to `1.0`.

## Input and execution

The only argument is a pyramidal image folder. It must contain `0.png`; child tiles use
the recursive full-quadkey layout produced by `32_pyramidalImageExporter` (for example,
`00/00.png` and `00/002/002.png`).

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

The selected depth starts at `0` and is clamped to the deepest level containing data.
The English HUD in the upper-right corner reports depth, matrix dimensions and active
LOD mode.

## Pixel LOD modes

The complete level is laid out as a square `2^depth x 2^depth` tile matrix, north up.
Only cells backed by a tile PNG at the selected depth are painted.

1. **Native/scaled:** the viewer tries image sides `256`, `128`, `64`, `32`, `16`, `8`,
   `4` and `2` pixels, in that order, and selects the largest one for which the complete
   tile matrix fits. Every image is surrounded by one unpainted background pixel on each
   side, so tile boundaries remain visible. Sampling uses nearest-neighbor filtering.
2. **Coverage:** if even the `2x2` image plus border does not fit, each available tile is
   one pixel. A level-0 image supplies colors
   through level 8; level 1 supplies level 9, and so on. When the complete one-pixel
   matrix does not fit, both scrollbars are enabled.

When scrollbars first appear at a depth, the viewport is initialized over the center of
the bounding box of the tiles that actually exist. This keeps sparse captured regions in
view instead of blindly centering the complete world matrix.

AWT key events are converted through Vitral's `AwtSystem`. The command mapping itself
operates on Vitral events and action interfaces, without depending on AWT or Swing.

Images are loaded lazily into a bounded LRU cache. Scanning startup indexes paths and
coordinates but does not decode every PNG.

## Tests

```bash
gradle test
```
