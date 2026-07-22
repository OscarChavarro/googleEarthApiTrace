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

All absolute quadtree paths, `uncles` direction semantics and pyramidal-image folders use
one quadrant convention (see [`11_pathPlanner`](../11_pathPlanner/README.md) route
generation and [`32_pyramidalImageExporter`](../32_pyramidalImageExporter/README.md)
export). Capture `contentId` values such as `"328_163"` are opaque identities, not
quadkeys:

```
NW | NE          3 | 2
---+---   i.e.   --+--
SW | SE          0 | 1
```

A full quadkey includes the root marker `0`: the root is exactly `"0"`, and a tile at
level `L` has `L + 1` digits. For example, `"0021"` is a level-3 tile: after the root
marker, take quadrant `0` (SW), then `2` (NE), then `1` (SE). Internal `Blist` values omit
the root marker; do not interchange the two representations without adding/removing that
marker explicitly. Levels `0..5` are the "top-level"/globe strip levels (sizes `1x1` up
to `32x32`); deeper levels come from `matrix_<n>` layers anchored through `uncles`.

Matrix row `0` is north and rows increase southward. Matrix column `0` is merely the
first local column and columns increase eastward; it is not necessarily the absolute
westernmost quadtree column for a partial matrix. A matrix spanning the complete world
(`cols == 2^L`) is the exception: after west-cutter normalization its column `0` is the
antimeridian and therefore has absolute `colOffset = 0`. At level `L`, absolute longitude
columns are cyclic modulo `2^L`, while latitude rows never wrap.

An "uncle" relationship (`ToUncleRelationship(direction, uncleContentId)`) links a fine
tile to the immediately coarser texture from which it descends. `direction` identifies
the quadrant of that texture used by the fine tile, so the child quadkey is the uncle
quadkey followed by one digit:

| Directions | Fine child quadrant |
|---|---|
| `WEST_SOUTH`, `SOUTH_WEST` | SW (`0`) |
| `EAST_SOUTH`, `SOUTH_EAST` | SE (`1`) |
| `EAST_NORTH`, `NORTH_EAST` | NE (`2`) |
| `WEST_NORTH`, `NORTH_WEST` | NW (`3`) |

The two spellings reflect the geometry axis that detected the relationship; they encode
the same quadrant. This relative relationship is the mechanism
that stitches deep tiles (captured at high zoom, with no direct global coordinate) back
into the global quadtree, but it cannot provide an absolute position without at least one
absolute seed somewhere in the connected relationship graph.

### Absolute anchoring of a rigid matrix

For an anchored tile at local cell `(i,j)` and absolute quadtree cell `(row,col)`, a
matrix votes for one common anchor:

```
rowOffset = row - i
colOffset = floorMod(col - j, 2^level)
```

The winning anchor places every matrix tile with `absoluteRow = i + rowOffset` and
`absoluteCol = floorMod(j + colOffset, 2^level)`. A strict majority is required so that
duplicate-looking textures or bad individual relationships cannot shift the whole layer.
Votes are evaluated at the strongest available evidence tier: direct quadkey/catalogue
anchors first, an already canonicalized grid second, and relative `uncles` last. Lower
tiers may fill a matrix that has no stronger anchor, but cannot outvote an absolute seed.
A full-world matrix (`cols == 2^level`) is canonicalized with `colOffset = 0` before any
of its `uncles` relationships are used to anchor a child layer. This is not an inference
from one noisy tile: it is the coordinate contract established by west-cutter splitting
and full-world matrix reconstruction. Partial matrices still vote for a cyclic longitude
offset. If no complete anchor tuple has a strict majority, the resolver can combine
independent strict majorities for level, row offset and cyclic column offset. Offsets that
differ by a multiple of `2^level` are equivalent (for example, `1` and `-63` at level 6).
If relative relationships do not provide those strict majorities, they cannot place the
matrix. The exporter may instead compare distributed native child tiles with quadrants of
an already canonicalized imported layer from the same session. That visual evidence also
requires at least three individually unambiguous matches and a strict majority for one
rigid anchor; accepted descendant anchors are retained and reused by the export pass.

