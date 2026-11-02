# Architecture Plan: Planetary Terrain Framework

## Purpose

`planetDemViewer` is evolving from a DEM-file viewer into a terrain framework. The goal is to combine elevation, imagery, vector data, multiple map projections, and multiple GPU backends without making a new data source or graphics API require a rewrite of LOD planning, geospatial semantics, or interaction.

This document defines the target architecture and an incremental migration. It introduces no functionality and does not change current behavior.

## Existing Foundation to Preserve

- Sparse, quadkey-indexed DEM pyramid (`PyramidalImage`, `QuadtreeNode`) and 258 x 258 `Int16` files.
- A visible 256 x 256 core, one-sample halo for normals, NoData, and C0/C1 seams between DEM neighbors.
- Camera-driven LOD, hysteresis, a temporary uniform-LOD mode, and seams against less detailed neighbors.
- Lazy loading, LRU caching, and a recency-prioritized concurrent queue for `.bin` reads; it is deliberately not FIFO.
- Terrain meshes, GPU caches, OpenGL 4/JOGL renderer, HUD, views, saved state, and MCP.
- Animation, reading, and JOGL threads; `PlanetViewerModel` is the shared critical section.

The migration must not alter the DEM format or regress these use cases.

## Design Principles

1. **Semantics before representation.** A geographic position, elevation, and tile identity do not depend on OpenGL or a visual projection.
2. **The quadtree is spatial, not a path.** A quadkey identifies a region; source adapters resolve where its content lives.
3. **Every layer has its own resolution.** An L13 terrain tile may use L13–L19 imagery and vectors with a different partition.
4. **Planning is not rendering.** Selection produces an immutable description of visible work; the backend decides how to upload and draw resources.
5. **Explicit transformations.** Longitude/latitude, meters, projected coordinates, and GPU coordinates are never mixed without declaring their reference system.
6. **Resources have owners.** CPU data, files, meshes, and GPU objects have separate lifetimes; GPU objects only live in their backend's context thread.
7. **Compatible evolution.** Adapters are introduced around existing code rather than performing a one-shot rewrite.
8. **CPU is canonical; GPU is optional.** Every algorithm has a correct, available CPU implementation. GPU accelerates eligible work without changing semantics or becoming a functional requirement.

## Spatial Model and References

### Required distinctions

- **WGS84** primarily defines a reference ellipsoid and geodetic system: longitude, latitude, and ellipsoidal height.
- A **geoid** is an equipotential surface used as a vertical datum. A DEM with orthometric height needs a transformation relative to the ellipsoid: `h = H + N`, where `N` is geoid undulation.
- A **map projection** maps geodetic coordinates to a plane: Mercator, Peters, Robinson, sinusoidal, azimuthal, conic, and so on. It is not a geoid.
- Planetary mode places positions on the WGS84 ellipsoid (or an approximate sphere), rather than representing the global world as a plane.

### Target contracts

These names are design contracts; they are not code yet:

```java
interface SpatialReference {
    String id(); // EPSG:4326, EPSG:3857, or a custom definition
    GeographicPosition toGeographic(SpatialPosition position);
    SpatialPosition fromGeographic(GeographicPosition position);
}

record GeographicPosition(double longitudeDegrees, double latitudeDegrees,
                          double ellipsoidalHeightMetres) {}

interface VerticalDatum {
    String id();
    double ellipsoidalHeight(GeographicPosition horizontal,
                             double sourceHeightMetres);
}

interface SurfaceModel {
    SurfacePoint locate(GeographicPosition position);
    SurfaceFrame localFrame(GeographicPosition position);
}
```

`PlanarSurfaceModel` will adapt the current normalized world.
`Wgs84EllipsoidSurfaceModel` will place vertices on the ellipsoid. A
`GeoidVerticalDatum` may convert heights before they reach the surface. No
mesh may assume that `x/y` are both longitude/latitude and normalized planar
coordinates.

