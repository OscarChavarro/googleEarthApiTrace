# Planet DEM Viewer

An interactive Java 17 desktop viewer for large, tiled Digital Elevation Model
(DEM) pyramids. It renders both palette-coloured raster tiles and illuminated
triangle-mesh terrain. The data model is a sparse quadtree of tiles at multiple
levels of detail (LOD), intended for datasets such as those stored under
`/Volumes/FABDEM`.

The viewer treats the terrain as a planar, normalized world surface. Camera
position, frustum visibility, and projected tile coverage determine which
quadtree nodes are rendered, so detail increases only where it is useful on
screen.

## Highlights

- Dynamic, camera-driven LOD selection over a sparse tile quadtree.
- Frustum culling and per-tile LOD hysteresis to reduce refinement/coarsening
  flicker.
- Two rendering modes:
  - palette-coloured 2D DEM tiles;
  - shaded 3D terrain built from a regular triangle mesh.
- 3D terrain meshes contain 256 x 256 vertices and two triangles per cell.
  A one-sample DEM halo is used for border normals.
- Seam stitching between adjacent tiles at different LODs: fine border heights
  are interpolated from the coarser neighbour, and the outer halo used for
  lighting is sampled consistently. A one-grid-step coverage apron on the
  constrained fine edge prevents rasterization cracks at T-junctions.
- Lazy tree discovery and asynchronous tile loading. Startup validates and
  reads only the root tile; visible branches, a look-ahead level, and
  neighbouring branches are indexed later with priority.
- Progressive refinement: while a requested tile is unavailable, raster
  rendering can keep using the nearest available ancestor tile.
- Multiple DEM pyramids, multiple views, saved viewer state, offline PNG
  rendering, palette cycling, vertical exaggeration, and optional terrain-tile
  bounds.

## DEM pyramid format

Each `<quadkey>.bin` file is a little-endian `Int16` raster with a fixed size
of 258 x 258 samples (133,128 bytes). Samples are ordered row by row from
north to south.

- The 256 x 256 inner samples are the tile's rendered core.
- The surrounding one-sample halo is retained in memory for terrain normals
  and boundary handling.
- `-32768` is NoData. It is transparent in palette mode, and triangles that
  require a NoData vertex are omitted in terrain mode.

The root is `0.bin`. Each later quadkey digit is stored in a child directory;
for example, `3/0/3/0303.bin`. The legacy cumulative-directory layout is also
accepted.

## LOD and terrain processing

The draw planner traverses the quadtree from the root. It rejects nodes outside
the camera frustum, estimates the node's projected screen coverage, and refines
nodes whose coverage exceeds the configured threshold. A lower threshold is
used after refinement, providing hysteresis. Terrain LOD also uses a height
aware bounding box when the relevant DEM is already resident.

In terrain mode, each selected tile becomes a mesh in the planar normalized
world domain. Elevations in metres are scaled using the equatorial
circumference and then multiplied by the current vertical-exaggeration factor.
The seam planner examines the selected quadtree frontier before mesh creation
and prepares variants that join fine tiles to coarser neighbours.

Press `9` to temporarily use a uniform LOD: all visible branches are drawn at
the finest depth required by the current dynamic selection. Press `0` to show
the bounding boxes of terrain tiles selected for 3D rendering.

## Caching and locality

Tiles are the cache pages in this viewer: elevation rasters, palette images,
2D textures, and terrain mesh variants are kept only while their respective
memory budgets allow. This lets camera movement reuse nearby pages and load
new detail in the background.

All tile caches use access-ordered LRU eviction. Rendering a tile renews a
monotonic `System.nanoTime()` access timestamp for its resident DEM page and
for its derived GPU resource. When a budget is exceeded, the least recently
viewed page is evicted first. The RAM cache has one shared LRU order for DEM
rasters and transient palette images; the 2D texture cache and terrain-mesh
cache maintain their own GPU LRU orders.

Terrain meshes are cached by a complete seam-variant key rather than by tile
alone. That key includes every coarse neighbour which modifies one of the
tile's borders, so a mesh stitched for one LOD frontier cannot overwrite or be
mistaken for the same tile under another frontier. The OpenGL 4 renderer uses
VAOs/VBOs, not deprecated display lists.

The RAM budget defaults to 80% of the JVM maximum heap. `PLANET_DEM_RAM_CACHE_BYTES`
can request a smaller explicit byte budget (still capped at that safe heap
fraction). Texture and terrain-mesh GPU budgets are each configured at 512 MiB.

