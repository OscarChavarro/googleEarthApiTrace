package matrixmerger.processor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import matrixmerger.io.TileMatrix;

public final class MatrixMerger {
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

        List<TileMatrix.TileCoord> tilesToAppend = collectNonOverlappingTilesFromB(b, aByPos, offset);
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

    private static List<TileMatrix.TileCoord> collectNonOverlappingTilesFromB(
        TileMatrix b,
        Map<String, TileMatrix.TileCoord> aByPos,
        MatrixOffset offset
    ) {
        List<TileMatrix.TileCoord> toAdd = new ArrayList<>();
        for (TileMatrix.TileCoord bt : b.getTiles()) {
            int translatedI = bt.getI() + offset.deltaI();
            int translatedJ = bt.getJ() + offset.deltaJ();
            String posKey = key(translatedI, translatedJ);
            TileMatrix.TileCoord at = aByPos.get(posKey);
            if (at != null) {
                if (!at.getId().equals(bt.getId())) {
                    return null;
                }
                continue;
            }
            toAdd.add(cloneWithCoordinates(bt, translatedI, translatedJ));
        }
        return toAdd;
    }

    private static TileMatrix.TileCoord cloneWithCoordinates(TileMatrix.TileCoord src, int i, int j) {
        TileMatrix.TileCoord added = new TileMatrix.TileCoord();
        added.setId(src.getId());
        added.setI(i);
        added.setJ(j);
        added.setTextureFile(src.getTextureFile());
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
}