The `SpatialReference` and `SurfaceModel` catalog will first be selected by
configuration and later by UI. A change is atomic for the spatial scene: it
invalidates meshes, bounds, BVH, and spatial LOD, but does not require source
samples to be read again.

| Family | Examples | Presentation surface |
| --- | --- | --- |
| Cylindrical | Web Mercator, Miller, Behrmann, Peters | plane |
| Thematic | Robinson, Hammer-Aitoff, sinusoidal, conic | plane |
| Azimuthal/perspective | gnomonic, stereographic, orthographic | local/global plane |
| Planetary | WGS84 ellipsoidal | 3D ellipsoid |

Every projection declares its domain, poles/antimeridian behavior, and its
method for conservative bounds. Not all projections share the same metrics or
tessellation.
Every projection declares its domain, poles/antimeridian behavior, and its
method for conservative bounds. Not all projections share the same metrics or
tessellation.

### Adopted vertical datum and FABDEM normalization

The application’s canonical 3D height is WGS84 ellipsoidal height `h`.
FABDEM supplies EGM2008-referenced orthometric height `H`; therefore every
FABDEM sample is normalized during source loading, before it enters elevation
cache, mesh generation, normal calculation, LOD bounds, or ray intersection:

```text
h(latitude, longitude) = H_FABDEM + N_EGM2008(latitude, longitude)
```

`N_EGM2008` is read from
`etc/wgs/globalDeltaHeightsMatrix.json`. This repository file is a global,
regular 5-arc-minute GeographicLib `egm2008-5` grid (4,320 columns × 2,161
rows), row-major from 90°N to 90°S, with longitude in `[0°, 360°)`. It stores
geoid undulation in metres and retains source URL, SHA-256, grid origin,
spacing, and encoding metadata. GeographicLib distributes this grid from NGA
EGM2008 data; the downloaded archive was verified against SHA-256
`9a57c14330ac609132d324906822a9da9de265ad9b9087779793eb7080852970`.

The loader wraps longitude modulo 360°, clamps latitude to the poles, and uses
bilinear interpolation of `N`. `ElevationLayer` must retain provenance:
source vertical datum `EGM2008 orthometric`, normalization model/grid
revision, and resulting canonical datum `WGS84 ellipsoidal`. It must not
apply the correction twice. Original `H` remains available only where
explicit source-value inspection requires it.

### Viewport, world, and geographic transformations

A user interaction starts at viewport position `(u, v)`. The contract must
obtain a camera ray, resolve its scene intersection, and express the result in
both world `(x, y, z)` and longitude/latitude/height. UI code must not
contain projection-specific formulas or assume a planar surface.

```java
interface CoordinateTransformService {
    ViewportRay viewportRay(ViewportPoint point, CameraSnapshot camera,
                            Viewport viewport);
    Optional<WorldHit> viewportToWorld(ViewportPoint point, ViewQuery query);
    GeographicPosition worldToGeographic(WorldPosition position,
                                         SpatialContext context);
    WorldPosition geographicToWorld(GeographicPosition position,
                                    SpatialContext context);
}

record WorldHit(WorldPosition worldPosition,
                GeographicPosition geographicPosition,
                LayerId layerId, TileAddress tile, RayHit hit) {}
```

`viewportToWorld` builds the ray from snapshot camera matrices and queries
surface/layers through TLAS and BLAS when available. If no resident geometry
exists, it may return empty or apply the policy specified by `ViewQuery`
(such as an ellipsoid fallback). Returned height explicitly states the
requested datum: ellipsoidal or orthometric. Every `SpatialReference`,
`VerticalDatum`, and `SurfaceModel` must provide the necessary transforms
or explicitly reject an unsupported domain; coordinates are never ambiguous.

## Layers and Data Sources

A scene becomes an ordered `TerrainLayer` stack instead of a specialized
`PyramidalImageInstance` list. Each layer defines its semantics, source, and
representation policy.

