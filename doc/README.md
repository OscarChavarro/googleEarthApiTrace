# Pipeline Data Contracts

This document specifies the on-disk data contracts between consecutive programs of the
pipeline described in the [repository README](../README.md). It is meant to let an
agentic coding agent reconstruct the necessary context to perform maintenance on any one
program **without changing the contracts** other programs depend on — the pipeline is
functional end to end, and the contracts below are the part that must stay stable.

Each section names a producer and its consumer(s), the exact files/fields involved, and
the invariants a maintenance change must preserve. Examples are taken from a real capture
session at `/media/ramdisk/output` (paths below use that default; the actual root is
`output.directory` in each Java project's `application.properties`).

## Quadtree conventions used throughout

All tile identities, `uncles` relationships and pyramidal-image paths share one quadrant
convention (see [`11_pathPlanner`](../11_pathPlanner/README.md) route generation and
[`32_pyramidalImageExporter`](../32_pyramidalImageExporter/README.md) export):

```
NW | NE          3 | 2
---+---   i.e.   --+--
SW | SE          0 | 1
```

A quadtree path is a string of quadrant digits without the leading root `0`, e.g. `"0021"`
under root `"0"` means: from the root, take quadrant `0` (SW), then `0` (SW), then `2`
(NE), then `1` (SE). Levels `0..5` are the "top-level"/globe strip levels (sizes `1x1` up
to `32x32`); deeper levels come from `matrix_<n>` layers anchored through `uncles`.

An "uncle" relationship (`ToUncleRelationship(direction, uncleContentId)`) links a tile to
the immediately coarser tile that contains it in one of its 4 quadrants; `direction` names
that quadrant (`NORTH_EAST`, `WEST_NORTH`, etc.) and, once the uncle's own absolute path is
known, the tile's path is the uncle's path with that quadrant digit appended. This is the
mechanism that stitches deep tiles (captured at high zoom, with no direct global
coordinate) back into the global quadtree.

## Pipeline graph

```
11_pathPlanner ─────KML "turtle" waypoints────────► 13_googleEarthController
12_fileSystemChangesDetector ──stdout activity────► 13_googleEarthController
13_googleEarthController ──drives Google Earth UI, causing OpenGL traffic──► 01_tracer

01_tracer ──.trace + per-frame gl.txt/manifest.txt/blobs──┬──► 21_traceLogSplitter (optional re-split)
                                                            └──► 22_dumpAnalyzer

21_traceLogSplitter ──per-frame gl.txt (alternate source)──► 22_dumpAnalyzer

22_dumpAnalyzer ──frame.json (per frame) + topLevelTiles.json (root)──► 23_frameTextureNormalizer
                                                                       └──► 32_pyramidalImageExporter (frame.json fallback only)

23_frameTextureNormalizer ──matrix.json (per frame) + westCutters.json (root)──► 31_matrixMerger

31_matrixMerger ──matrix_<n>/matrixLayer.json + tile PNGs──► 32_pyramidalImageExporter

22_dumpAnalyzer ──topLevelTiles.json──────────────────────► 32_pyramidalImageExporter

32_pyramidalImageExporter ──<inputFolder>/pyramidalImage quadtree of PNGs──► 41_planetViewer
```

---

## Contract 0a: `11_pathPlanner` → `13_googleEarthController`

- **Medium**: `~/.googleearth/myplaces.kml`, folder `turtle`.
- **Producer**: `11_pathPlanner` replaces the `turtle` folder contents with a route curve
  (`spiral`/`zigzag`/`globe`) plus altitude-calibration and zero-longitude-seam markers.
- **Consumer**: the user double-clicks the first placemark in Google Earth to start
  navigation there; `13_googleEarthController` reads the same KML file only to count total
  placemarks in `turtle` for its progress label and, when that count can be read, to stop
  automatically after advancing that many steps — it does not otherwise interpret the
  route, it just advances through whatever is currently selected in Google Earth.
- **Invariant**: placemarks must remain inside a folder literally named `turtle` directly
  under `~/.googleearth/myplaces.kml`'s root, since that is the only thing both programs
  agree on.

## Contract 0b: `12_fileSystemChangesDetector` → `13_googleEarthController`

- **Medium**: child-process stdout/stdin pipe (`12_fileSystemChangesDetector` is spawned
  by `13_googleEarthController`).
- **Producer**: prints one line `Updated at <timestamp>` per `IN_CREATE`/`IN_MOVED_TO`
  `inotify` event anywhere under the watched tree (`/media/ramdisk/output` in practice).
- **Consumer**: `13_googleEarthController` resets its inactivity timer on each line; when
  no line arrives for its configured timeout, it sends `DOWN` + `ENTER` to advance Google
  Earth to the next placemark.
- **Invariant**: the exact literal prefix `Updated at ` on stdout, and the literal command
  `exit` on stdin, are the whole protocol — do not change either format without updating
  both programs. Note, however, that the current controller implementation does not use
  the detector's stdin shutdown path; it stops the child process directly.
- **Current startup behavior**: if the detector executable cannot be started, the
  controller does not enter a degraded fixed-timer mode; it aborts the automatic session
  start.

## Contract 0c: `13_googleEarthController` → `01_tracer`

Not a file contract: the controller drives the live, already-running, `apitrace`-wrapped
Google Earth process through synthetic keyboard events. `01_tracer` reacts to whatever
OpenGL traffic this navigation produces; there is no direct data handoff, only causal
pacing (the controller only advances once tracer write activity — observed indirectly
through Contract 0b — has quieted down, so textures are not read mid-write).

---

## Contract 1: `01_tracer` → {`21_traceLogSplitter`, `22_dumpAnalyzer`}

This is the tracer's runtime export, written live while Google Earth runs under
`apitrace trace`.

- **Producer**: `01_tracer` (modified `apitrace`).
- **Consumers**: `21_traceLogSplitter` (re-derives `gl.txt` from the `.trace`, see
  Contract 2) and `22_dumpAnalyzer` (reads the per-frame folders directly, see Contract 3).
- **Location**: `/media/ramdisk/output/%05d/` (`output.directory`), one folder per frame
  number, plus the standard `.trace` file at the traced program's own location.
- **Contents of each frame folder**:
  - `gl.txt`: one apitrace-style text line per intercepted GL/GLX call, e.g.:
    ```
    9 glDrawElements(mode = GL_TRIANGLE_STRIP, count = 4, type = GL_UNSIGNED_SHORT, indices = blob(...))
    ```
    Written directly by the tracer unless `TRACE_WRITE_GLTXT=0`. Frame boundary is
    `glXSwapBuffers`.
  - `manifest.txt`: one `key=value ...` line per exported blob, `kind=` one of
    `draw_elements`, `vertex_attrib` (others may exist), each pointing to a `.bin` file by
    absolute path, e.g.:
    ```
    kind=draw_elements frame=9 call=2552 parserCall=1 file=/media/ramdisk/output/00009/drawElements_indices_call_2552.bin mode=5 type=5123 blobPtr=... bytes=804
    kind=vertex_attrib frame=9 call=2551 parserCall=0 file=/media/ramdisk/output/00009/glVertexAttribPointer_vertexAttrib_call_2551.bin attribIndex=0 blobPtr=... bytes=118188
    ```
  - Binary blobs referenced by `manifest.txt`: `drawElements_indices_call_<call>.bin`
    (`GL_UNSIGNED_SHORT` index buffer) and `glVertexAttribPointer_vertexAttrib_call_<call>.bin`
    (vertex data for `attribIndex`, position data is `attribIndex=0`).
  - Texture images, named `<width>x<height>_<textureId>.png` (uncompressed, decodable
    formats) or `.dds` (`GL_COMPRESSED_RGB_S3TC_DXT1_EXT`), e.g. `256x256_159.png`,
    `1024x256_1.png`. `textureId` is `THE_TextureId` at export time.
- **Invariants a maintenance change must preserve**:
  - Frame folder names stay 5-digit, zero-padded, matching the numeric frame id
    (`%05d`, e.g. `00009`).
  - `manifest.txt` line format (`key=value`, space-separated) and its `file=` absolute
    paths must keep matching the files actually on disk — `22_dumpAnalyzer` resolves blobs
    by that path.
  - Texture filename pattern `<W>x<H>_<textureId>.<ext>` must stay parseable, since both
    `22_dumpAnalyzer` and, indirectly, `23_frameTextureNormalizer`/`31_matrixMerger`
    (through `frame.json`/`matrix.json` `textureFile` fields) key off exact paths produced
    here, not off re-derived ones.
  - Line-oriented `gl.txt`, with `glXSwapBuffers` as the sole frame-boundary marker, is
    relied on by both `21_traceLogSplitter`'s splitting logic and `22_dumpAnalyzer`'s ANTLR
    grammar (`GlTrace.g4`).

## Contract 2: `21_traceLogSplitter` (alternate source of `gl.txt`)

- **Input**: a full apitrace text dump obtained with `apitrace dump googleearth-bin.trace >
  textfile.txt` from the `.trace` produced by `01_tracer` — this is the *offline*,
  regenerate-from-`.trace` path, used when live `gl.txt` was disabled
  (`TRACE_WRITE_GLTXT=0`) or lost, rather than the normal live-capture path.
- **Output**: `./output/%05d/gl.txt`, relative to the current working directory,
  split on `glXSwapBuffers` boundaries — same per-frame folder numbering and same `gl.txt`
  format as Contract 1's live output.
- **Consumer**: `22_dumpAnalyzer`, exactly as in Contract 3 — it cannot tell the two
  sources of `gl.txt` apart, since the format is identical by construction.
- **Invariant**: `21_traceLogSplitter`'s output tree must be merged into (or run directly
  inside) the same `output.directory` root `22_dumpAnalyzer` is configured to scan,
  because this splitter does not know about `manifest.txt`/blobs — those still only come
  from the original `01_tracer` run of the same session (Contract 1), and `22_dumpAnalyzer`
  expects both to coexist under the same frame folder.

