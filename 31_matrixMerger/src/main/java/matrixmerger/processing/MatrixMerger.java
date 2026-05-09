package matrixmerger.processing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import matrixmerger.io.TileMatrix;
import matrixmerger.io.WestCutterReader;

public final class MatrixMerger {
    private Set<String> westCutterTileIds = Set.of();

    public void setWestCutterTileIds(Set<String> westCutterTileIds) {
        if (westCutterTileIds == null || westCutterTileIds.isEmpty()) {
            this.westCutterTileIds = Set.of();
            return;
        }
        Set<String> normalized = new HashSet<>();
        for (String id : westCutterTileIds) {
            String canonical = WestCutterReader.normalizeScopedTileId(id);
            if (canonical != null) {
                normalized.add(canonical);
            }
        }
        this.westCutterTileIds = Collections.unmodifiableSet(normalized);
    }

    public boolean merge(TileMatrix a, TileMatrix b) {
        if (!canMerge(a, b)) {
            return false;
        }

        Map<String, TileMatrix.TileCoord> aById = indexTilesById(a.getTiles());
        Map<String, TileMatrix.TileCoord> bById = indexTilesById(b.getTiles());
        if (aById == null || bById == null) {
            return false;
        }

        Set<String> sharedIds = findSharedIds(aById, bById);
        if (sharedIds.isEmpty()) {
            return false;
        }

        MatrixOffset offset = calculateAlignmentOffset(sharedIds, aById, bById);
        if (offset == null) {
            return false;
        }

        Map<String, TileMatrix.TileCoord> aByPos = indexTilesByPosition(a.getTiles());
        if (aByPos == null) {
            return false;
        }

        List<TileMatrix.TileCoord> tilesToAppend = collectReachableTilesFromB(b, aByPos, bById, sharedIds, offset);
        if (tilesToAppend == null) {
            return false;
        }

        appendTiles(a, tilesToAppend);
        normalizeIndices(a);
        return true;
    }

    private static boolean canMerge(TileMatrix a, TileMatrix b) {
        return a != null
            && b != null
            && a.getTiles() != null
            && b.getTiles() != null
            && !a.getTiles().isEmpty()
            && !b.getTiles().isEmpty();
    }

    private static Set<String> findSharedIds(
        Map<String, TileMatrix.TileCoord> aById,
        Map<String, TileMatrix.TileCoord> bById
    ) {
        Set<String> sharedIds = new HashSet<>(aById.keySet());
        sharedIds.retainAll(bById.keySet());
        return sharedIds;
    }

    private static MatrixOffset calculateAlignmentOffset(
        Set<String> sharedIds,
        Map<String, TileMatrix.TileCoord> aById,
        Map<String, TileMatrix.TileCoord> bById
    ) {
        Integer deltaI = null;
        Integer deltaJ = null;
        for (String id : sharedIds) {
            TileMatrix.TileCoord at = aById.get(id);
            TileMatrix.TileCoord bt = bById.get(id);
            int di = at.getI() - bt.getI();
            int dj = at.getJ() - bt.getJ();
            if (deltaI == null) {
                deltaI = di;
                deltaJ = dj;
                continue;
            }
            if (deltaI != di || deltaJ != dj) {
                return null;
            }
        }
        return deltaI == null ? null : new MatrixOffset(deltaI, deltaJ);
    }

    private static Map<String, TileMatrix.TileCoord> indexTilesById(List<TileMatrix.TileCoord> tiles) {
        Map<String, TileMatrix.TileCoord> out = new HashMap<>();
        for (TileMatrix.TileCoord t : tiles) {
            if (t == null) {
                continue;
            }
            String id = t.getId();
            if (id == null || id.isBlank()) {
                return null;
            }
            TileMatrix.TileCoord prev = out.putIfAbsent(id, t);
            if (prev != null && (prev.getI() != t.getI() || prev.getJ() != t.getJ())) {
                return null;
            }
        }
        return out;
    }

    private static Map<String, TileMatrix.TileCoord> indexTilesByPosition(List<TileMatrix.TileCoord> tiles) {
        Map<String, TileMatrix.TileCoord> byPosition = new HashMap<>();
        for (TileMatrix.TileCoord t : tiles) {
            String key = key(t.getI(), t.getJ());
            TileMatrix.TileCoord prev = byPosition.putIfAbsent(key, t);
            if (prev != null && !prev.getId().equals(t.getId())) {
                return null;
            }
        }
        return byPosition;
    }