```java
interface TerrainLayer {
    LayerId id();
    LayerDescriptor descriptor();
    LayerCoverage coverage(SpatialTile tile);
    TileRequestResult request(TileRequest request);
}

interface TileSource<T> {
    SourceDescriptor descriptor();
    TileAvailability availability(TileAddress address);
    CompletionStage<TilePayload<T>> request(TileRequest request);
}
```

`TileAddress` contains tile scheme, level, x/y or quadkey, spatial reference,
and a temporal variant when applicable. `TileRequest` includes priority,
requesting view, optional deadline, and purpose: `RENDER`, `PICKING`,
`PREFETCH`, or `OFFLINE`. A recent visible request supersedes an obsolete
one; prefetch must not block rendering.

### Elevation

`ElevationLayer` supplies a grid with halo, NoData, vertical datum, and
resolution metadata. `DemTile` and `TileImageLoader` become the
`FabdemBinElevationSource` adapter. The 258/256 rules, normals, and seams
are source representation details, not global planner rules.

### Secondary imagery pyramid

`ImageryLayer` reads the imagery folder given on the command line. Its adapter reports
tile scheme, CRS, coverage, maximum level, and its transformation relative to
the elevation layer.

A terrain tile and its textures are related by `TextureCoveragePlan`:

```java
interface TextureCoveragePlanner {
    TextureCoveragePlan plan(SpatialTile terrainTile,
                             ImageryLayer imagery, ViewRequest view);
}
```

The plan may contain an ancestor texture, the equivalent tile, or a set of
finer subtiles. An L13 mesh may therefore receive up to `4^(19-13)` L19
images. It must choose among ancestor fallback, texture patches, UVs per
patch, and a per-frame patch limit. Texture tessellation must not change DEM
mesh continuity or unnecessarily multiply geometry. The backend hides whether
it uses an atlas, a texture table, or a later technique.

### OpenStreetMap vectors

`VectorLayer` models entities rather than prebuilt triangles:

`VectorSource (OSM/other) -> VectorTile -> style -> surface -> backend`.

Entities carry geographic geometry and attributes. Draping uses
`SurfaceModel` and, where appropriate, elevation to place lines, areas,
symbols, or buildings. This prevents coupling OSM to Mercator or OpenGL. A
vector presentation layer owns occlusion, z-offset, and vector LOD.

## Planning, Tessellation, and Seams

The planner is split into three decisions:

1. **Visibility:** frustum, planetary horizon where applicable, and
   `SpatialTile` coverage through conservative surface bounds.
2. **Geometry LOD:** elevation frontier with hysteresis, triangle budget, and
   neighborhood rules.
3. **Content LOD:** textures, vectors, and auxiliary data at independent
   resolutions for each geometric tile.

`TerrainMeshPlan` is immutable and includes edges, neighbors, spatial
transform, vertical datum, and texture plan. `TerrainStitchingStrategy`
continues adjusting a fine mesh edge and interpolated normal to match its
coarser neighbor. For WGS84, this occurs in the surface local frame before
conversion to rendering coordinates.

LOD/culling bounds always come from the real surface or conservative source
limits, never from a flat sheet in curved mode.

### Continuous transitions between LOD levels

The LOD frontier may instantly decide that a tile moves from level `i` to
`i + 1`; initially the system retains this current pop behavior. The design
must allow every replacement to have a temporal `double t` in `[0, 1]`,
where `t = 0` represents the previous level's geometry/coordinates and
`t = 1` represents the new level. A policy may disable transition and use
`t = 1` immediately, preserving current behavior.

```java
interface LodTransitionPolicy {
    LodTransition begin(LodReplacement replacement, FrameTime now);
    double progress(LodTransition transition, FrameTime now); // [0, 1]
}

record LodTransition(LodReplacement replacement, double durationSeconds,
                     TransitionMode mode) {}
```