### Full-world phase regression and depth-dependent displacement

A real failure reproduced with `/media/ramdisk/output` and `/tmp/matrix` showed why the
full-world exception must be applied before resolving descendants. The level-5 matrix had
`cols = 32` but retained a voted cyclic phase of `31`, equivalent to `-1`. Its tiles were
therefore exported one cell west of their known top-level positions. Quadtree refinement
doubles coordinates at every edge:

```
childCol = 2 * parentCol + childEastBit
```

Consequently, an erroneous parent offset `e` becomes `2e` in the child. The observed
displacements were `-1` column at level 5, `-2` at level 6 and `-4` at level 7. This can
look like a new error that depends on tree depth, but it is one bad phase being scaled by
the quadtree transformation; after `d` descendant edges the error is `2^d * e`.

The resolver must therefore perform these operations in this order:

1. Load direct/catalogued absolute seeds.
2. Canonicalize every full-world rigid matrix to `colOffset = 0`.
3. Resolve coarse-texture quadrant relationships into the next level.
4. Canonicalize each newly anchored rigid matrix before continuing the fixpoint walk.

This precedence is required for partial matrices too. A later session contained a
13-column level-5 matrix whose noisy relative anchors voted `colOffset = 30`; treating
that vote as absolute shifted the partial matrix by `-1`, then its level-6 and level-7
descendants by `-2` and `-4`. `TopLevelVisualAnchorResolver` supplied the missing absolute
evidence by comparing distributed native tiles with the texture sub-rectangles of
reconstructed levels `0..5`. It accepts only individually unambiguous matches and requires
at least three votes plus a strict majority for one rigid offset. All eight sampled tiles
voted for `31`, positioning the three layers at offsets `31`, `0` and `0` modulo their
respective widths without relying on the full-world special case. It does not read the
destination/previous-session pyramid.

The repaired export was checked by SHA-256 identity against
`/samples/datasets/googleEarth/toplevel`: all 142 shared level-5 tiles and all 625 shared
level-6 tiles had coordinate delta `(row=0,col=0)`. Compared with the defective
`level3part1` export, the corrected paths moved east by `+1`, `+2` and `+4` columns at
levels 5, 6 and 7 respectively; all 1104 level-7 tile identities agreed on the `+4`
correction. This hash-to-quadkey comparison is a stronger regression check than visual
inspection of a coastline because it verifies every uniquely matching tile.

The later `42_pyramidalImageMerger` regression using `/tmp/matrix/pyramidalImage` exposed
a different source for the same `-1`, `-4` signature: the exporter had started treating
an uncle as an adjacent parent and crossing its border. The real records prove the
containing-texture semantics directly. For example, `00067_92` resolves from coarse
quadkey `03100` plus `WEST_SOUTH` to `031000`, and `09136_1325` resolves from `0133302`
plus `WEST_NORTH` to `01333023`. Crossing a border placed those tiles one column west at
level 5 and four columns west at level 7 after propagation. With quadrant appending
restored, the regenerated delta shared 623 quadkeys with the destination and all 623 PNGs
were byte-identical; the previous export had 313 mismatches.

### Placement model to preserve in future changes

The five commits from `584878e` through `116601c` exposed several concepts that had been
represented by similar strings or coordinates even though they are not interchangeable.
Future code should keep these as separate logical types, even if Java currently represents
some of them as `String` or `int`:

| Concept | Meaning | May establish absolute position? |
|---|---|---|
| `TileId`/`contentId` | Opaque identity of captured content, e.g. `328_163` | No |
| `Quadkey` | Absolute root-to-node identity, matching `0[0-3]*` | Yes |
| local cell `(i,j)` | Position inside one rigid matrix | No |
| grid anchor `(level,rowOffset,colOffset)` | Transform from all local cells in one matrix to absolute cells | Yes, once accepted |
| uncle relationship | Fine tile to coarse-texture quadrant constraint | Only transitively from an absolute seed |
| pyramid file path | Serialization of a quadkey in the per-digit or legacy cumulative layout | No new information |

In particular, changing the folder representation of a quadkey, as in `6a23ee4`, must be
a pure serialization change. Readers may support both layouts during migration, but
neither directory names nor traversal order may participate in coordinate inference.