## Requirements

- Java 17
- Gradle
- An OpenGL 4-capable environment for interactive or offscreen rendering

The project uses JOGL and Vitral 1.4.

## Run the viewer

```bash
./run.sh /Volumes/FABDEM/02_rawPyramidal
```

Without arguments, `run.sh` uses its configured default dataset path. You may
also pass one or more pyramid-root directories directly to Gradle:

```bash
gradle run --args="/Volumes/FABDEM/02_rawPyramidal"
```

### Storage profiles

`slow` is the default and serializes tile reads and tree discovery, which is
appropriate for slow or removable storage. Use `fast` on storage that tolerates
parallel I/O:

```bash
./run.sh --storage-profile fast /Volumes/FABDEM/02_rawPyramidal
```

### Memory configuration

Increase the JVM heap when the dataset and available RAM justify it:

```bash
PLANET_DEM_MAX_HEAP=32g ./run.sh /Volumes/FABDEM/02_rawPyramidal
```

Set a specific RAM cache budget in bytes if required:

```bash
PLANET_DEM_RAM_CACHE_BYTES=8589934592 ./run.sh /Volumes/FABDEM/02_rawPyramidal
```

## Offline export

Offline rendering first discovers all leaves, then renders to a PNG. Unlike the
interactive viewer, this intentionally performs a complete pyramid traversal.

```bash
./run.sh --offline /Volumes/FABDEM/02_rawPyramidal \\
  --output /tmp/planet-dem.png --width 2048 --height 2048 --wires
```

## GUI

The window has a menu bar and a left panel with a tree of layers. Radio buttons
select how elevation is shown (3D terrain, the default, or a 2D palette image)
and whether Google Earth color is drawn on the terrain. Color is on by default,
is saved in `state.json` with `e`, and is available only with 3D terrain and when
a color pyramid was given as the second folder. The `Space` and `t` keys change
the same state, so the tree always follows them. `Settings > Language` switches
the menus, panel and HUD between English and Spanish without restarting; the
language is not saved. `Ctrl+F` toggles full screen, which shows only the canvas
and HUD.

The GUI is defined by Vitral JSON files in `etc/gui`: `english.json` and
`spanish.json` hold the menus, panel and HUD texts, and `dialogs.json` holds the layer tree.
Swing code is limited to `planetdemviewer.gui.awt`. Commands and layer behavior
are in the toolkit-independent `GuiController` and `LayerTreePresenter`, to make
later ports easier.

## Controls

The mouse, wheel, arrow keys, and the remaining camera controls provided by
Vitral's `CameraControllerAquynza` move the active camera. Project-specific
controls are:

In 3D terrain mode, a left click places the blue surface marker and updates
its HUD information. Use `Ctrl`+click to also select the hit tile; its min/max
box is drawn in yellow.

| Key | Action |
| --- | --- |
| `Space` | Toggle palette and 3D terrain modes |
| `t` | Toggle Google Earth color on the 3D terrain |
| `3` / `4` | Previous / next GIMP palette |
| `5` / `6` | Halve / double vertical exaggeration |
| `0` | Toggle terrain tile bounds |
| `Ctrl` + click | Select terrain tile for cleanup |
| `Delete` | Delete the selected tile's imagery `.png` and all its sublevels, and free their GPU textures; no confirmation, no undo (never the DEM `.bin`) |
| `9` | Toggle uniform LOD |
| `l` | Load a DEM pyramid from the GUI |
| `1` / `2` | Select previous / next loaded pyramid |
| `o` / `O` | Decrease / increase selected-layer opacity |
| `Page Up` / `Page Down` | Raise / lower the selected layer |
| `z` / `Z` | Zoom in / out |
| `r` / `R` | Reset camera / reset to oblique camera |
| `.` / `,` | Cycle active view / view layout |
| `w` | Toggle camera-frustum display |
| `v` / `V` | Add / remove a view (up to four) |
| `e` / `E` | Save / clear saved viewer state |
| `Ctrl` + `f` | Toggle full screen (hides the menu bar and layer panel) |
| `Escape` | Close the viewer |

## Palettes

GIMP palettes are loaded from `../etc/palettes`; `Topographic.gpl` is the
initial palette. Elevations are normalized over 0 to 12,000 metres and clamped
at both ends. Changing a palette discards derived colour images and textures,
but preserves cached DEM elevations for recolouring on demand.

## Test

```bash
gradle test
```