The first strategy is *geomorph*: every fine-mesh vertex retains its target
position and a source position derived from the parent mesh or parent sample.
The shader or CPU route interpolates `mix(source, target, t)`. Normals and
constrained edges follow the same transition to avoid cracks and lighting
changes. Parent and children overlap while transitioning, under a
depth/fade rule defined by `TransitionMode`.

Transition state belongs to scene planning, not a concrete GPU mesh. It is
invalidated by projection, datum, exaggeration, or seam changes. Memory budget
accounts for both LODs at once; the policy may shorten or cancel transitions
under pressure.

## Visualization Backends

The core does not know `GL4`, VAO/VBOs, Vulkan command buffers, or Metal
command encoders. The façade is based on resources and draw packets:

```java
interface RenderBackend extends AutoCloseable {
    BackendCapabilities capabilities();
    RenderResource upload(MeshData mesh);
    RenderResource upload(TextureData texture);
    FrameEncoder beginFrame(RenderTarget target, FrameState state);
    void release(RenderResource resource);
}

interface FrameEncoder {
    void drawTerrain(TerrainDrawPacket packet);
    void drawRaster(RasterDrawPacket packet);
    void drawVectors(VectorDrawPacket packet);
    void drawHud(HudPacket packet);
    void endFrame();
}
```

The package `render/jogl4` is the present OpenGL implementation, and it only
controls the GPU: shaders, buffers, textures, draw calls and reading the color
buffer. Every method in it depends directly or indirectly on OpenGL, and it does
no input/output. Everything else lives in `render`, which does not depend on any
graphics API, so that another backend only has to replace `render/jogl4`.

The application talks to `PlanetViewerSession`, and exports the images it obtains
from `Jogl4PlanetViewerRenderer` through `ViewerSnapshotWriter`.

| In `render` (no graphics API) | Responsibility | Used by, in `render/jogl4` |
| --- | --- | --- |
| `PlanetViewerSession` | Loaders, views, selection, user choices; composes each frame's inputs | `Jogl4PlanetViewerRenderer` |
| `ViewportLayout` | The 1 to 4 views, their layout, and which one the camera controller drives | Through the session |
| `TerrainPicker`, `TerrainPickFrame` | From a pixel to the selected surface point and tile, on the CPU | Through the session; `TerrainFramePlanner` reports drawn meshes |
| `TerrainFramePlanner`, `TerrainMeshBuffers`, `TerrainMeshDraw` | LOD selection, seam variants, placement, imagery, which meshes to upload and when; arrays to upload; what to draw, already on the GPU | `Jogl4TerrainRenderer` |
| `PaletteFramePlanner`, `TileQuadGeometry` | LOD selection, texture or ancestor fallback; quad and outline vertices | `Jogl4QuadtreeRenderer` |
| `GpuResourceCache`, `TileTextureCache`, `TextureUploader`, `TerrainMeshUploader`, `GpuResourceUsage` | LRU, memory budget and usage counters; the backend only creates and destroys | `Jogl4TerrainMeshHelper`, `Jogl4TextureCacheHelper` |
| `DirectBuffers` | Native-memory copies of vertex data | `Jogl4TerrainMeshHelper`, `Jogl4QuadtreeRenderer`, `Jogl4TextRenderer` |
| `HudContent`, `TileLabelLayout`, `TextLabel`, `TextRasterizer` | What text goes where; its pixels come from `gui.awt.AwtTextRasterizerHelper`, which the application injects | `Jogl4TextRenderer` |
| `ViewOverlayPlanner` | Which frustums, marker placement, view border | `Jogl4ViewOverlayRenderer` |

`Jogl4OfflineStackRenderer` drives a frame on an offscreen surface, and
`Jogl4FramebufferReaderHelper` reads the color buffer into a Vitral image.

`BackendCapabilities` remains a backend-neutral architecture boundary. Native
Vulkan and Metal renderers, if adopted, will be implemented in a separate C++
project and communicate through a deliberately defined packet/ABI boundary;
they are not Java implementation targets for this repository.

