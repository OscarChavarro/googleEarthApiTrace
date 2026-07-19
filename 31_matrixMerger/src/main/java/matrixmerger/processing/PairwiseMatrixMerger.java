package matrixmerger.processing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import matrixmerger.model.contract.FrameTileMatrix;
import matrixmerger.io.WestCuttersJsonReader;

public final class PairwiseMatrixMerger {
    private Set<String> westCutterTileIds = Set.of();

    public void setWestCutterTileIds(Set<String> westCutterTileIds) {
        if (westCutterTileIds == null || westCutterTileIds.isEmpty()) {
            this.westCutterTileIds = Set.of();
            return;
        }
        Set<String> normalized = new HashSet<>();
        for (String id : westCutterTileIds) {
            String canonical = WestCuttersJsonReader.normalizeScopedTileId(id);
            if (canonical != null) {
                normalized.add(canonical);
            }
        }
        this.westCutterTileIds = Collections.unmodifiableSet(normalized);
    }

    public boolean merge(FrameTileMatrix a, FrameTileMatrix b) {
        if (!canMerge(a, b)) {
            return false;
        }

        Map<String, FrameTileMatrix.TileCoord> aById = indexTilesById(a.getTiles());
        Map<String, FrameTileMatrix.TileCoord> bById = indexTilesById(b.getTiles());
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

        Map<String, FrameTileMatrix.TileCoord> aByPos = indexTilesByPosition(a.getTiles());
        if (aByPos == null) {
            return false;
        }

        List<FrameTileMatrix.TileCoord> tilesToAppend = collectAlignedTilesFromB(b, aByPos, offset);
        if (tilesToAppend == null) {
            return false;
        }

        appendTiles(a, tilesToAppend);
        normalizeIndices(a);
        return true;
    }

    private static boolean canMerge(FrameTileMatrix a, FrameTileMatrix b) {
        return a != null
            && b != null
            && a.getTiles() != null
            && b.getTiles() != null
            && !a.getTiles().isEmpty()
            && !b.getTiles().isEmpty();
    }

    private static Set<String> findSharedIds(
        Map<String, FrameTileMatrix.TileCoord> aById,
        Map<String, FrameTileMatrix.TileCoord> bById
    ) {
        Set<String> sharedIds = new HashSet<>(aById.keySet());
        sharedIds.retainAll(bById.keySet());
        return sharedIds;
    }

    private static MatrixOffset calculateAlignmentOffset(
        Set<String> sharedIds,
        Map<String, FrameTileMatrix.TileCoord> aById,
        Map<String, FrameTileMatrix.TileCoord> bById
    ) {
        Integer deltaI = null;
        Integer deltaJ = null;
        for (String id : sharedIds) {
            FrameTileMatrix.TileCoord at = aById.get(id);
            FrameTileMatrix.TileCoord bt = bById.get(id);
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

    private static Map<String, FrameTileMatrix.TileCoord> indexTilesById(List<FrameTileMatrix.TileCoord> tiles) {
        Map<String, FrameTileMatrix.TileCoord> out = new HashMap<>();
        for (FrameTileMatrix.TileCoord t : tiles) {
            if (t == null) {
                continue;
            }
            String id = t.getId();
            if (id == null || id.isBlank()) {
                return null;
            }
            FrameTileMatrix.TileCoord prev = out.putIfAbsent(id, t);
            if (prev != null && (prev.getI() != t.getI() || prev.getJ() != t.getJ())) {
                return null;
            }
        }
        return out;
    }

    private static Map<String, FrameTileMatrix.TileCoord> indexTilesByPosition(List<FrameTileMatrix.TileCoord> tiles) {
        Map<String, FrameTileMatrix.TileCoord> byPosition = new HashMap<>();
        for (FrameTileMatrix.TileCoord t : tiles) {
            String key = key(t.getI(), t.getJ());
            FrameTileMatrix.TileCoord prev = byPosition.putIfAbsent(key, t);
            if (prev != null && !prev.getId().equals(t.getId())) {
                return null;
            }
        }
        return byPosition;
    }

    private List<FrameTileMatrix.TileCoord> collectAlignedTilesFromB(
        FrameTileMatrix b,
        Map<String, FrameTileMatrix.TileCoord> aByPos,
        MatrixOffset offset
    ) {
        List<FrameTileMatrix.TileCoord> toAdd = new ArrayList<>();
        for (FrameTileMatrix.TileCoord bt : b.getTiles()) {
            if (bt == null) {
                continue;
            }
            int translatedI = bt.getI() + offset.deltaI();
            int translatedJ = bt.getJ() + offset.deltaJ();
            String posKey = key(translatedI, translatedJ);
            FrameTileMatrix.TileCoord at = aByPos.get(posKey);
            if (at != null) {
                if (!at.getId().equals(bt.getId())) {
                    return null;
                }
            }
            else {
                toAdd.add(cloneWithCoordinates(bt, translatedI, translatedJ));
            }
        }
        return toAdd;
    }

    private static FrameTileMatrix.TileCoord cloneWithCoordinates(FrameTileMatrix.TileCoord src, int i, int j) {
        FrameTileMatrix.TileCoord added = new FrameTileMatrix.TileCoord();
        added.setId(src.getId());
        added.setI(i);
        added.setJ(j);
        added.setTextureFile(src.getTextureFile());
        added.setUncles(src.getUncles());
        return added;
    }

    private static void appendTiles(FrameTileMatrix a, List<FrameTileMatrix.TileCoord> tilesToAppend) {
        a.getTiles().addAll(tilesToAppend);
    }

    private static void normalizeIndices(FrameTileMatrix matrix) {
        if (matrix.getTiles().isEmpty()) {
            matrix.setRows(0);
            matrix.setCols(0);
            return;
        }

        int minI = Integer.MAX_VALUE;
        int minJ = Integer.MAX_VALUE;
        int maxI = Integer.MIN_VALUE;
        int maxJ = Integer.MIN_VALUE;

        for (FrameTileMatrix.TileCoord t : matrix.getTiles()) {
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
            for (FrameTileMatrix.TileCoord t : matrix.getTiles()) {
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