The placement algorithm is a precedence-controlled fixpoint over rigid matrices and must
preserve this evidence order:

1. Start with absolute quadkeys reconstructed from the top-level catalogue or otherwise
   supplied explicitly for this session.
2. Turn a visual match into an absolute seed only after several spatially distributed,
   individually unambiguous probes vote by strict majority for the same complete grid
   anchor. A single approximate image match is not an anchor (a byte-identical match to an
   already catalogued native tile is a different, direct identity case). Once accepted,
   retain the resulting `tileId -> Quadkey` map for the later export pass.
3. If a matrix spans all `2^L` columns, force `colOffset = 0`; if it spans all `2^L` rows,
   force `rowOffset = 0`. Apply this canonicalization before the matrix is allowed to seed
   descendants.
4. Resolve uncle constraints one level at a time by appending the quadrant from the
   direction table. When both images are readable, scale the fine image to one coarse
   quadrant and compare all four quadrants by RMS; only a declared quadrant attaining the
   minimum RMS for that image pair votes, without a global RMS threshold. Canonicalize at
   most one newly anchored matrix before returning to the relationship phase, so newly
   available parent paths participate before a descendant grid propagates.
5. Within a matrix, vote using only its strongest available evidence class. Absolute seeds
   outrank an accepted grid, and an accepted grid outranks uncle-derived candidates. Never
   let a larger number of weaker relative candidates outvote one absolute seed.
6. Stop without placing a matrix when the strongest evidence has no strict majority. It is
   valid to export a sparse pyramid; guessing an offset is not valid. Before writing, also
   require in-range rows and a unique `Quadkey -> native tile` manifest.

This is the generalized reason for the observed `-1`, `-2`, `-4` failures: an anchor error
is not local to one level. If a parent coordinate error is `(er,ec)`, after `d` descendant
edges it contributes `(2^d * er, 2^d * ec)` before child quadrant bits are added. Therefore
every regression test for placement must check at least one descendant level, not only the
matrix where an anchor was first selected.

The recent commit sequence should be read as a decision record:

| Commit | What changed | Stable conclusion |
|---|---|---|
| `584878e` | Added cyclic longitude, majority voting and rigid-grid propagation | Longitude wraps; matrix placement is one transform, not independent per-tile guesses |
| `3868e77` | Added tile selection to the coverage viewer | Diagnostic only; it did not change placement semantics |
| `6a23ee4` | Replaced cumulative quadkey folders with one folder per quadrant digit | Logical quadkeys and their disk layout are separate contracts |
| `37b95f3` | Restored zero phase for full-world matrices and canonicalized before descending | A normalized complete-world grid has a canonical origin; relative noise cannot redefine it |
| `116601c` | Added evidence precedence and visual anchoring for partial and descendant layers | Weak relationships cannot override stronger evidence; visual inference needs distributed consensus and must become an explicit retained anchor |

Minimum regression coverage for any change to placement, hierarchy import or pyramid
layout is consequently: all eight uncle directions; full-world row/column origin;
partial-matrix cyclic phase; direct
seed versus a majority of weaker candidates; tie/plurality rejection; propagation through
two descendant levels; and equivalent scanning of the current and migration folder
layouts. Dataset checks should compare stable tile identity/hash to quadkey and report
coordinate deltas per level, so a visually plausible but uniformly shifted result fails.

### Invalid assumptions — do not encode these in producers or consumers

- **An uncle path must be crossed into an adjacent parent cell.** False: the id names the
  coarser texture and the direction names its child quadrant. Crossing the border creates
  the observed depth-scaled displacement; `childPath = unclePath + quadrantDigit` is the
  contract.
- **A full-world matrix may preserve an arbitrary cyclic phase.** False for normalized
  pipeline matrices: west-cutter normalization makes local column zero the antimeridian.
  Preserving a noisy phase of `-1` shifts the next levels by `-2`, `-4`, and so on.
- **Longitude behaves like latitude.** False: columns wrap modulo `2^level`; rows are
  bounded and do not wrap. Negative and large longitude offsets may be valid equivalent
  representations.