### Dual CPU/GPU execution

Every expensive algorithm is expressed as a semantic contract with a reference
CPU route and one or more accelerated strategies. Selection depends on backend
capability, work size, latency budget, and required reproducibility—not on UI
or source code.

```java
interface TerrainComputeService {
    MeshData tessellate(TessellationRequest request);
    TextureCoverageData resolveTextureCoverage(TextureCoverageRequest request);
    AccelerationData buildAcceleration(AccelerationRequest request);
}

interface ComputeStrategy {
    ComputeCapabilities capabilities();
    boolean supports(ComputeRequest request);
    CompletionStage<ComputeResult> execute(ComputeRequest request);
}
```

`terrain.compute.TerrainComputeService` is always the functional reference:
tessellation, LOD geomorphing, seams, normal generation, texture coverage, and
BVH may run there. An optional accelerated strategy may be supplied by the
OpenGL implementation or a future native C++ backend, but it consumes the
same immutable input and preserves CPU semantics.

Tests compare CPU and GPU output for identical input within documented numeric
tolerances. Operations requiring exact or immediate readback—some click
queries, for example—keep a CPU route even when asynchronous GPU acceleration
exists.

## Concurrency and Ownership

| Domain | Owning thread/executor | Rule |
| --- | --- | --- |
| Animation/simulation | animation thread | updates time; never GPU |
| Input/MCP | EDT or MCP thread | publishes short model commands |
| Model | `PlanetViewerModel` critical section | consistent reads and atomic mutations |
| Files/decoding | source executors | never create GPU resources |
| CPU plans/meshes | preparation executor | delivers immutable data |
| Render/backend | context-owning thread | creates, uses, and frees GPU resources |

Mutable-object exposure is replaced by immutable snapshots:
`SceneSnapshot`, `CameraSnapshot`, and `LayerSnapshot`. The lock protects
transitions; it is not held during I/O, mesh generation, or GPU waits.
Communication uses `CompletionStage`, concurrent queues, and immutable
messages.

The common queue orders by `priority`, `requestedAt`, and visibility. An
in-queue request with the same key is replaced or loses priority to the newest
one. Cancellation is cooperative: a started read may finish and enter cache,
but cannot prevent newer visible work.

## BLAS and TLAS for Intersection

The contract, initially implemented on CPU, is:

```java
interface BottomLevelAccelerationStructure {
    RayHit intersect(Ray ray, RayQueryOptions options);
    Bounds bounds();
}

interface TopLevelAccelerationStructure {
    RayHit intersect(Ray ray, RayQueryOptions options);
    void update(List<InstanceBounds> instances);
}
```

- **BLAS:** one per terrain-mesh variant, containing local triangles. It is
  invalidated by elevation, seam, projection, or geometric changes.
- **TLAS:** over selected tile instances and transforms; it may rebuild or
  refit per frame.
- A ray is transformed to instance space, tests TLAS and then BLAS. Result
  includes geographic position, elevation/datum, layer, and tile.

A first phase retains CPU BVH. A future native backend may use hardware
acceleration without changing the picking or scene model.

## Target Packages

```text
planetdemviewer
  geo/          CRS, projections, vertical datum, WGS84, SurfaceModel
  spatial/      TileAddress, tile scheme, bounds, transforms
  scene/        model, layers, snapshots, commands, state
  io/           tile I/O contracts and adapters
  io/elevation/ FABDEM elevation import and normalization
  io/imagery/   imagery-pyramid importers
  io/vector/    preprocessed OSM vector-tile importers
  terrain/      elevation, LOD, meshes, seams, texture patches
  vector/       entities, styles, draping
  scheduler/    priorities, cancellation, budgets, executors
  acceleration/ BLAS, TLAS, rays, picking
  render/       backend-neutral contracts and caches
  render/jogl4/ existing OpenGL 4 implementation
  bridge/       future native C++ packet/ABI boundary; no native renderer here
  ui/           AWT/Swing, HUD, input, MCP, persistence
```

