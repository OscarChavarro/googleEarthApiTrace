# 51_demResampler

`51_demResampler` is a Java 17 command-line batch program that converts the FABDEM V1.2
GeoTIFF collection into the repository's folder-based quadtree layout. Leaf tiles contain
elevation rather than RGB: every `.bin` is a headerless `258x258` raster of signed
16-bit integer metres in little-endian, north-to-south row-major order. Its central
`256x256` area contains the tile itself and the surrounding one-sample halo comes from
its eight neighbours.

The program uses Vitral 1.3 for parallel console progress and a small JNI bridge to the
system GDAL library. GDAL reads and bilinearly resamples one VRT mosaic; Java owns
quadtree addressing, integer conversion, raw serialization, parallel scheduling, and
construction of all coarser levels.

## Input contract

The expected input is the extracted FABDEM V1.2 directory, for example
`/media/extra/FABDEM/01_tiff`. Its supplied `readme.txt`, metadata and representative
GeoTIFFs establish the following contract:

- FABDEM is a bare-earth DEM derived from Copernicus GLO-30 with forests and buildings
  removed.
- Files are `1x1` degree Cloud Optimized GeoTIFF tiles named from their south-west corner,
  such as `N40W004_FABDEM_V1-2.tif`.
- Each raster is `3600x3600` Float32, `AREA_OR_POINT=Point`, north-up and sampled on a
  uniform one arc-second grid.
- Horizontal coordinates are WGS 84 (`EPSG:4326`); vertical heights use EGM2008
  (`EPSG:3855`).
- Source NoData is `-9999`.
- FABDEM V1.2 is licensed CC BY-NC-SA 4.0. Derived datasets must retain the attribution,
  non-commercial and ShareAlike obligations documented in the source dataset.

Startup validates a representative raster against these properties. `gdalbuildvrt`
then opens every source header while constructing the global sparse mosaic and rejects
incompatible inputs.

## Quadtree and geographic convention

The layout matches the PNG pyramids produced by `32_pyramidalImageExporter` and merged by
`42_pyramidalImageMerger`:

```text
NW | NE          3 | 2
---+---           --+--
SW | SE           0 | 1
```

A quadkey includes the root marker `0`. Level `L` has `2^L` rows and columns, row `0` is
north, columns increase eastward, and longitude starts at the antimeridian. A level-3
tile such as `0303301` would be stored as:

```text
3/0/3/3/0/1/0303301.bin
```

The quadtree uses the same square equirectangular domain as the image viewers:
longitude spans `[-180, 180]` and the nominal vertical span is `[180, -180]`. Earth DEM
samples occupy only latitudes `[-90, 90]`; pixels outside that band remain NoData in
coarser ancestors.

For level `L`, tile `(row, column)` covers:

```text
tileDegrees = 360 / 2^L
west  = -180 + column * tileDegrees
east  = west + tileDegrees
north =  180 - row * tileDegrees
south = north - tileDegrees
```

## Selected native level

The level is computed from the source pixel spacing, choosing the finest quadtree level
whose pixel size is not finer than the source:

```text
L = floor(log2(360 / (256 * sourcePixelDegrees)))
```

For FABDEM's `1/3600` degree pixels this gives **level 12**:

| Grid | Angular pixel size | Decision |
|---|---:|---|
| FABDEM | 1.000 arc-second | source |
| level 12 | 1.2353515625 arc-seconds | selected; modest downsampling |
| level 13 | 0.61767578125 arc-seconds | rejected; would invent resolution |

Leaf pixels are bilinearly resampled by GDAL at the centers of the level-12 output
pixels. Valid values are rounded to integer metres and clamped to the usable signed
16-bit range.

## Raw tile contract

Every tile file:

- is exactly `133128` bytes (`258 * 258 * 2`);
- has no header, dimensions, padding or row stride;
- stores signed 16-bit integers in little-endian order;
- stores row `0` first (the northern/top row);
- stores each row from west/left to east/right;
- reserves `-32768` for NoData;
- uses `[-32767, 32767]` for valid elevations in metres.

The stored layout is:

```text
258x258 stored tile

    north-west corner | north neighbour's last core row | north-east corner
    west neighbour's  |                                  | east neighbour's
    last core column  |       256x256 elevation core     | first core column
    south-west corner | south neighbour's first core row | south-east corner
```

Corner samples come from the corresponding diagonal neighbour. Longitude is circular:
the western neighbour of column `0` is the last column at that level, and the eastern
neighbour of the last column is column `0`. Latitude is not circular. A missing sparse
neighbour, or a neighbour beyond the north/south limits, contributes NoData to that part
of the halo.

The halo supplies the adjacent elevation samples needed by finite-difference normal
calculation when converting the DEM to triangles. Both sides of a tile boundary
therefore use the same neighbouring samples, avoiding seams in position and lighting.

For example, the first two samples `4660` (`0x1234`) and `-2` are the byte sequence
`34 12 fe ff`.

The current `43_pyramidalImageCoverage` decodes PNG image tiles only. This output uses
the same address/folder topology, but its raw `.bin` samples require DEM-aware decoding
before that viewer can render their values.

## Building upper levels

