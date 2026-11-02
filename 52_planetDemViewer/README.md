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
- 3D terrain meshes use one of three edge-subdivision schemes, selectable at run
  time (see [Terrain subdivision schemes](#terrain-subdivision-schemes)).
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

## Terrain subdivision schemes

The layer panel selects how each tile's samples are joined into triangles. There
is no key for this setting. It starts on triangle edge and is not saved in
`state.json`. The first two schemes render every tile at full resolution, and
LIND1996 chooses its triangles for each frame.

| | Triangle edge | Diamond-square | LIND1996 |
| --- | --- | --- | --- |
| Reference | Figure [MILL1986a].1 | Figure [MILL1986a].2 | [LIND1996] |
| Traversal | Row by row, same diagonal in every cell | Recursive bisection from the tile's four quadrants | The same bisection, stopping at vertices that are not enabled |
| Vertices per tile | 256 x 256 | 257 x 257 (stored samples 0 to 256) | 257 x 257, of which it draws a subset |
| Triangles per tile | 130,050 | 131,072 | Up to 131,072, depending on the view |
| Can subdivide below the DEM spacing | No | Yes, but not implemented yet | No |
| Color from other levels than the geometry | No | Yes | Yes |

### Independent color and geometry levels

The elevation and color pyramids are two independent, incomplete quadtrees: a tile may have
no PNG of its own level, or PNG files many levels deeper than its DEM. Following sections 4.1
and 4.2 of [LERB2010], an elevation block is linked to the color blocks that cover it, and
the option "Independent color levels" controls this. It appears in a panel at the bottom of the
layer panel when it applies (3D terrain with color, in the diamond-square and LIND1996 schemes),
along with the levels the last frame used. It is on by default and is not saved. The MCP tool
`set_independent_imagery_levels` also sets it, and `get_camera_model` reports those levels.
Clearing it draws each tile with the PNG of its own level only, or without color.

- **Color shallower than the geometry.** A tile whose PNG is missing, or still loading, uses the
  nearest ancestor that has one, with a texture coordinate transform.
- **Color deeper than the geometry.** A tile that is an elevation leaf is split into squares of
  its grid with the same coverage test and thresholds as the elevation tiles, and each square is
  drawn with its own PNG. Squares outside the view are not drawn. Whether a block exists is found
  out in the background, so the frame never waits for storage.

These parts differ from the paper or limit the result:

- The paper binds several textures in one fragment shader. Here the triangles of a tile are
  grouped by square, with one draw call per square. Two bisections of the diamond-square triangles
  leave the four quadrants of a square with the same pattern, so a square is an exact set of
  triangles. Triangle edge has 255 intervals per side, which cannot be halved, so it keeps the PNG
  of its own level. Only its deeper color needs this: the ancestor fallback alone would work there,
  and is not enabled.
- In LIND1996 a coarse triangle may cross several squares, so the center of each square stays
  enabled, with its dependencies. This adds triangles only where a color block requires them.
- The smallest square has 2 intervals of the grid, so color goes at most 7 levels below the DEM
  tile. Geometry is not subdivided further.
- Importance is not a separate measure, as section 4.2 says, and a tile whose DEM can still split
  is refined by splitting it. So deeper color only appears at elevation leaves.
- The mipmaps and filtering of sections 4.2 and 4.3 are not implemented.

### LIND1996

The vertex-based simplification of [LIND1996] removes a vertex while its delta
segment projects to less than a threshold, τ, of 1 pixel. Selecting LIND1996 shows a
panel at the bottom of the layer panel, with a slider and the mouse wheel moving τ over
values from 0 to 16 pixels, and the original and optimized triangle counts of the frame
below them. The MCP tool `set_screen_error_threshold` also sets τ, to any value. It is
not saved. The HUD and `get_camera_model` report, per frame, the triangles before and
after simplification and the evaluations of the projection equation. The vertices of a tile stay on the
GPU and only its triangle indices change. Popping is visible: the algorithm is meant
to be compared with later ones that treat it.

These parts differ from the paper:

- **Block selection.** Tiles still come from the projected-area selection, not from
  section 4.2, so the threshold bounds the error only inside each tile. The HUD counts
  the tiles that the paper's test would replace with a coarser one.
- **Tile boundaries.** By default, section 4.3 of the paper applies to tiles of the same
  level: each keeps its own copy of a shared vertex, and both are enabled when either
  tile needs it. An edge against a tile of another level always stays complete, because
  seam stitching relies on its samples. Clearing the panel's "Simplify tile boundaries"
  checkbox, or the MCP tool `set_dependencies_cross_tiles`, keeps every boundary complete
  instead, so adjacent tiles meet with all their samples, at the cost of extra triangles
  near the boundaries.
- **Level jump.** The paper obtains a parent tile by discarding samples of its children.
  This pyramid filters them, so the parent has other heights. The largest height
  difference at the vertices they share is added to the tile's maximum delta, when the
  parent is in memory.
- **NoData.** The paper does not treat it. Vertices next to NoData are kept, so the
  outline of the data is the same as in the full mesh.
- **Orthogonal cameras.** The equations assume perspective, so these draw every triangle.

Selection always uses the camera of the main view, as tile selection does, so the
other views show that same mesh from outside.

## Caching and locality

Tiles are the cache pages in this viewer: elevation rasters, palette images,
2D textures, and terrain mesh variants are kept only while their respective
memory budgets allow. This lets camera movement reuse nearby pages and load
new detail in the background.

All tile caches use access-ordered LRU eviction. Rendering a tile renews both
its resident DEM page and its derived GPU resource in their LRU orders. When a
budget is exceeded, the least recently viewed page is evicted first. The RAM cache has one shared LRU order for DEM
rasters and transient palette images; the 2D texture cache and terrain-mesh
cache maintain their own GPU LRU orders.

Terrain meshes are cached by subdivision scheme and a complete seam-variant key
rather than by tile alone. That key includes every coarse neighbour which modifies one of the
tile's borders, so a mesh stitched for one LOD frontier cannot overwrite or be
mistaken for the same tile under another frontier. The OpenGL 4 renderer uses
VAOs/VBOs, not deprecated display lists.

The RAM budget defaults to 80% of the JVM maximum heap. `PLANET_DEM_RAM_CACHE_BYTES`
can request a smaller explicit byte budget (still capped at that safe heap
fraction). Texture and terrain-mesh GPU budgets are each configured at 512 MiB.

## Graphics backend layering

The package `planetdemviewer.render.jogl4` is a thin layer whose only job is to
control the GPU: shaders, buffers, textures, draw calls and reading the color
buffer. Every method in it depends directly or indirectly on OpenGL. Everything
else is in `planetdemviewer.render`, which uses no graphics API:

- which tiles a frame shows, and which texture or mesh each one uses;
- cache and memory budget policies;
- picking, geometry, and the content and layout of text;
- the state of the viewer, which the application reaches through
  `PlanetViewerSession`.

The backend does no input/output either. It returns a captured image as a Vitral
image, and the application decides whether to save it. Images are Vitral images
throughout, so that `render`, its backend and the loaders do not depend on AWT.
Text goes through the `TextRasterizer` interface. Because Vitral has no font
rasterizer that works without AWT, the application gives the renderer
`gui.awt.AwtTextRasterizerHelper`.

The terrain has its own subpackages on each side of that line, which keep it apart
from the rest of the application. `planetdemviewer.render.terrain` holds the three
[subdivision schemes](#terrain-subdivision-schemes), seam stitching, the frame planner
and picking, and `planetdemviewer.render.jogl4.terrain` holds their GPU side. The
caches, `TextureUploader` and `DirectBuffers` stay in `render`, and
`Jogl4TextureCacheHelper` in `render/jogl4`, because the palette images use them too.

There are three reasons to keep this separation:

- **Portability.** Moving to another API, such as Vulkan or Metal, should mean
  replacing `render/jogl4` and not rewriting the viewer's behavior.
- **Testability.** Logic in `render` runs in unit tests without a GL context or a
  display.
- **Clarity.** Algorithms read as algorithms, without graphics API calls mixed in,
  and GPU code is easy to find and review.

`RenderBackendBoundaryTest` checks part of this rule. The application classes
`Main` and `InteractiveViewer` still use JOGL directly to create the canvas and its
animator.

## Bibliography

The folder `doc/references` at the repository root (`../doc/references` from this
project) contains the papers that guide the implementation of this system.

The code keeps the terminology of these papers, so that a person can easily
recognize the concepts of the literature in it. As far as possible, code that
implements a specific algorithm cites the work it comes from, down to the equation,
section or figure, with keys such as `[MILL1986a]` that match the names of the PDF
files. `AGENTS.md` describes the citation format.

## Requirements

- Java 17
- Gradle
- An OpenGL 4-capable environment for interactive or offscreen rendering

The project uses JOGL and Vitral 1.4.

PNG files load faster with Vitral's native reader, which is optional and needs
libpng. Build it from Vitral's `pkgs/NativeImageReader` project. `run.sh` looks for
it in `$HOME/VITRAL/vitral/pkgs/NativeImageReader/build`, and the
`VSDK_NATIVE_LIB_PATH` environment variable selects another folder. Without it,
Vitral reads them through AWT.

## Run the viewer

```bash
./run.sh /Volumes/FABDEM/02_rawPyramidal /Volumes/googleEarth/toplevel
```

Both folders are required, in this order: the elevation (DEM) pyramid and the
color imagery pyramid. There are no default locations. If a folder is missing or
the DEM pyramid has no valid `0.bin`, the program prints the error and exits
without opening a window. You may also pass the folders directly to Gradle:

```bash
gradle run --args="/Volumes/FABDEM/02_rawPyramidal /Volumes/googleEarth/toplevel"
```

### Storage profiles

`slow` is the default and serializes tile reads and tree discovery, which is
appropriate for slow or removable storage. Use `fast` on storage that tolerates
parallel I/O:

```bash
./run.sh --storage-profile fast /Volumes/FABDEM/02_rawPyramidal /Volumes/googleEarth/toplevel
```

### Memory configuration

Increase the JVM heap when the dataset and available RAM justify it:

```bash
PLANET_DEM_MAX_HEAP=32g ./run.sh /Volumes/FABDEM/02_rawPyramidal /Volumes/googleEarth/toplevel
```

Set a specific RAM cache budget in bytes if required:

```bash
PLANET_DEM_RAM_CACHE_BYTES=8589934592 ./run.sh /Volumes/FABDEM/02_rawPyramidal /Volumes/googleEarth/toplevel
```

## Offline export

Offline rendering first discovers all leaves, then renders to a PNG. Unlike the
interactive viewer, this intentionally performs a complete pyramid traversal. It
draws the elevation palette only: the imagery folder is required but not drawn.

```bash
./run.sh --offline /Volumes/FABDEM/02_rawPyramidal /Volumes/googleEarth/toplevel \\
  --output /tmp/planet-dem.png --width 2048 --height 2048 --wires
```

## GUI

The window has a menu bar and a left panel with a tree of layers. Radio buttons
select how elevation is shown (3D terrain, the default, or a 2D palette image)
and whether Google Earth color is drawn on the terrain, plus the terrain's edge
subdivision scheme. Color is on by default,
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
its HUD information: the elevation tile and level, and on the line below them
the color tile and level showing at that point when it was clicked, which can
be another level. Use `Ctrl`+click to also select the hit tile; its min/max
box is drawn in yellow.

| Key | Action |
| --- | --- |
| `Space` | Toggle palette and 3D terrain modes |
| `t` | Toggle Google Earth color on the 3D terrain |
| `3` / `4` | Previous / next GIMP palette |
| `5` / `6` | Halve / double vertical exaggeration |
| `0` / `F4` | Toggle tile bounds in the selected view |
| `F2` | Toggle triangle edges |
| `F7` | Next shading model of the selected view: no light, flat, Gouraud, Phong, Cook-Terrance |
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

## MCP tools

With `--mcp-port <port>` (`run.sh` uses 42817) the viewer exposes these tools to
agents at `http://127.0.0.1:<port>/mcp`:

| Tool | Purpose |
| --- | --- |
| `set_geometry_mode` | Switch between the 3D terrain and the 2D palette image |
| `set_subdivision_scheme` | Select `TRIANGLE_EDGE`, `DIAMOND_SQUARE` or `LIND1996`; 3D terrain mode only |
| `set_screen_error_threshold` | Set the LIND1996 threshold, in pixels |
| `set_camera_model` | Define the selected view's camera directly, in world coordinates |
| `set_dependencies_cross_tiles` | Whether adjacent LIND1996 tiles of the same level simplify their common edge together |
| `set_independent_imagery_levels` | Whether color may come from other levels than the geometry |
| `set_wires` | Show or hide the triangle edges, as `F2` does |
| `set_tile_bounds` | Show or hide tile bounds in the selected view, as `0` and `F4` do |
| `click_viewport` | Select the terrain under a pixel of `capture_view` |
| `key_event` | Send a key code, without modifiers |
| `capture_view` | Save the canvas to a local PNG and return its path |
| `get_camera_model` | Return the camera and the rendering options as JSON |

## Palettes

GIMP palettes are loaded from `../etc/palettes`; `Topographic.gpl` is the
initial palette. Elevations are normalized over 0 to 12,000 metres and clamped
at both ends. Changing a palette discards derived colour images and textures,
but preserves cached DEM elevations for recolouring on demand.

## Test

```bash
gradle test
```