Dependencies point downwards: `ui -> render -> scene/terrain -> geo/spatial`;
`io` supplies data to the domain but does not import `render`. Backend and
I/O adapters do not import one another.

## Implementation Phases

### Phase 0 — Consolidate Current State

- Document/test invariants: 258/256/halo, NoData, C0/C1 seams, non-FIFO
  priority, GPU ownership, and locks.
- Add camera/scene snapshots to remove mutable reads outside the critical
  section.
- Define view matrices/snapshots and the CPU contract for
  viewport→ray→world→geography.
- Measure I/O, mesh preparation, GPU upload, memory, and FPS.

**Exit:** current imagery is preserved by regression tests.

### Phase 1 — Spatial Core and Sources

- Introduce `TileAddress`, tile scheme, `SpatialReference`,
  `VerticalDatum`, `SurfaceModel`, and `TileSource`, while retaining
  planar operation.
- Adapt FABDEM as `ElevationLayer` through a `DemTile` bridge.
- Convert `PyramidalImageInstance` into a layer implementation/adapter.

**Exit:** the same DEM looks identical but is no longer conceptually tied to a
file path or fixed projection.

**Migration status: complete.** `SpatialReference`, `SurfaceModel`, their
current equirectangular/planar adapters, `TileAddress`, `TileScheme`,
`VerticalDatum`, `TileSource`, and the FABDEM address-to-payload adapter are
explicit contracts. `PyramidalImageInstance` is now an `ElevationLayer` that
declares its tile scheme, CRS, source datum, and canonical datum. Existing
rendering remains on the planar adapter while WGS84 and other projections are
added later.

### Phase 2 — Immutable Scene and Planning

- Extract `SceneSnapshot`, `ViewRequest`, `TerrainMeshPlan`, and the
  common queue.
- Separate I/O, CPU preparation, and GPU upload/draw.
- Introduce `LodTransitionPolicy` with instant policy as default and enough
  data for future geomorphing.
- Generalize RAM/GPU budgets and telemetry by resource.

**Exit:** elevation, texture, and vector work use one priority policy.

**Migration status: complete.** `SceneSnapshot`, `ViewRequest`, and
`TerrainMeshPlan` provide immutable planning boundaries. The common
`scheduler.LatestRequestQueue` supplies replacement-by-key, priority, and
recency semantics for FABDEM loading and is reusable by later imagery/vector
sources. I/O, mesh preparation, and JOGL remain separately owned. The
`terrain` package contains the immutable LOD-transition contract and its
instant policy, preserving current behavior until geomorphing is introduced.
`ResourceBudget` and `ResourceTelemetry` now distinguish elevation/raster RAM
and terrain/raster GPU resources; current FABDEM and JOGL owners publish their
actual usage there.

### Phase 3 — Secondary Imagery Pyramid

- Create the `googleEarth/toplevel` adapter; detect actual CRS, tile scheme,
  Y convention, coverage, and root level before assuming FABDEM equivalence.
- Implement `TextureCoveragePlan`, ancestor fallback, and patches.
- Test L13 geometry covered by imagery through L19, including edges and UVs.

**Exit:** photography/color is decoupled from elevation resolution.

**Migration status:** the first validation mode is implemented: an optional
PNG pyramid has an independent reader thread and matches FABDEM by identical
quadkey/depth. It is disabled by default and toggled with `t`; a missing PNG
leaves the terrain white. Coverage discovery, ancestor fallback, and L13–L19
texture patches remain the next Phase 3 work.

### Phase 4 — WGS84, Geoid, and Projections

- Retain planar mode as control reference.
- Add WGS84 ellipsoid, vertical datum, and horizon culling.
- Add projections one at a time with validated bounds and metrics.
- Validate viewport→world→geography for every supported projection and datum.