- **Local `(i,j)` is an absolute world coordinate.** False: it becomes absolute only
  after applying a resolved rigid-grid offset.
- **`matrix_<n>` directory order establishes parentage or quadtree depth.** False: only
  explicit hierarchy relationships and absolute seeds establish placement. Folder order
  is an export/iteration order.
- **`hierarchyLevel` or `parentMatrixIndex` alone is an absolute quadkey.** False: those
  fields describe relative matrix hierarchy; they do not locate it on Earth. Legacy or
  disconnected data may also carry unknown values such as `-1`/`null`.
- **Uncle relationships alone are sufficient.** False: a connected graph of exclusively
  relative relationships still needs at least one absolute seed.
- **Equal image bytes or visually equal textures prove equal geographic position.**
  False: oceans and other repeated-looking cells exist. Content matching can propose an
  anchor, but rigid-grid majority and conflict checks must canonicalize the result.
- **A matrix is necessarily full/dense because it has `rows` and `cols`.** False: these
  fields define its coordinate extent; filtering and merging may leave empty cells.
- **Every reconstructed top-level cell owns a native exportable image.** False: viewer
  matrices may borrow an ancestor sub-rectangle. Only native `256x256` whole-texture
  tiles are written to the on-disk pyramid.
- **Legacy `topLevelTiles.json.pathFromRoot`, `row`, or `col` are authoritative.** False:
  current top-level placement is reconstructed from `appearances[].texCoord`.
- **A previous `pyramidalImage` export or another session supplies missing anchors.**
  False: export is session-local, rebuilds its own destination, and never reads another
  pyramidal image. Cross-session merging is a separate stage.

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
    `draw_elements`, `vertex_attrib` (others may exist), each pointing to a `.bin.bz2` file by
    absolute path, e.g.:
    ```
    kind=draw_elements frame=9 call=2552 parserCall=1 file=/media/ramdisk/output/00009/drawElements_indices_call_2552.bin.bz2 mode=5 type=5123 blobPtr=... bytes=804 compression=bzip2
    kind=vertex_attrib frame=9 call=2551 parserCall=0 file=/media/ramdisk/output/00009/glVertexAttribPointer_vertexAttrib_call_2551.bin.bz2 attribIndex=0 blobPtr=... bytes=118188 compression=bzip2
    ```
  - Binary blobs referenced by `manifest.txt`: `drawElements_indices_call_<call>.bin.bz2`
    (`GL_UNSIGNED_SHORT` index buffer) and `glVertexAttribPointer_vertexAttrib_call_<call>.bin.bz2`
    (vertex data for `attribIndex`, position data is `attribIndex=0`). `22_dumpAnalyzer`
    decompresses these blobs directly in memory; no temporary decompressed `.bin` is written.
  - Texture images, named `<width>x<height>_<textureId>.png` (uncompressed, decodable
    formats) or `.dds` (`GL_COMPRESSED_RGB_S3TC_DXT1_EXT`), e.g. `256x256_159.png`,
    `1024x256_1.png`. `textureId` is `THE_TextureId` at export time.
- **Invariants a maintenance change must preserve**:
  - Frame folder names stay 5-digit, zero-padded, matching the numeric frame id
    (`%05d`, e.g. `00009`).
  - `manifest.txt` line format (`key=value`, space-separated) and its `file=` absolute
    paths must keep matching the files actually on disk — `22_dumpAnalyzer` resolves blobs
    by that path. The current preferred binary blob storage is bzip2-compressed `.bin.bz2`;
    legacy `.bin` paths remain readable for old captures and compression-failure fallback.
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
      `matrix.json`) because this is where the raw geometry needed to detect the
      fine-to-coarse-texture quadrant relationship lives.
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
  - `rows`/`cols` define a rectangular coordinate extent and `i`/`j` must stay 0-based and
    inside it. The tile list may be sparse: filtered or merged matrices can contain empty
    cells, so consumers must not equate `tiles.length` with `rows * cols`.
  - `westCutters.json`'s schema is shared verbatim between `23_frameTextureNormalizer` and
    `31_matrixMerger` — both read and write it, so a format change must land in both
    projects atomically.