## Contract 3 (core contract): {`01_tracer`, `21_traceLogSplitter`} → `22_dumpAnalyzer`

This is the contract that turns raw OpenGL call logs into a structured, per-frame tile
model with quadtree neighborhood information — everything downstream depends on it.

- **Producer**: `22_dumpAnalyzer`.
- **Consumers**: `23_frameTextureNormalizer` (per-frame `frame.json`), and
  `32_pyramidalImageExporter` (root `topLevelTiles.json`, plus a documented fallback read
  of per-frame `frame.json`, see below).
- **Inputs** (per frame folder under `output.directory`, `frame` = folder name as integer,
  `filename` = absolute path to that folder's `gl.txt`): `gl.txt`, `manifest.txt` and blobs
  from Contract 1/2.
- **Outputs**:
  - `<output.directory>/%05d/frame.json`, one per frame folder that has a `gl.txt`. Top
    level: `id` (frame number), `tiles[]`, `lines[]`, `camera` (`projectionMatrix`,
    `modelViewMatrix`, `googleCamera` with position/front/left/up vectors, projection
    mode, fov, near/far planes). Each entry in `tiles[]`:
    - `contentId`: `"<frameId>_<tileNumber>"`, e.g. `"330_6"` — this is the identity used
      everywhere downstream (`matrix.json` tile `id`s of the form `"<frame>_<n>"`, and
      `uncles[].uncleContentId`).
    - `textureFile`: absolute path to the texture PNG/DDS from Contract 1 (may point into
      a *different* frame folder than the tile's own, when a texture is reused across
      frames — see `00330` tile `330_6` pointing at `/media/ramdisk/output/00010/...`).
    - `southNeighbor`/`northNeighbor`/`eastNeighbor`/`westNeighbor`: same-level
      quadtree neighbor `contentId`s (or `null`), computed within the frame's tile set.
    - `primitive`, `vertexArraySize`, `indexArraySize`, `numberOfPoints`,
      `triangleStrip.vertices[]` (`x,y,z,u,v`), `modelViewMatrix`: geometry needed to
      reconstruct the tile's screen-space quad and its texture-coordinate mapping.
    - `skipped`/`skipReason`: set when the tile was excluded from processing (reason is
      free text for diagnostics).
    - `uncles[]`: cross-level neighbor relationships (`direction`,
      `uncleContentId`) — see "Quadtree conventions" above. Present here (not only in
      `matrix.json`) because that is where the raw geometry needed to detect containment
      lives.
    - `syntheticGlobeLevelTile`/`fullResolutionWithRespectToTexture`: flags consumed by
      the globe-level (top-level) tile-set processing described next.
  - `<output.directory>/topLevelTiles.json`: one JSON object, `byStripId` maps strip id
    (string, stringified integer) to `{ id, pathFromRoot, row, col, appearances[] }`.
    `pathFromRoot`/`row`/`col` are legacy and **no longer read** by
    `32_pyramidalImageExporter` (see Contract 6). `appearances[]` is what matters: each
    entry is `{ frameId, imageId, imagePath, texCoord: { u0, v0, u1, v1 } }`, one per frame
    in which that globe strip was observed, `imagePath` pointing at a Contract-1 texture
    file and `texCoord` giving that texture's sub-rectangle covering the strip (OpenGL
    convention, `v=0` at the image's south/bottom edge). Every strip covers exactly
    `1/32 x 1/32` of world texture space (the level-5 quadtree grid); this is the geometric
    fact `32_pyramidalImageExporter` uses to reconstruct levels `0..5`.
  - `<output.directory>/globalPatches.json`: `frames[]` of
    `{ frameId, tileTriangleStripsToCountMap[] }` (histogram of triangle-strip sizes per
    frame). **Diagnostic only** — no downstream program reads this file back; free to
    change its shape without breaking the pipeline, but keep it if agents rely on it for
    debugging capture sessions.
- **Invariants**:
  - `contentId` format `"<frameId>_<n>"` (no zero-padding on `frameId` here, unlike folder
    names) must stay stable: it is both a lookup key (`matrix.json` `id`, `uncles[].uncleContentId`)
    and, in `32_pyramidalImageExporter`'s `DanglingUncleBridge`, parsed back apart
    (`frameToken` + `tileNumber`) to re-open `<output.directory>/<frameId zero-padded to
    5 digits>/frame.json` and look up a `tiles[]` entry whose `contentId` is
    `"<frameId>_<tileNumber>"` again — so this fallback path depends on `frame.json` files
    remaining on disk at their original location for the lifetime of a session, even after
    `23_frameTextureNormalizer`/`31_matrixMerger` have filtered/copied/renamed everything
    else.
  - `topLevelTiles.json`'s `appearances[].texCoord` and the `1/32 x 1/32` strip-size
    invariant are load-bearing for `32_pyramidalImageExporter`'s top-level reconstruction;
    changing strip granularity or texCoord convention breaks levels `0..5`.
  - Fatal parse errors must keep exiting with `System.exit(666)` after printing the failing
    file's absolute path — downstream batch tooling / agents may special-case this exit
    code to detect a corrupt frame.

## Contract 4: `22_dumpAnalyzer` → `23_frameTextureNormalizer`

- **Producer**: `22_dumpAnalyzer` (`frame.json` per frame, see Contract 3).
- **Consumer**: `23_frameTextureNormalizer`, together with the texture files from
  Contract 1 (same `output.directory`).
- **Consumption**: for each frame folder with a `frame.json`, tiles are filtered by
  connected components and by geometric null-neighbor pruning; frames with too few
  surviving tiles are dropped entirely (their `matrix.json`, if any existed from a
  previous run, is deleted).
- **Invariant**: `23_frameTextureNormalizer` relies on the same `contentId`,
  `textureFile`, and same-level neighbor fields (`southNeighbor`/etc.) described in
  Contract 3 — it does not re-derive neighborhood, only filters and regroups it.

## Contract 5: `23_frameTextureNormalizer` → `31_matrixMerger`

- **Producer**: `23_frameTextureNormalizer`.
- **Consumer**: `31_matrixMerger`.
- **Outputs** (per frame folder):
  - `matrix.json` (fallback read: legacy `matrix.txt`): `{ rows, cols, tiles[] }`, `tiles[]`
    entries `{ id, i, j, textureFile, uncles[] }`. `id` is the surviving tile's `contentId`
    string from Contract 3 (`"00328_163"` in the real sample — note this occurrence uses
    the zero-padded frame folder name as prefix, unlike the un-padded `frameId` used inside
    `frame.json`'s own `contentId`; both forms appear across the pipeline and consumers
    parse trailing digits after the last `_`, so treat the whole prefix before it as an
    opaque frame token rather than parsing it as an integer). `i`,`j` are the tile's
    position inside this frame's local matrix (`0,0` = one corner, consistent with
    `rows`/`cols`). `textureFile` is still the Contract-1 texture path (frame folders are
    not renamed by this stage — legacy numeric `tileId` is also accepted on read for
    backward compatibility).
  - `uncles[]` per tile: same `{ direction, uncleContentId }` shape as Contract 3,
    propagated from `frame.json` (see the real example above: tile `"00328_161"` has an
    uncle at `WEST_NORTH` pointing to `"00329_67"`).
  - `<output.directory>/westCutters.json` (root, session-wide): the set of tiles marked as
    "west cutters" (tiles that straddle the `-180°` meridian and must be split before
    merging matrices spatially). Written by `23_frameTextureNormalizer` (interactive `c`
    key) and by `31_matrixMerger` (which also propagates/rewrites it, see its own README).
  - Texture PNGs are normalized (deduplicated) in place: `23_frameTextureNormalizer`
    computes a duplicated-texture filename mapping (its own cache,
    `textureFileNamesMap.json`, not consumed by any other program) and SHA-256
    `.signature` files (also an internal cache) but does not move/rename the original
    texture files — `matrix.json` `textureFile` entries still point at Contract-1 paths.