**Exit:** layers are displayable on plane or ellipsoid without discontinuities.

### Phase 5 — OSM Vectors

- Define OSM source, style, vector LOD, and draping.
- Implement tile clipping, caches, and entity selection.

**Exit:** roads, polygons, and buildings are positioned on terrain.

### Phase 6 — Accelerated Picking

- Integrate CPU BLAS per mesh and TLAS for visible scene.
- Deterministically test rays, seams, and LOD transitions.
- Expose picking through UI and MCP.

**Exit:** robust, measurable, backend-independent intersection.

## Cross-cutting Acceptance Criteria

- CRS, datum, resolution, coverage, and provenance metadata are never implicit
  in file paths.
- DEM maintains C0 and coherent normals for equal and mixed LOD neighbors, in
  both planar and curved surfaces.
- An LOD transition may be instant by policy, or advance `t` from 0 to 1
  without cracks, geometric pops, or lighting discontinuity at edges.
- L19 imagery over L13 geometry has no cracks, no inverted UVs, and does not
  require loading all of L19.
- A click `(u, v)` unambiguously returns ray, world `(x, y, z)`, and
  longitude/latitude/height with declared CRS and datum, or reports no hit.
- Camera changes reprioritize work without blocking render or holding the model
  lock during I/O.
- CPU/GPU resources are freed by their owner; HUD distinguishes measured from
  estimated values.
- CPU and GPU routes produce equivalent results within documented tolerances,
  and CPU works without a compute backend.
- The OpenGL implementation and any future native backend consume the same
  regression `DrawPacket` set through the defined bridge boundary.
- MCP/offline export can freeze camera, scene, projection, sources, and data
  version in a reproducible snapshot.

## Decisions and Remaining Open Decisions

### Adopted decisions

1. **Canonical vertical datum:** WGS84 ellipsoidal height, with FABDEM EGM2008
   orthometric values normalized at source load using the committed global grid.
2. **Google Earth imagery coverage:** global. Its tiling/CRS metadata is still
   detected and validated rather than inferred from coverage alone.
3. **OSM ingestion:** a separate preprocessing tool imports the binary planet
   release and emits binary, image-like vector tiles. That tile payload format,
   metadata, and style schema remain intentionally unspecified for now.
4. **Native backends:** Vulkan and Metal, if pursued, belong to a separate
   C++ project. This Java repository retains only backend-neutral draw/compute
   contracts and a future bridge boundary; it does not implement either API.

### Google Earth imagery: alternatives to decide

| Topic | Alternative A | Alternative B | Alternative C | Required evidence |
| --- | --- | --- | --- | --- |
| Horizontal CRS | EPSG:3857 / Web Mercator | EPSG:4326 geographic plate carrée | custom/project-local CRS | inspect tile metadata, control points, and world edge behavior |
| Tile format | raster image files (PNG/JPEG/WebP) | custom binary with image payload | indexed archive/database | inspect magic bytes, dimensions, alpha, compression, and sidecars |
| Root level | root tile covers complete world at level 0 | first stored level is an offset from logical root | multiple roots/face-based globe layout | discover directory tree, filenames, count, and bounding coverage |
| Y convention | XYZ: row 0 at north, increasing south | TMS: row 0 at south, increasing north | nonstandard/local index transform | compare known landmarks or metadata against adjacent tiles |

The preferred path is not chosen yet: implement a read-only discovery tool that
samples metadata and four geographically distinct landmark tiles. It will
produce a `SourceDescriptor` with explicit CRS, tile extent, root-level
offset, Y convention, image encoding, and validation evidence. Only then will
the imagery adapter be implemented.

### Still open

1. Choose the initial projection set and support criteria: global/local,
   conformality/area/distance, poles, and antimeridian.
2. Define the binary vector-tile payload, metadata, and minimum OSM style after
   the preprocessing tool contract exists.
3. Set triangle, patch, active-request, and latency budgets using real hardware
   profiles.