    private List<TileMatrix.TileCoord> collectReachableTilesFromB(
        TileMatrix b,
        Map<String, TileMatrix.TileCoord> aByPos,
        Map<String, TileMatrix.TileCoord> bById,
        Set<String> sharedIds,
        MatrixOffset offset
    ) {
        List<TileMatrix.TileCoord> toAdd = new ArrayList<>();
        Map<String, TileMatrix.TileCoord> bByTranslatedPos = new HashMap<>();
        for (TileMatrix.TileCoord bt : b.getTiles()) {
            bByTranslatedPos.put(key(bt.getI() + offset.deltaI(), bt.getJ() + offset.deltaJ()), bt);
        }

        ArrayDeque<Position> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        for (String sharedId : sharedIds) {
            TileMatrix.TileCoord bt = bById.get(sharedId);
            if (bt == null) {
                continue;
            }
            Position start = new Position(bt.getI() + offset.deltaI(), bt.getJ() + offset.deltaJ());
            String startKey = key(start.i(), start.j());
            if (visited.add(startKey)) {
                queue.addLast(start);
            }
        }

        while (!queue.isEmpty()) {
            Position current = queue.removeFirst();
            String posKey = key(current.i(), current.j());
            TileMatrix.TileCoord bt = bByTranslatedPos.get(posKey);
            if (bt == null) {
                continue;
            }

            TileMatrix.TileCoord at = aByPos.get(posKey);
            if (at != null) {
                if (!at.getId().equals(bt.getId())) {
                    return null;
                }
            }
            else {
                toAdd.add(cloneWithCoordinates(bt, current.i(), current.j()));
            }

            enqueueIfPresent(queue, visited, bByTranslatedPos, current.i() - 1, current.j());
            enqueueIfPresent(queue, visited, bByTranslatedPos, current.i() + 1, current.j());
            enqueueIfPresent(queue, visited, bByTranslatedPos, current.i(), current.j() + 1);
            enqueueIfPresent(queue, visited, bByTranslatedPos, current.i(), current.j() - 1);
        }
        return toAdd;
    }

    private static void enqueueIfPresent(
        ArrayDeque<Position> queue,
        Set<String> visited,
        Map<String, TileMatrix.TileCoord> bByTranslatedPos,
        int i,
        int j
    ) {
        String posKey = key(i, j);
        if (!bByTranslatedPos.containsKey(posKey) || !visited.add(posKey)) {
            return;
        }
        queue.addLast(new Position(i, j));
    }

    private static TileMatrix.TileCoord cloneWithCoordinates(TileMatrix.TileCoord src, int i, int j) {
        TileMatrix.TileCoord added = new TileMatrix.TileCoord();
        added.setId(src.getId());
        added.setI(i);
        added.setJ(j);
        added.setTextureFile(src.getTextureFile());
        added.setUncles(src.getUncles());
        return added;
    }

    private static void appendTiles(TileMatrix a, List<TileMatrix.TileCoord> tilesToAppend) {
        a.getTiles().addAll(tilesToAppend);
    }

    private static void normalizeIndices(TileMatrix matrix) {
        if (matrix.getTiles().isEmpty()) {
            matrix.setRows(0);
            matrix.setCols(0);
            return;
        }

        int minI = Integer.MAX_VALUE;
        int minJ = Integer.MAX_VALUE;
        int maxI = Integer.MIN_VALUE;
        int maxJ = Integer.MIN_VALUE;

        for (TileMatrix.TileCoord t : matrix.getTiles()) {
            if (t == null) {
                continue;
            }
            if (t.getI() < minI) minI = t.getI();
            if (t.getJ() < minJ) minJ = t.getJ();
            if (t.getI() > maxI) maxI = t.getI();
            if (t.getJ() > maxJ) maxJ = t.getJ();
        }

        if (minI == Integer.MAX_VALUE || minJ == Integer.MAX_VALUE) {
            matrix.setRows(0);
            matrix.setCols(0);
            return;
        }

        if (minI != 0 || minJ != 0) {
            for (TileMatrix.TileCoord t : matrix.getTiles()) {
                if (t == null) {
                    continue;
                }
                t.setI(t.getI() - minI);
                t.setJ(t.getJ() - minJ);
            }
            maxI -= minI;
            maxJ -= minJ;
        }

        matrix.setRows(maxI + 1);
        matrix.setCols(maxJ + 1);
    }

    private static String key(int i, int j) {
        return i + ":" + j;
    }

    private record Position(int i, int j) {
    }
}