After level 12 is complete, levels `11` down to `0` are constructed recursively. Only
the central `256x256` cores participate in reduction; halo samples never affect parent
elevations. The four children are placed using the repository quadrant convention.
Every parent core pixel is the rounded arithmetic mean of the corresponding `2x2`
group of child core integers:

- only valid samples contribute;
- each valid sample has equal weight;
- if fewer than four samples are valid, the sum is divided by the valid count;
- if all four are NoData, the parent is NoData;
- a missing sparse child behaves as an all-NoData child.

Thus the weighting preserves coastlines and holes without treating `-32768` as an
elevation. A parent tile is omitted only when all of its pixels are NoData.

## Execution

Prerequisites:

- Java 17 and Gradle;
- `g++`;
- GDAL command-line tools, headers and shared library.

Ubuntu/Debian packages:

```bash
sudo apt install gdal-bin libgdal-dev g++
```

Homebrew:

```bash
brew install gdal
```

No download into `pkgs/` is needed on the current workstation: GDAL 3.4.1, its headers
and `libgdal` are already installed.

Run from this directory:

```bash
./run.sh /media/extra/FABDEM/01_tiff /media/extra/FABDEM/02_rawPyramidal
```

Optional arguments:

- `--threads <n>` or `--threads=<n>`: worker count in `[1, 256]`. The default is
  `Runtime.availableProcessors()`, which is 72 on the current two-socket NUMA host.
- `--resume`: retained as a backwards-compatible no-op.

Runs are automatically incremental: a tile whose size is exactly `133128` bytes is
considered complete and skipped. Incomplete or missing tiles are regenerated, so the
same command can safely continue an interrupted compatible import. Do not reuse an
output folder produced with different inputs or settings.

The process maintains its own output inventory instead of traversing the generated tree
with `du`. Every 30 seconds it reports the number of verified tile files and their exact
logical byte count. At each completed pyramid level it atomically updates
`pyramid-size.txt` in the output root; the final report has `status=complete`. Halo
rewrites are not counted as additional files.

Before building the GDAL VRT, every source TIFF header is opened in an isolated helper
process. A header read that takes more than two seconds is cancelled, excluded from the
current import, and recorded as pending. Invalid headers are handled the same way. The
scan has its own progress monitor, and the final summary is also written to
`pending-tiffs.txt` in the output folder with the path and reason for every excluded
file.

## Parallel implementation plan

The implemented pipeline is split into restartable, bounded-memory phases:

1. **Discover and validate.** Scan all V1.2 TIFFs, reject duplicate geographic cells,
   inspect GDAL metadata, derive level 12, and enumerate only leaf tiles intersecting a
   real FABDEM degree tile.
2. **Build the sparse VRT.** Pass an input-file list to `gdalbuildvrt`; command-line
   length is therefore independent of the 19,011 input files.
3. **Generate leaf cores in parallel.** A fixed pool defaults to 72 workers. Each worker
   opens its own read-only GDAL dataset handle to the VRT (GDAL handles are not shared
   across threads), resamples one `256x256` core at a time, quantizes it, and writes it
   into the centre of a single `258x258` tile whose halo initially contains NoData.
   Calculation and reads retain the configured worker count, while a shared fair
   semaphore limits concurrent tile writes, directory mutations and atomic publication
   renames to four.
4. **Publish leaf halos in parallel.** Once every core is available, workers copy the
   eight neighbouring edges/corners into those same `258x258` files. No edge sidecar
   or second persistent file is created for a tile.
5. **Reduce and publish upper levels in parallel.** At every level, unique parent
   addresses form the next work queue. Workers read the four child cores, compute
   independent NoData-aware `2x2` means, then run the same neighbour-halo publication
   phase.
6. **Verify convergence.** The job succeeds only when non-empty leaf data reaches one
   level-0 root. Every raw read also validates the exact file size.

The fixed pool exposes all 72 logical CPUs to the operating system, which distributes
workers across both NUMA nodes. Memory is deliberately local and short-lived per worker:
one `256x256` GDAL Float32 buffer for leaf work, at most four child cores plus one parent
for reduction, or one core plus one stored tile during halo publication. There is no
global elevation raster in RAM. Only four workers may publish files concurrently; workers
that reach this limit wait while the remaining pool can continue calculation and reads.
On storage that cannot sustain 72 concurrent read streams, lower `--threads` may still
outperform the default.

The global conversion is large: input is about 297 GiB and the sparse output can contain
millions of approximately `130 KiB` files plus all ancestors. Each tile uses one
persistent file, including while its halo is being assembled. Check both free bytes and
free inodes before starting.

## Build and tests

```bash
gradle test
```

Gradle's `compileNative` task builds `build/native/libdemresampler_gdal.so` against the
installed GDAL and the active Java 17 JNI headers. Tests cover quadkey orientation and
paths, level selection, quadrant placement, NoData-aware `2x2` reduction, exact
little-endian bytes, neighbour halos including antimeridian wrapping, CLI parsing, and
a real native GDAL read through a synthetic VRT.

## Package structure

- `gdal`: JNI dataset wrapper and `gdalbuildvrt` orchestration.
- `io`: FABDEM discovery and strict raw tile serialization.
- `model`: raster metadata and absolute quadtree addresses.
- `options`: CLI contract.
- `processing`: target-level selection, parallel leaf resampling and parent reduction.
- `src/main/cpp`: minimal read-only GDAL JNI bridge.