- **Invariants**:
  - `matrix.json` tile `id` must keep matching a `contentId` reachable from `frame.json`
    (directly, or through the `frame`+`tileNumber` parse used by `DanglingUncleBridge`),
    since `32_pyramidalImageExporter` may need to resolve it later even after
    `31_matrixMerger` has copied/renamed the texture (Contract 6).
  - `rows`/`cols`/`i`/`j` must stay 0-based and dense (no gaps) within one frame's matrix,
    since `31_matrixMerger`'s merge algorithms assume a rectangular grid.
  - `westCutters.json`'s schema is shared verbatim between `23_frameTextureNormalizer` and
    `31_matrixMerger` — both read and write it, so a format change must land in both
    projects atomically.

## Contract 6: `31_matrixMerger` → `32_pyramidalImageExporter`

- **Producer**: `31_matrixMerger`, via `ResultsExporter` (writes once at startup, after
  `--mode auto` processing if requested — interactive merges done afterwards in the GUI
  are **not** re-exported).
- **Consumer**: `32_pyramidalImageExporter`, via `MatrixLayerReader`.
- **Location**: `<exportFolder>/matrix_<n>/`, one folder per surviving merged matrix
  (`n` = 0-based export order), containing:
  - `matrixLayer.json`: versioned envelope (current `contractVersion: 2`) with:
    - `frameId`: representative frame of the merged matrix.
    - `hierarchyLevel`: real depth in the matrix hierarchy (`0` for the first matrix,
      increasing by one through each parent edge; it is not inferred from `<n>`).
    - `parentMatrixIndex`: index `<n>` of the immediate parent, or `null` only for a
      genuinely disconnected root.
    - `hierarchyUnclesByTileId`: compatibility map `tileId -> [uncleId]` used for graph
      ordering and diagnostics.
    - `hierarchyRelationshipsByTileId`: lossless map
      `tileId -> [{direction, uncleContentId}]`. It preserves the quadrant direction even
      when a merge keeps a duplicate tile record that originally had no `uncles`.
    - `matrices`: one `{frameId, rows, cols, tiles[]}` matrix with
      `tiles[].id/i/j/textureFile/uncles[]`. `textureFile` points at the exported copy
      `<exportFolder>/matrix_<n>/<tileId>.png`, not the Contract-1 path.
  - One `<tileId>.png` per tile, copied from the tile's original texture.
