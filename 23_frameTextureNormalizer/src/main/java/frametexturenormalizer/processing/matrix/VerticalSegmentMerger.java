package frametexturenormalizer.processing.matrix;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import frametexturenormalizer.model.TileInstance;

final class VerticalSegmentMerger {
    private record Shift(int di, int dj) {
    }

    boolean merge(List<RowSegment> chunks, Map<Integer, TileInstance> byId, MatrixConflictTracker conflictTracker) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < chunks.size(); i++) {
                for (int j = i + 1; j < chunks.size(); j++) {
                    MatrixDebug.debug(
                        null,
                        "Try merge row segments A(size=%d,width=%d) B(size=%d,width=%d)",
                        chunks.get(i).size(),
                        chunks.get(i).width(),
                        chunks.get(j).size(),
                        chunks.get(j).width()
                    );
                    RowSegment merged = tryMerge(chunks.get(i), chunks.get(j), byId, conflictTracker);
                    if (merged == null) {
                        continue;
                    }
                    chunks.set(i, merged);
                    chunks.remove(j);
                    conflictTracker.refreshWorkingCoordinates(chunks, byId.keySet());
                    MatrixDebug.debug(
                        null,
                        "Merge success -> new row-segment size=%d width=%d, total chunks=%d",
                        merged.size(),
                        merged.width(),
                        chunks.size()
                    );
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

    private RowSegment tryMerge(
        RowSegment a,
        RowSegment b,
        Map<Integer, TileInstance> byId,
        MatrixConflictTracker conflictTracker
    ) {
        Shift shift = findConsistentShiftFromVerticalRelations(a, b, byId);
        if (shift == null) {
            MatrixDebug.debug(null, "Merge rejected: no consistent vertical anchor shift found");
            return null;
        }
        MatrixDebug.debug(null, "Merge anchor shift selected di=%d dj=%d", shift.di(), shift.dj());

        RowSegment shiftedB = b.copyShifted(shift.di(), shift.dj());

        for (MatrixCell cb : shiftedB.cells()) {
            MatrixCell occupant = a.getByCoordinate(cb.i(), cb.j());
            if (occupant != null && occupant.tileId() != cb.tileId()) {
                conflictTracker.registerConflict(cb.tileId(), occupant.tileId());
                MatrixDebug.debug(
                    null,
                    "Merge rejected: overlap tile=%d with tile=%d at (%d,%d)",
                    cb.tileId(),
                    occupant.tileId(),
                    cb.i(),
                    cb.j()
                );
                return null;
            }
        }

        RowSegment merged = new RowSegment();
        for (MatrixCell ca : a.cells()) {
            merged.put(new MatrixCell(ca.i(), ca.j(), ca.tile()));
        }
        for (MatrixCell cb : shiftedB.cells()) {
            if (merged.tileIdNotFound(cb.tileId())) {
                merged.put(new MatrixCell(cb.i(), cb.j(), cb.tile()));
            }
        }

        if (!validateMergedNeighbors(merged, byId, conflictTracker)) {
            MatrixDebug.debug(null, "Merge rejected: neighbor consistency check failed after merge");
            return null;
        }
        if (invalidRowContiguity(merged, byId, conflictTracker)) {
            MatrixDebug.debug(null, "Merge rejected: row contiguity invariant failed");
            return null;
        }
        return merged;
    }

    private Shift findConsistentShiftFromVerticalRelations(RowSegment a, RowSegment b, Map<Integer, TileInstance> byId) {
        Shift fromA = findShiftScanningRows(a, b, byId);
        if (fromA != null) {
            return fromA;
        }
        return findShiftScanningRows(b, a, byId);
    }

    private Shift findShiftScanningRows(RowSegment source, RowSegment target, Map<Integer, TileInstance> byId) {
        List<Integer> rows = source.sortedRowIndices();

        for (Integer row : rows) {
            Map<String, Integer> votes = new HashMap<>();
            Map<String, Shift> shiftByKey = new HashMap<>();

            for (MatrixCell cell : source.cellsInRowSortedLeftToRight(row)) {
                TileInstance tile = byId.get(cell.tileId());
                if (tile == null) {
                    continue;
                }
                voteShiftFromRelation(votes, shiftByKey, cell, tile.getNorthNeighbor(), -1, target);
                voteShiftFromRelation(votes, shiftByKey, cell, tile.getSouthNeighbor(), +1, target);
            }

            if (votes.isEmpty()) {
                MatrixDebug.debug(null, "Row %d has no vertical anchors against target row segment", row);
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
                MatrixDebug.debug(
                    null,
                    "Row %d selected anchor shift di=%d dj=%d (votes=%d)",
                    row,
                    best.di(),
                    best.dj(),
                    bestCount
                );
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
        if (neighborId == null || target.tileIdNotFound(neighborId)) {
            return;
        }
        MatrixCell otherCell = target.getByTileId(neighborId);
        int expectedI = anchor.i() + deltaI;
        int expectedJ = anchor.j();
        Shift shift = new Shift(expectedI - otherCell.i(), expectedJ - otherCell.j());
        String key = shift.di() + ":" + shift.dj();
        votes.put(key, votes.getOrDefault(key, 0) + 1);
        shiftByKey.putIfAbsent(key, shift);
    }

    private boolean validateMergedNeighbors(
        RowSegment merged,
        Map<Integer, TileInstance> byId,
        MatrixConflictTracker conflictTracker
    ) {
        for (MatrixCell cell : merged.cells()) {
            TileInstance tile = byId.get(cell.tileId());
            if (tile == null) {
                continue;
            }

            if (matchNotFound(merged, tile.getEastNeighbor(), cell.i(), cell.j() + 1, cell.tileId(), conflictTracker)) {
                return false;
            }
            if (matchNotFound(merged, tile.getWestNeighbor(), cell.i(), cell.j() - 1, cell.tileId(), conflictTracker)) {
                return false;
            }
            if (matchNotFound(merged, tile.getNorthNeighbor(), cell.i() - 1, cell.j(), cell.tileId(), conflictTracker)) {
                return false;
            }
            if (matchNotFound(merged, tile.getSouthNeighbor(), cell.i() + 1, cell.j(), cell.tileId(), conflictTracker)) {
                return false;
            }
        }
        return true;
    }

    static boolean invalidRowContiguity(
        RowSegment chunk,
        Map<Integer, TileInstance> byId,
        MatrixConflictTracker conflictTracker
    ) {
        for (Integer rowValue : chunk.sortedRowIndices()) {
            int row = rowValue;
            List<MatrixCell> cells = chunk.cellsInRowSortedLeftToRight(row);
            Set<Integer> idSet = new HashSet<>();
            for (MatrixCell c : cells) {
                idSet.add(c.tileId());
            }

            Set<Integer> visited = new HashSet<>();
            for (MatrixCell start : cells) {
                if (visited.contains(start.tileId())) {
                    continue;
                }
                List<MatrixCell> segment = new ArrayList<>();
                ArrayDeque<MatrixCell> queue = new ArrayDeque<>();
                queue.add(start);
                visited.add(start.tileId());
                int minSegmentJ = Integer.MAX_VALUE;
                int maxSegmentJ = Integer.MIN_VALUE;
                while (!queue.isEmpty()) {
                    MatrixCell cur = queue.removeFirst();
                    segment.add(cur);
                    minSegmentJ = Math.min(minSegmentJ, cur.j());
                    maxSegmentJ = Math.max(maxSegmentJ, cur.j());
                    TileInstance t = byId.get(cur.tileId());
                    if (t == null) {
                        continue;
                    }
                    addIfSameRowNeighbor(chunk, row, t.getEastNeighbor(), visited, queue);
                    addIfSameRowNeighbor(chunk, row, t.getWestNeighbor(), visited, queue);
                }
                if (!segment.isEmpty() && maxSegmentJ - minSegmentJ + 1 != segment.size()) {
                    MatrixCell left = null;
                    MatrixCell right = null;
                    for (MatrixCell cell : segment) {
                        if (left == null || cell.j() < left.j()) {
                            left = cell;
                        }
                        if (right == null || cell.j() > right.j()) {
                            right = cell;
                        }
                    }
                    conflictTracker.registerConflict(
                        left == null ? start.tileId() : left.tileId(),
                        right == null ? start.tileId() : right.tileId()
                    );
                    MatrixDebug.debug(
                        null,
                        "Row block gap at row=%d span=[%d,%d] size=%d",
                        row,
                        minSegmentJ,
                        maxSegmentJ,
                        segment.size()
                    );
                    return false;
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
                        conflictTracker.registerConflict(c.tileId(), t.getEastNeighbor());
                        MatrixDebug.debug(
                            null,
                            "Row block invariant failed for east edge tile=%d east=%d",
                            c.tileId(),
                            t.getEastNeighbor()
                        );
                        return true;
                    }
                }
                if (t.getWestNeighbor() != null && idSet.contains(t.getWestNeighbor())) {
                    MatrixCell west = chunk.getByTileId(t.getWestNeighbor());
                    if (west == null || west.i() != c.i() || west.j() != c.j() - 1) {
                        conflictTracker.registerConflict(c.tileId(), t.getWestNeighbor());
                        MatrixDebug.debug(
                            null,
                            "Row block invariant failed for west edge tile=%d west=%d",
                            c.tileId(),
                            t.getWestNeighbor()
                        );
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void addIfSameRowNeighbor(
        RowSegment chunk,
        int row,
        Integer neighborId,
        Set<Integer> visited,
        ArrayDeque<MatrixCell> queue
    ) {
        if (neighborId == null || visited.contains(neighborId)) {
            return;
        }
        MatrixCell neighbor = chunk.getByTileId(neighborId);
        if (neighbor == null || neighbor.i() != row) {
            return;
        }
        visited.add(neighborId);
        queue.add(neighbor);
    }

    private boolean matchNotFound(
        RowSegment merged,
        Integer neighborId,
        int expectedI,
        int expectedJ,
        int ownerId,
        MatrixConflictTracker conflictTracker
    ) {
        if (neighborId == null) {
            return false;
        }

        MatrixCell neighbor = merged.getByTileId(neighborId);
        if (neighbor == null) {
            return false;
        }

        if (neighbor.i() != expectedI || neighbor.j() != expectedJ) {
            conflictTracker.registerConflict(ownerId, neighborId);
            MatrixDebug.debug(
                null,
                "Neighbor mismatch owner=%d neighbor=%d expected=(%d,%d) actual=(%d,%d)",
                ownerId,
                neighborId,
                expectedI,
                expectedJ,
                neighbor.i(),
                neighbor.j()
            );
            return true;
        }
        return false;
    }
}