## Contract 6: `31_matrixMerger` → `32_pyramidalImageExporter`

- **Producer**: `31_matrixMerger`, via `MatrixLayerExportWriter`. Offline mode exports after its
  requested grouping/merge processing. Interactive mode exports when the viewer closes,
  so accepted merges, splits and deletions from that session are included.
- **Consumer**: `32_pyramidalImageExporter`, via `MatrixLayerJsonReader`.
- **Location**: `<exportFolder>/matrix_<n>/`, one folder per surviving merged matrix
  (`n` = 0-based export order), containing:
  - `matrixLayer.json`: versioned envelope (current `contractVersion: 3`) with:
    - `frameId`: representative frame of the merged matrix.
    - `hierarchyLevel`: relative depth in the matrix hierarchy (`0` for a known hierarchy
      root, increasing by one through each parent edge; it is not inferred from `<n>`), or
      `-1` when legacy/global grouping did not establish it. It is not the absolute
      quadtree level.
    - `parentMatrixIndex`: index `<n>` of the immediate parent, or `null` for a hierarchy
      root, a disconnected matrix, or unknown legacy hierarchy.
    - `parentGridTransform`: optional `{rowOffset,colOffset}` rigid transform for a
      visually inferred **containing** parent. For child local cell `(i,j)`,
      `(i+rowOffset,j+colOffset)` is its coordinate in the parent-local grid refined by
      one level. It is relative evidence and cannot anchor the child until the parent has
      an accepted absolute grid anchor.
    - `hierarchyUnclesByTileId`: compatibility map `tileId -> [uncleId]` used for graph
      ordering and diagnostics.
    - `hierarchyRelationshipsByTileId`: lossless map
      `tileId -> [{direction, uncleContentId}]`. It preserves the quadrant direction even
      when a merge keeps a duplicate tile record that originally had no `uncles`.
    - `matrices`: one or more `{frameId, rows, cols, tiles[]}` matrix records with
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
    strict majority must agree on one rigid `(parent,rowOffset,colOffset)`. The accepted
    result is persisted as `parentMatrixIndex + parentGridTransform`, never as a fabricated
    uncle relationship. A non-confident root stays disconnected; ordering alone is never
    an anchor.
  - When hierarchy metadata is known, `parentMatrixIndex` refers to an earlier exported
    matrix and `hierarchyLevel == parent.hierarchyLevel + 1`. Legacy/global grouping can
    emit unknown `hierarchyLevel: -1` and `parentMatrixIndex: null`; consumers must then
    rely on explicit tile relationships and absolute seeds, never directory order.
  - Folder naming `matrix_<n>` (not zero-padded) and file naming `matrixLayer.json` (not
    `matrix.json`) are both significant — `32_pyramidalImageExporter` only recognizes this
    exact layout.

## Contract 6b: `22_dumpAnalyzer` → `32_pyramidalImageExporter` (direct)

In addition to feeding `23_frameTextureNormalizer`/`31_matrixMerger` (Contracts 3-6),
`22_dumpAnalyzer`'s output is read directly by `32_pyramidalImageExporter`, twice:

- `topLevelTiles.json` (root of `output.directory`, configured independently of
  `<inputFolder>`): the *only* source for reconstructing quadtree levels `0..5`
  (`TopLevelMatrixRebuilder`), see Contract 3. This works even if `<inputFolder>` has no
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
    to an already-anchored coarse texture. Resolution appends the child quadrant specified
    by the direction table above to the uncle path.
  - `hierarchyRelationshipsByTileId` is merged into `tiles[].uncles` before resolution.
  - A contract-v3 `parentGridTransform` is propagated only after its referenced parent
    grid has an absolute anchor. Rigid matrix transforms and observed per-tile uncle
    relationships are separate evidence types and must never be converted into one another.
  - Once a strict majority chooses one `(level,rowOffset,colOffset)` for a rigid matrix,
    that grid canonicalizes every path in the matrix. Minority/outlier anchors and an
    individually ambiguous tile are corrected by the winning grid instead of being
    allowed to create duplicate output paths. A direct quadkey/catalogue anchor is a
    stronger evidence tier than a grid-derived path, and a grid-derived path is stronger
    than a relative uncle path; voting never mixes tiers. `colOffset` and every propagated
    absolute column are reduced modulo `2^level`. Partial matrices vote for that cyclic phase and,
    if a complete tuple has no majority, may combine independent strict majorities for
    level, row offset and column offset. A complete-world matrix is different: its
    `colOffset` is canonical zero and must be applied before its relationships seed a
    descendant, or the erroneous phase doubles at every hierarchy edge.
  - Before writing, the exporter builds a unique `fullPath -> tile` manifest. A local
    native tile deliberately replaces a derived TOP cell at the same path; any duplicate
    between incompatible peers aborts the export before the first PNG is written.
  - A tile with no resolvable path (directly or transitively) is skipped.