- **`<exportFolder>` becomes `32_pyramidalImageExporter`'s `<inputFolder>` argument.**
- **Invariants**:
  - `tileId` must stay parseable back into `"<frameFolder>_<tileNumber>"` form by
    `DanglingUncleBridge.originalTexturePathOf` (regex `\d{5}_\d+`), which reconstructs the
    *original* Contract-1 texture path
    (`outputDirectory/<frameFolder>/256x256_<tileNumber>.png`) from the id alone, to cross
    -reference the top-level catalogue (Contract 3's `topLevelTiles.json`) by original
    path, since the copied `matrix_<n>/<tileId>.png` path is not in that catalogue.
  - `uncles[]` must survive the merge/copy step unchanged in shape, since anchoring
    (Contract 7) depends on walking them.
  - `hierarchyRelationshipsByTileId` is authoritative when it contains relationships
    lost from `tiles[].uncles`; the consumer merges both sets by value.
  - A hierarchy root without recorded `uncles` may be joined to an earlier matrix only
    by `31_matrixMerger`'s visual parent inference: child textures are compared with
    parent quadrants, matches must pass RMSE and best/second confidence thresholds, and a
    strict majority must agree on one rigid `(parent,rowOffset,colOffset)`. Accepted
    relationships are persisted in both hierarchy maps. A non-confident root stays
    disconnected; ordering alone is never an anchor.
  - `parentMatrixIndex` must refer to an earlier exported matrix and
    `hierarchyLevel == parent.hierarchyLevel + 1`.
  - Folder naming `matrix_<n>` (not zero-padded) and file naming `matrixLayer.json` (not
    `matrix.json`) are both significant — `32_pyramidalImageExporter` only recognizes this
    exact layout.

## Contract 6b: `22_dumpAnalyzer` → `32_pyramidalImageExporter` (direct)

In addition to feeding `23_frameTextureNormalizer`/`31_matrixMerger` (Contracts 3-6),
`22_dumpAnalyzer`'s output is read directly by `32_pyramidalImageExporter`, twice:

- `topLevelTiles.json` (root of `output.directory`, configured independently of
  `<inputFolder>`): the *only* source for reconstructing quadtree levels `0..5`
  (`TopLevelsMatricesImporter`), see Contract 3. This works even if `<inputFolder>` has no
  `matrix_<n>` subfolders at all.
- Individual `<output.directory>/<frameFolder>/frame.json` files: read on demand by
  `DanglingUncleBridge` as a fallback when a `matrix_<n>` tile's `uncles[]` points at an
  `uncleContentId` that did not survive into any loaded `matrix_<n>` layer (i.e. it was
  filtered out by `23_frameTextureNormalizer`). The bridge looks up that `contentId` in the
  original `frame.json`, recovers its canonical `textureFile`, and uses that texture's
  identity — either directly in the top-level catalogue, or as an alias of a tile that did
  survive — to anchor the chain anyway. **This means `frame.json` files must not be
  deleted from `output.directory` for the lifetime of a session**, even though nothing
  after `23_frameTextureNormalizer` normally needs to read them again.

## Contract 7: `31_matrixMerger`/`22_dumpAnalyzer` → `32_pyramidalImageExporter` export

Internal to `32_pyramidalImageExporter`, but this is the contract that decides which tiles
make it into the final pyramidal image, so it is worth stating explicitly:

- A tile is exportable only if `TileRootPathResolver` can anchor it to an absolute
  quadtree path. The root is exactly `"0"`; every descendant begins with that marker and
  adds one quadrant digit per level (e.g. `"0021"`):
  - Directly, if its own `id` already is such a path (true by construction for every
    top-level tile synthesized from `topLevelTiles.json`).
  - Transitively, if one of its `uncles[]` resolves (directly, through Contract 6b's
    bridge, or through a chain of several uncle hops resolved one hop per fixpoint pass)
    to an already-anchored tile: the child's path is the uncle's path with the matching
    quadrant digit appended.
  - `hierarchyRelationshipsByTileId` is merged into `tiles[].uncles` before resolution.
  - Once a strict majority chooses one `(level,rowOffset,colOffset)` for a rigid matrix,
    that grid canonicalizes every path in the matrix. Minority/outlier anchors and an
    individually ambiguous tile are corrected by the winning grid instead of being
    allowed to create duplicate output paths.
  - Before writing, the exporter builds a unique `fullPath -> tile` manifest. A local
    native tile deliberately replaces a derived TOP cell at the same path; any duplicate
    between incompatible peers aborts the export before the first PNG is written.
  - A tile with no resolvable path (directly or transitively) is skipped.
- **Invariant**: `32_pyramidalImageExporter` resolves paths from ids, relationships and
  the Contract-6b bridge; visual inference happens upstream in `31_matrixMerger` and its
  accepted result is explicit Contract-6 metadata. Any id-format change must remain
  consistent across `frame.json`, `matrix.json`, `matrixLayer.json` and the TOP catalogue.

## Contract 8: `32_pyramidalImageExporter` → `41_planetViewer`

This is the final contract: the actual deliverable of the whole pipeline.

- **Producer**: `32_pyramidalImageExporter`, via the `e` key or `--export`, writing to
  `<inputFolder>/pyramidalImage` (created on first export; always inside the input folder,
  session-local — the tool never reads or writes any other pyramidal image).
- **Consumer**: `41_planetViewer`, and, potentially, any future cross-session merging tool
  (not implemented yet — explicitly out of scope for `32_pyramidalImageExporter`).
- **Format** (the "folder-based pyramidal image" format, shared by both programs):
  - Root tile: `0.png`, directly inside the pyramidal-image folder.
  - Its 4 children: subfolders `00`, `01`, `02`, `03` (quadrant digit convention above:
    `0`=SW, `1`=SE, `2`=NE, `3`=NW), each holding its own tile image (`00/00.png`) and,
    recursively, its own 4 child folders one level deeper (`00/000/`, `00/001/`, ...).
  - Every tile image is a `256x256` PNG, nearest-neighbor-cropped from its source
    texture's sub-rectangle (the same sub-rectangle used to texture that tile on screen).
- **Invariants**:
  - The root file must be named exactly `0.png`; child folder names must be the single
    quadrant digit appended to the parent's path (`RootPathResolver`'s path strings and the
    on-disk folder names use the same digit alphabet `0-3`).
  - Tile images must stay `256x256` PNG — `41_planetViewer` (and `32_pyramidalImageExporter`
    itself when re-reading during LOD/culling in its own viewer) assumes this fixed size
    for texture budgeting and projected-area LOD thresholds.
  - A pyramidal-image folder is read-only input for `41_planetViewer` (and for any
    consumer): nothing downstream ever writes back into it, so `32_pyramidalImageExporter`
    remains the sole writer for a given session's `pyramidalImage` folder, safe to
    re-export (`new`/`rewritten` slots) without external interference.
  - Multiple pyramidal-image folders (e.g. from different capture sessions) can be passed
    to `41_planetViewer` independently, stacked with opacity/z control — this only works
    because each one independently satisfies the format above; the pipeline does not yet
    merge sessions into one consolidated pyramid (see the repository README's "Final
    Gigapixel Build stage" note).

---

## Summary table

| # | Producer | Consumer(s) | Key artifact(s) |
|---|---|---|---|
| 0a | `11_pathPlanner` | `13_googleEarthController` | `~/.googleearth/myplaces.kml` (`turtle` folder) |
| 0b | `12_fileSystemChangesDetector` | `13_googleEarthController` | stdout `Updated at ...` / stdin `exit` |
| 1 | `01_tracer` | `21_traceLogSplitter`, `22_dumpAnalyzer` | `%05d/gl.txt`, `manifest.txt`, texture/geometry blobs |
| 2 | `21_traceLogSplitter` | `22_dumpAnalyzer` | `%05d/gl.txt` (regenerated) |
| 3 | `01_tracer`+`21_traceLogSplitter` | `23_frameTextureNormalizer`, `32_pyramidalImageExporter` | `%05d/frame.json`, root `topLevelTiles.json` |
| 4 | `22_dumpAnalyzer` | `23_frameTextureNormalizer` | `frame.json` (consumed) |
| 5 | `23_frameTextureNormalizer` | `31_matrixMerger` | `%05d/matrix.json`, root `westCutters.json` |
| 6 | `31_matrixMerger` | `32_pyramidalImageExporter` | `<exportFolder>/matrix_<n>/matrixLayer.json` + `<tileId>.png` |
| 6b | `22_dumpAnalyzer` | `32_pyramidalImageExporter` | `topLevelTiles.json`, `frame.json` (uncle-bridge fallback) |
| 8 | `32_pyramidalImageExporter` | `41_planetViewer` | `<inputFolder>/pyramidalImage/` quadtree of PNGs |

## Appendix: Blist quadrant/level notation

`Blist` (used in some internal path representations) is a quadtree path without the
initial `0` root digit. Level `0` is the root; each following digit selects a quadrant
using the convention in "Quadtree conventions used throughout" above:

```
NW | NE          3 | 2
---+---   i.e.   --+--
SW | SE          0 | 1
```
