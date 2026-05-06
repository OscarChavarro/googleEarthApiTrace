package frametexturenormalizer.processing.matrix;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.TileInstance;
import frametexturenormalizer.model.TileMatrix;
import frametexturenormalizer.model.TileMatrix.TileCoord;

public final class TileSetToMatrixConverter {
    private static final MatrixTileCoordinate UNASSIGNED = new MatrixTileCoordinate(-1, -1);
    private static final int DISCONNECTED_SEGMENT_GAP_COLUMNS = 1;
    private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("pib.debug.matrix", "false"));
    private static final Integer DEBUG_FRAME = parseDebugFrame();
    private static final ThreadLocal<Integer> CURRENT_FRAME = new ThreadLocal<>();

    private final Set<Integer> lastConflictingTileIds = new HashSet<>();
    private final Map<Integer, MatrixTileCoordinate> lastCoordinatesByTileId = new HashMap<>();

    private record Shift(int di, int dj) {}

    public TileMatrix convert(FrameData frameData) {
        lastConflictingTileIds.clear();
        lastCoordinatesByTileId.clear();

        if (frameData == null || frameData.getTiles() == null || frameData.getTiles().isEmpty()) {
            return new TileMatrix(frameData == null ? -1 : frameData.getId(), 0, 0, List.of());
        }
        CURRENT_FRAME.set(frameData.getId());
        debug(frameData.getId(), "=== Frame %d conversion start (%d tiles) ===", frameData.getId(), frameData.getTiles().size());

        Map<Integer, TileInstance> byId = new HashMap<>();
        for (TileInstance tile : frameData.getTiles()) {
            if (tile != null) {
                byId.put(tile.getTileId(), tile);
            }
        }
        if (byId.isEmpty()) {
            return new TileMatrix(frameData.getId(), 0, 0, List.of());
        }

        Map<Integer, MatrixTileCoordinate> workingCoords = new HashMap<>();
        for (Integer id : byId.keySet()) {
            workingCoords.put(id, UNASSIGNED);
        }

        List<RowSegment> chunks = buildHorizontalChunks(byId, workingCoords);
        if (chunks == null) {
            debug(frameData.getId(), "Frame %d FAILED during horizontal chunk build", frameData.getId());
            lastCoordinatesByTileId.putAll(workingCoords);
            CURRENT_FRAME.remove();
            return null;
        }
        debug(frameData.getId(), "Frame %d horizontal row segments built: %d", frameData.getId(), chunks.size());

        if (!mergeChunksByNorthSouth(chunks, byId, workingCoords)) {
            debug(frameData.getId(), "Frame %d FAILED during vertical row-segment merge", frameData.getId());
            lastCoordinatesByTileId.putAll(workingCoords);
            CURRENT_FRAME.remove();
            return null;
        }
        debug(frameData.getId(), "Frame %d merged row segments remaining: %d", frameData.getId(), chunks.size());

        Map<Integer, MatrixTileCoordinate> finalCoords = placeRemainingChunks(chunks);
        lastCoordinatesByTileId.putAll(finalCoords);
        debug(frameData.getId(), "=== Frame %d conversion end ===", frameData.getId());
        TileMatrix out = buildMatrix(frameData, finalCoords);
        CURRENT_FRAME.remove();
        return out;
    }

    private List<RowSegment> buildHorizontalChunks(Map<Integer, TileInstance> byId, Map<Integer, MatrixTileCoordinate> workingCoords) {
        List<RowSegment> chunks = new ArrayList<>();

        for (Integer seedId : byId.keySet()) {
            if (!isUnassigned(workingCoords.get(seedId))) {
                continue;
            }

            RowSegment chunk = new RowSegment();
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            TileInstance seed = byId.get(seedId);
            MatrixCell seedCell = new MatrixCell(0, 0, seed);
            chunk.put(seedCell);
            debug(null, "New row segment seed tile=%d", seedId);
            workingCoords.put(seedId, new MatrixTileCoordinate(0, 0));
            queue.add(seedId);

            while (!queue.isEmpty()) {
                int currentId = queue.removeFirst();
                MatrixCell currentCell = chunk.getByTileId(currentId);
                TileInstance currentTile = byId.get(currentId);
                if (currentCell == null || currentTile == null) {
                    continue;
                }

                if (!assignHorizontal(chunk, byId, currentCell, currentTile.getEastNeighbor(), +1, queue, workingCoords)) {
                    return null;
                }
                if (!assignHorizontal(chunk, byId, currentCell, currentTile.getWestNeighbor(), -1, queue, workingCoords)) {
                    return null;
                }
            }

            chunk.normalizeRowStartToZero();
            if (!validateRowContiguity(chunk, byId)) {
                return null;
            }
            debug(null, "Row segment finalized size=%d width=%d minJ->0", chunk.size(), chunk.width());
            for (MatrixCell cell : chunk.cells()) {
                workingCoords.put(cell.tileId(), new MatrixTileCoordinate(cell.i(), cell.j()));
            }
            chunks.add(chunk);
        }

        return chunks;
    }

    private boolean assignHorizontal(
        RowSegment chunk,
        Map<Integer, TileInstance> byId,
        MatrixCell currentCell,
        Integer neighborId,
        int deltaJ,
        ArrayDeque<Integer> queue,
        Map<Integer, MatrixTileCoordinate> workingCoords
    ) {
        if (neighborId == null) {
            return true;
        }

        TileInstance neighborTile = byId.get(neighborId);
        if (neighborTile == null) {
            return true;
        }

        int expectedI = currentCell.i();
        int expectedJ = currentCell.j() + deltaJ;

        MatrixCell assigned = chunk.getByTileId(neighborId);
        if (assigned == null) {
            MatrixCell occupant = chunk.getByCoord(expectedI, expectedJ);
            if (occupant != null && occupant.tileId() != neighborId) {
                registerConflict(neighborId, occupant.tileId());
                return false;
            }
            MatrixCell cell = new MatrixCell(expectedI, expectedJ, neighborTile);
            chunk.put(cell);
            debug(
                null,
                "Assign H: tile=%d neighbor=%d -> (%d,%d)",
                currentCell.tileId(),
                neighborId,
                expectedI,
                expectedJ
            );
            workingCoords.put(neighborId, new MatrixTileCoordinate(expectedI, expectedJ));
            queue.add(neighborId);
            return true;
        }

        if (assigned.i() != expectedI || assigned.j() != expectedJ) {
            registerConflict(currentCell.tileId(), neighborId);
            return false;
        }

        return true;
    }

    private boolean mergeChunksByNorthSouth(List<RowSegment> chunks, Map<Integer, TileInstance> byId, Map<Integer, MatrixTileCoordinate> workingCoords) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < chunks.size(); i++) {
                for (int j = i + 1; j < chunks.size(); j++) {
                    debug(
                        null,
                        "Try merge row segments A(size=%d,width=%d) B(size=%d,width=%d)",
                        chunks.get(i).size(),
                        chunks.get(i).width(),
                        chunks.get(j).size(),
                        chunks.get(j).width()
                    );
                    RowSegment merged = tryMerge(chunks.get(i), chunks.get(j), byId);
                    if (merged == null) {
                        continue;
                    }
                    chunks.set(i, merged);
                    chunks.remove(j);
                    refreshWorkingCoords(chunks, workingCoords, byId.keySet());
                    debug(null, "Merge success -> new row-segment size=%d width=%d, total chunks=%d", merged.size(), merged.width(), chunks.size());
                    changed = true;
                    break;
                }
                if (changed) {
                    break;
                }
            }
        }
        return true;
    }

    private RowSegment tryMerge(RowSegment a, RowSegment b, Map<Integer, TileInstance> byId) {
        Shift shift = findConsistentShiftFromVerticalRelations(a, b, byId);
        if (shift == null) {
            debug(null, "Merge rejected: no consistent vertical anchor shift found");
            return null;
        }
        debug(null, "Merge anchor shift selected di=%d dj=%d", shift.di(), shift.dj());

        RowSegment shiftedB = b.copyShifted(shift.di(), shift.dj());

        for (MatrixCell cb : shiftedB.cells()) {
            MatrixCell occupant = a.getByCoord(cb.i(), cb.j());
            if (occupant != null && occupant.tileId() != cb.tileId()) {
                registerConflict(cb.tileId(), occupant.tileId());
                debug(null, "Merge rejected: overlap tile=%d with tile=%d at (%d,%d)", cb.tileId(), occupant.tileId(), cb.i(), cb.j());
                return null;
            }
        }

        RowSegment merged = new RowSegment();
        for (MatrixCell ca : a.cells()) {
            merged.put(new MatrixCell(ca.i(), ca.j(), ca.tile()));
        }
        for (MatrixCell cb : shiftedB.cells()) {
            if (!merged.containsTileId(cb.tileId())) {
                merged.put(new MatrixCell(cb.i(), cb.j(), cb.tile()));
            }
        }

        if (!validateMergedNeighbors(merged, byId)) {
            debug(null, "Merge rejected: neighbor consistency check failed after merge");
            return null;
        }
        if (!validateRowContiguity(merged, byId)) {
            debug(null, "Merge rejected: row contiguity invariant failed");
            return null;
        }
        return merged;
    }

    private Shift findConsistentShiftFromVerticalRelations(RowSegment a, RowSegment b, Map<Integer, TileInstance> byId) {
        Shift fromA = findShiftScanningRows(a, b, byId);
        if (fromA != null) {
            return fromA;
        }
        // Fallback: if no useful anchor is found from A, try scanning from B against A.
        return findShiftScanningRows(b, a, byId);
    }

    private Shift findShiftScanningRows(RowSegment source, RowSegment target, Map<Integer, TileInstance> byId) {
        List<Integer> rows = source.sortedRowIndices();

        for (Integer row : rows) {
            Map<String, Integer> votes = new HashMap<>();
            Map<String, Shift> shiftByKey = new HashMap<>();

            for (MatrixCell cell : source.cellsSortedLeftToRight()) {
                if (cell.i() != row.intValue()) {
                    continue;
                }
                TileInstance tile = byId.get(cell.tileId());
                if (tile == null) {
                    continue;
                }
                voteShiftFromRelation(votes, shiftByKey, cell, tile.getNorthNeighbor(), -1, target);
                voteShiftFromRelation(votes, shiftByKey, cell, tile.getSouthNeighbor(), +1, target);
            }

            if (votes.isEmpty()) {
                debug(null, "Row %d has no vertical anchors against target row segment", row);
                continue;
            }

            Shift best = null;
            int bestCount = -1;
            for (Map.Entry<String, Integer> e : votes.entrySet()) {
                if (e.getValue() > bestCount) {
                    bestCount = e.getValue();
                    best = shiftByKey.get(e.getKey());
                }
            }

            if (best != null) {
                debug(null, "Row %d selected anchor shift di=%d dj=%d (votes=%d)", row, best.di(), best.dj(), bestCount);
                return best;
            }
        }

        return null;
    }

    private void voteShiftFromRelation(
        Map<String, Integer> votes,
        Map<String, Shift> shiftByKey,
        MatrixCell anchor,
        Integer neighborId,
        int deltaI,
        RowSegment target
    ) {
        if (neighborId == null || !target.containsTileId(neighborId)) {
            return;
        }
        MatrixCell otherCell = target.getByTileId(neighborId);
        int expectedI = anchor.i() + deltaI;
        int expectedJ = anchor.j();
        Shift s = new Shift(expectedI - otherCell.i(), expectedJ - otherCell.j());
        String key = s.di() + ":" + s.dj();
        votes.put(key, votes.getOrDefault(key, 0) + 1);
        shiftByKey.putIfAbsent(key, s);
    }

    private boolean validateMergedNeighbors(RowSegment merged, Map<Integer, TileInstance> byId) {
        for (MatrixCell cell : merged.cells()) {
            TileInstance tile = byId.get(cell.tileId());
            if (tile == null) {
                continue;
            }

            if (!matches(merged, tile.getEastNeighbor(), cell.i(), cell.j() + 1, cell.tileId())) {
                return false;
            }
            if (!matches(merged, tile.getWestNeighbor(), cell.i(), cell.j() - 1, cell.tileId())) {
                return false;
            }
            if (!matches(merged, tile.getNorthNeighbor(), cell.i() - 1, cell.j(), cell.tileId())) {
                return false;
            }
            if (!matches(merged, tile.getSouthNeighbor(), cell.i() + 1, cell.j(), cell.tileId())) {
                return false;
            }
        }
        return true;
    }

    private boolean validateRowContiguity(RowSegment chunk, Map<Integer, TileInstance> byId) {
        Map<Integer, List<MatrixCell>> rows = new HashMap<>();
        for (MatrixCell cell : chunk.cells()) {
            rows.computeIfAbsent(cell.i(), __ -> new ArrayList<>()).add(cell);
        }

        for (Map.Entry<Integer, List<MatrixCell>> rowEntry : rows.entrySet()) {
            int row = rowEntry.getKey();
            List<MatrixCell> cells = rowEntry.getValue();
            Set<Integer> idSet = new HashSet<>();
            for (MatrixCell c : cells) {
                idSet.add(c.tileId());
            }

            // Allow gaps between blocks, but each horizontally connected block
            // (via east/west links) must be internally contiguous.
            Set<Integer> visited = new HashSet<>();
            for (MatrixCell start : cells) {
                if (visited.contains(start.tileId())) {
                    continue;
                }
                List<MatrixCell> segment = new ArrayList<>();
                ArrayDeque<MatrixCell> queue = new ArrayDeque<>();
                queue.add(start);
                visited.add(start.tileId());
                while (!queue.isEmpty()) {
                    MatrixCell cur = queue.removeFirst();
                    segment.add(cur);
                    TileInstance t = byId.get(cur.tileId());
                    if (t == null) {
                        continue;
                    }
                    addIfSameRowNeighbor(chunk, row, t.getEastNeighbor(), visited, queue);
                    addIfSameRowNeighbor(chunk, row, t.getWestNeighbor(), visited, queue);
                }
                segment.sort(Comparator.comparingInt(MatrixCell::j));
                for (int idx = 1; idx < segment.size(); idx++) {
                    int prevJ = segment.get(idx - 1).j();
                    int curJ = segment.get(idx).j();
                    if (curJ != prevJ + 1) {
                        registerConflict(segment.get(idx - 1).tileId(), segment.get(idx).tileId());
                        debug(null, "Row block gap at row=%d between j=%d and j=%d", row, prevJ, curJ);
                        return false;
                    }
                }
            }

            for (MatrixCell c : cells) {
                TileInstance t = byId.get(c.tileId());
                if (t == null) {
                    continue;
                }
                if (t.getEastNeighbor() != null && idSet.contains(t.getEastNeighbor())) {
                    MatrixCell east = chunk.getByTileId(t.getEastNeighbor());
                    if (east == null || east.i() != c.i() || east.j() != c.j() + 1) {
                        registerConflict(c.tileId(), t.getEastNeighbor());
                        debug(null, "Row block invariant failed for east edge tile=%d east=%d", c.tileId(), t.getEastNeighbor());
                        return false;
                    }
                }
                if (t.getWestNeighbor() != null && idSet.contains(t.getWestNeighbor())) {
                    MatrixCell west = chunk.getByTileId(t.getWestNeighbor());
                    if (west == null || west.i() != c.i() || west.j() != c.j() - 1) {
                        registerConflict(c.tileId(), t.getWestNeighbor());
                        debug(null, "Row block invariant failed for west edge tile=%d west=%d", c.tileId(), t.getWestNeighbor());
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void addIfSameRowNeighbor(
        RowSegment chunk,
        int row,
        Integer neighborId,
        Set<Integer> visited,
        ArrayDeque<MatrixCell> queue
    ) {
        if (neighborId == null || visited.contains(neighborId)) {
            return;
        }
        MatrixCell n = chunk.getByTileId(neighborId);
        if (n == null || n.i() != row) {
            return;
        }
        visited.add(neighborId);
        queue.add(n);
    }

    private boolean matches(RowSegment merged, Integer neighborId, int expectedI, int expectedJ, int ownerId) {
        if (neighborId == null) {
            return true;
        }

        MatrixCell neighbor = merged.getByTileId(neighborId);
        if (neighbor == null) {
            return true;
        }

        if (neighbor.i() != expectedI || neighbor.j() != expectedJ) {
            registerConflict(ownerId, neighborId);
            debug(
                null,
                "Neighbor mismatch owner=%d neighbor=%d expected=(%d,%d) actual=(%d,%d)",
                ownerId,
                neighborId,
                expectedI,
                expectedJ,
                neighbor.i(),
                neighbor.j()
            );
            return false;
        }
        return true;
    }

    private void refreshWorkingCoords(List<RowSegment> chunks, Map<Integer, MatrixTileCoordinate> working, Set<Integer> allIds) {
        for (Integer id : allIds) {
            working.put(id, UNASSIGNED);
        }
        for (RowSegment chunk : chunks) {
            for (MatrixCell cell : chunk.cells()) {
                working.put(cell.tileId(), new MatrixTileCoordinate(cell.i(), cell.j()));
            }
        }
    }

    private Map<Integer, MatrixTileCoordinate> placeRemainingChunks(List<RowSegment> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return Map.of();
        }

        // Anchor segment: largest one keeps the base vertical reference.
        int anchorIndex = 0;
        for (int i = 1; i < chunks.size(); i++) {
            if (chunks.get(i).size() > chunks.get(anchorIndex).size()) {
                anchorIndex = i;
            }
        }
        if (anchorIndex != 0) {
            RowSegment anchor = chunks.get(anchorIndex);
            chunks.set(anchorIndex, chunks.get(0));
            chunks.set(0, anchor);
        }

        Map<Integer, MatrixTileCoordinate> out = new HashMap<>();
        int nextStartJ = 0;
        int minI = Integer.MAX_VALUE;
        int currentMaxI = Integer.MIN_VALUE;
        int globalMaxHeight = 0;
        for (RowSegment s : chunks) {
            globalMaxHeight = Math.max(globalMaxHeight, s.height());
        }

        for (int idx = 0; idx < chunks.size(); idx++) {
            RowSegment chunk = chunks.get(idx);
            validateRowSegmentInvariants(chunk, "final-placement-before-shift", idx);
            int shiftI;
            if (idx == 0) {
                shiftI = -chunk.minI();
            } else if (chunk.height() >= globalMaxHeight) {
                // Full-height segments are aligned to the top.
                shiftI = -chunk.minI();
            } else {
                // Partial segments are placed below to avoid row collapse.
                shiftI = (currentMaxI + 1) - chunk.minI();
            }
            int shiftJ = nextStartJ - chunk.minJ();
            debug(
                null,
                "Final place seg=%d size=%d width=%d height=%d minI=%d maxI=%d shiftI=%d shiftJ=%d",
                idx,
                chunk.size(),
                chunk.width(),
                chunk.height(),
                chunk.minI(),
                chunk.maxI(),
                shiftI,
                shiftJ
            );

            for (MatrixCell cell : chunk.cells()) {
                int i = cell.i() + shiftI;
                int j = cell.j() + shiftJ;
                minI = Math.min(minI, i);
                currentMaxI = Math.max(currentMaxI, i);
                out.put(cell.tileId(), new MatrixTileCoordinate(i, j));
            }

            // Keep disconnected segments separated so the exported matrix does not
            // invent east-west adjacency that is absent from the source neighbor graph.
            nextStartJ += chunk.width() + DISCONNECTED_SEGMENT_GAP_COLUMNS;
        }

        if (minI == Integer.MAX_VALUE || minI == 0) {
            return out;
        }

        Map<Integer, MatrixTileCoordinate> normalized = new HashMap<>(out.size());
        for (Map.Entry<Integer, MatrixTileCoordinate> e : out.entrySet()) {
            MatrixTileCoordinate c = e.getValue();
            normalized.put(e.getKey(), new MatrixTileCoordinate(c.i() - minI, c.j()));
        }
        return normalized;
    }

    private TileMatrix buildMatrix(FrameData frameData, Map<Integer, MatrixTileCoordinate> coordsByTileId) {
        int minI = Integer.MAX_VALUE;
        int minJ = Integer.MAX_VALUE;
        int maxI = Integer.MIN_VALUE;
        int maxJ = Integer.MIN_VALUE;

        for (MatrixTileCoordinate c : coordsByTileId.values()) {
            if (isUnassigned(c)) {
                continue;
            }
            minI = Math.min(minI, c.i());
            minJ = Math.min(minJ, c.j());
            maxI = Math.max(maxI, c.i());
            maxJ = Math.max(maxJ, c.j());
        }

        if (minI == Integer.MAX_VALUE) {
            return null;
        }

        int shiftI = minI < 0 ? -minI : 0;
        int shiftJ = minJ < 0 ? -minJ : 0;

        List<TileCoord> result = new ArrayList<>(frameData.getTiles().size());
        for (TileInstance tile : frameData.getTiles()) {
            if (tile == null) {
                continue;
            }
            MatrixTileCoordinate c = coordsByTileId.get(tile.getTileId());
            if (c == null || isUnassigned(c)) {
                registerConflict(tile.getTileId(), null);
                return null;
            }
            result.add(new TileCoord(tile.getTileId(), c.i() + shiftI, c.j() + shiftJ, tile.getTextureFile()));
        }

        return new TileMatrix(frameData.getId(), maxI - minI + 1, maxJ - minJ + 1, result);
    }

    private static boolean isUnassigned(MatrixTileCoordinate c) {
        return c == null || (c.i() == -1 && c.j() == -1);
    }

    public Set<Integer> getLastConflictingTileIds() {
        return Set.copyOf(lastConflictingTileIds);
    }

    public Map<Integer, MatrixTileCoordinate> getLastCoordinatesByTileId() {
        return Map.copyOf(lastCoordinatesByTileId);
    }

    private void registerConflict(Integer a, Integer b) {
        if (a != null) {
            lastConflictingTileIds.add(a);
        }
        if (b != null) {
            lastConflictingTileIds.add(b);
        }
        debug(null, "Conflict registered: a=%s b=%s", String.valueOf(a), String.valueOf(b));
    }

    private void validateRowSegmentInvariants(RowSegment segment, String stage, int index) {
        Map<Integer, List<MatrixCell>> byRow = new HashMap<>();
        for (MatrixCell c : segment.cells()) {
            byRow.computeIfAbsent(c.i(), __ -> new ArrayList<>()).add(c);
        }
        for (Map.Entry<Integer, List<MatrixCell>> rowEntry : byRow.entrySet()) {
            int row = rowEntry.getKey();
            List<MatrixCell> cells = rowEntry.getValue();
            cells.sort(Comparator.comparingInt(MatrixCell::j));
            int expected = 0;
            for (MatrixCell c : cells) {
                if (c.j() != expected) {
                    debug(
                        null,
                        "INVARIANT WARNING stage=%s seg=%d row=%d expectedJ=%d gotJ=%d tile=%d",
                        stage,
                        index,
                        row,
                        expected,
                        c.j(),
                        c.tileId()
                    );
                    break;
                }
                expected++;
            }
        }
    }

    private static void debug(Integer frameId, String message, Object... args) {
        if (!isDebugEnabled(frameId)) {
            return;
        }
        System.out.println("[TileSetToMatrixConverter] " + String.format(message, args));
    }

    private static boolean isDebugEnabled(Integer frameId) {
        if (!DEBUG) {
            return false;
        }
        Integer effectiveFrame = frameId == null ? CURRENT_FRAME.get() : frameId;
        if (DEBUG_FRAME == null) {
            return true;
        }
        return effectiveFrame != null && effectiveFrame.intValue() == DEBUG_FRAME.intValue();
    }

    private static Integer parseDebugFrame() {
        String raw = System.getProperty("pib.debug.matrix.frame");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