- **Invariant**: `32_pyramidalImageExporter` resolves paths from ids, relationships and
  the Contract-6b bridge. It may also infer an anchor inside `TopLevelLayerMerger`, by
  matching a partial matrix against reconstructed top-level texture regions or native
  child tiles against quadrants of an already canonicalized imported layer. Such matches
  become explicit retained `tileId -> Quadkey` seeds only after the distributed strict-
  majority checks defined above; they are not persisted as Contract-6 metadata by
  `31_matrixMerger`. Any id-format change must remain consistent across `frame.json`,
  `matrix.json`, `matrixLayer.json` and the TOP catalogue. A relationship graph with no
  absolute/catalogued seed and no accepted visual anchor remains relative and must not be
  exported at a guessed absolute location.

## Contract 8: `32_pyramidalImageExporter` → `41_planetViewer`

This is the final contract: the actual deliverable of the whole pipeline.

- **Producer**: `32_pyramidalImageExporter`, via the `e` key or `--export`, writing to
  `<inputFolder>/pyramidalImage` (created on first export; always inside the input folder,
  session-local — the tool never reads or writes any other pyramidal image).
- **Consumer**: `41_planetViewer`, and, potentially, any future cross-session merging tool
  (not implemented yet — explicitly out of scope for `32_pyramidalImageExporter`).
- **Format** (the "folder-based pyramidal image" format, shared by both programs):
  - Root tile: `0.png`, directly inside the pyramidal-image folder.
  - Every deeper tile uses one folder per quadrant digit after the root marker
    (quadrant convention above: `0`=SW, `1`=SE, `2`=NE, `3`=NW). Examples:
    `00 -> 0/00.png`, `002 -> 0/2/002.png`, `0303301 -> 3/0/3/3/0/1/0303301.png`.
    Consumers may still accept the previous cumulative-folder layout during migration, but
    new exports must use the per-digit layout.
  - Every written tile image is a native `256x256` PNG copied from a source tile that uses
    its complete texture rectangle (`u0=0`, `v0=0`, `u1=1`, `v1=1`). A viewer layer may
    display a cell by borrowing a sub-rectangle from an ancestor, but the exporter does
    not crop/upscale that derived cell into a new pyramid tile; holes are valid.
- **Invariants**:
  - The root file must be named exactly `0.png`; child folder names must be the single
    quadrant digit for that level. `TileRootPathResolver` path strings stay as full
    absolute quadkeys using the same digit alphabet `0-3`; only the folder layout omits
    the redundant leading `0` and the repeated cumulative prefixes.
  - Written tile images must stay native `256x256` PNG — `41_planetViewer` assumes this
    fixed size for texture budgeting and projected-area LOD thresholds. Missing child
    files are legal and use ancestor fallback in the viewer.
  - A pyramidal-image folder is read-only input for `41_planetViewer` (and for any
    consumer): nothing downstream ever writes back into it, so `32_pyramidalImageExporter`
    remains the sole writer for a given session's `pyramidalImage` folder. A re-export
    clears and rebuilds the session destination after a non-empty valid manifest has been
    prepared; it does not use previous slots as placement evidence.
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
| 7 | `31_matrixMerger`+`22_dumpAnalyzer` | `32_pyramidalImageExporter` | absolute quadkey resolution and session export manifest |
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
