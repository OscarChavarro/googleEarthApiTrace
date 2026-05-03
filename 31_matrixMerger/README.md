# 31_matrixMerger

`31_matrixMerger` is a tile-matrix (`matrix.json`) viewer/processor generated in previous stages, focused on merging overlapping matrices and visualizing the result.

## What it does

- Reads matrices from frame folders inside `output.directory` (default `/media/ramdisk/output`).
- Displays one active matrix at a time as quads on plane `Z=0`.
- Supports matrix navigation and interactive merges.
- Implements frustum culling to avoid drawing off-camera quads.
- Implements distance-based LOD:
  - Near: full textured quad (pixel-perfect with `NEAREST` + `CLAMP_TO_EDGE`).
  - Far: untextured quad, scaled to `98%` to keep visible separation.
- Manages GPU texture memory with a maximum budget and FIFO eviction.

## Expected inputs

Each frame must contain `matrix.json` (fallback: `matrix.txt`) with a structure compatible with:

- `frameId`
- `rows`
- `cols`
- `tiles[]` with:
  - `id` (string, recommended)
  - `i`, `j`
  - `textureFile`

It also accepts legacy numeric `tileId` during deserialization.

## Viewer controls

- `1`: previous matrix
- `2`: next matrix
- `m`: merge current matrix (A) with next matrix (B)
- `M`: merge across the entire set (full algorithm)
- `t`: texture toggle (delegated to `RendererConfigurationController`)
- `ESC`: exit

HUD:

- Always: `Matrix [1, 2]: i/N`
- If a next matrix exists: `Merge current matrix with next one [m]`
- If the last local merge failed (without changing selection):
  - `ERROR: Could not merge with next matrix!` (in red)

## Merge algorithms

### `processor.MatrixMerger`

Operates on two matrices `A` and `B`:

1. Finds matching cells by `id` to compute one offset (`MatrixOffset`) for `B` over `A`.
2. If the offset is not consistent for all shared `id` values, it fails.
3. If overlapping cells contain conflicting content, it fails.
4. If valid, adds to `A` the cells from `B` that were not already in `A`.
5. Normalizes `A` coordinates to start at `0` and recalculates `rows/cols`.

### `processor.FullSetMerger`

Iterates through the full list:

1. Takes `A = matrices[i]`, `B = matrices[i+1]`.
2. If merge succeeds, removes `B` and retries with the new next matrix on the same `A`.
3. If merge fails, increments `i`.
4. Stops when only one matrix remains or no more pairs exist.

## Execution

### Interactive mode

From repo root:

```bash
./gradlew :31_matrixMerger:run
```

### Offline mode

Runs only the global full-set merge and exits without GUI:

```bash
./gradlew :31_matrixMerger:run --args="--ofline"
```

(`--offline` is also accepted for compatibility.)

## Configuration

In `matrixmerger.config.Configuration`:

- `MAX_GPU_TEXTURE_MEMORY`: GPU texture memory limit.
- `MAX_TEXTURED_QUAD_DISTANCE`: distance threshold for using textures.
- `FAR_QUAD_SCALE`: scale of far (untextured) quads.

## Package structure

- `io`: matrix reading/deserialization.
- `model`: visualization and selection state.
- `processor`: local merge and full-set merge.
- `render`: JOGL renderer, culling, and LOD.
- `gui`: keyboard/mouse handling.
