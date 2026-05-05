package frametexturenormalizer.processing.matrix;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import frametexturenormalizer.model.TileMatrix;

public final class TileMatrixFiltererByConsistency {
    public List<TileMatrix> filter(List<TileMatrix> matrices) {
        if (matrices == null || matrices.isEmpty()) {
            return List.of();
        }

        List<TileMatrix> out = new ArrayList<>(matrices.size());
        for (TileMatrix matrix : matrices) {
            if (matrix == null || matrix.getRows() <= 0 || matrix.getCols() <= 0 || matrix.getTiles() == null || matrix.getTiles().isEmpty()) {
                continue;
            }

            TileMatrix filtered = keepFirstConnectedComponent(matrix);
            if (filtered != null && filtered.getRows() > 0 && filtered.getCols() > 0 && filtered.getTiles() != null && !filtered.getTiles().isEmpty()) {
                out.add(filtered);
            }
        }
        return out;
    }

    private TileMatrix keepFirstConnectedComponent(TileMatrix matrix) {
        List<TileMatrix.TileCoord> tiles = new ArrayList<>(matrix.getTiles());
        tiles.sort(Comparator.comparingInt(TileMatrix.TileCoord::i).thenComparingInt(TileMatrix.TileCoord::j));

        Map<String, TileMatrix.TileCoord> byPos = new HashMap<>();
        for (TileMatrix.TileCoord t : tiles) {
            if (t != null) {
                byPos.put(key(t.i(), t.j()), t);
            }
        }
        if (byPos.isEmpty()) {
            return null;
        }

        Set<String> visited = new HashSet<>();
        Set<String> keep = null;

        for (TileMatrix.TileCoord seed : tiles) {
            if (seed == null) {
                continue;
            }
            String seedKey = key(seed.i(), seed.j());
            if (visited.contains(seedKey)) {
                continue;
            }
            Set<String> component = bfsComponent(seed, byPos, visited);
            if (component != null && !component.isEmpty()) {
                keep = component;
                break;
            }
        }

        if (keep == null || keep.isEmpty()) {
            return null;
        }

        List<TileMatrix.TileCoord> keptTiles = new ArrayList<>();
        int minI = Integer.MAX_VALUE;
        int minJ = Integer.MAX_VALUE;
        int maxI = Integer.MIN_VALUE;
        int maxJ = Integer.MIN_VALUE;
        for (TileMatrix.TileCoord t : tiles) {
            if (t == null) {
                continue;
            }
            if (!keep.contains(key(t.i(), t.j()))) {
                continue;
            }
            keptTiles.add(t);
            minI = Math.min(minI, t.i());
            minJ = Math.min(minJ, t.j());
            maxI = Math.max(maxI, t.i());
            maxJ = Math.max(maxJ, t.j());
        }
        if (keptTiles.isEmpty()) {
            return null;
        }

        int shiftI = minI < 0 ? -minI : -minI;
        int shiftJ = minJ < 0 ? -minJ : -minJ;
        List<TileMatrix.TileCoord> normalized = new ArrayList<>(keptTiles.size());
        for (TileMatrix.TileCoord t : keptTiles) {
            normalized.add(new TileMatrix.TileCoord(t.tileId(), t.i() + shiftI, t.j() + shiftJ, t.textureFile()));
        }

        int rows = maxI - minI + 1;
        int cols = maxJ - minJ + 1;
        return new TileMatrix(matrix.getFrameId(), rows, cols, normalized);
    }

    private static Set<String> bfsComponent(
        TileMatrix.TileCoord seed,
        Map<String, TileMatrix.TileCoord> byPos,
        Set<String> visited
    ) {
        Set<String> component = new HashSet<>();
        ArrayDeque<TileMatrix.TileCoord> q = new ArrayDeque<>();
        q.add(seed);
        visited.add(key(seed.i(), seed.j()));

        while (!q.isEmpty()) {
            TileMatrix.TileCoord cur = q.removeFirst();
            String curKey = key(cur.i(), cur.j());
            component.add(curKey);

            enqueueNeighbor(cur.i() - 1, cur.j(), byPos, visited, q);
            enqueueNeighbor(cur.i() + 1, cur.j(), byPos, visited, q);
            enqueueNeighbor(cur.i(), cur.j() - 1, byPos, visited, q);
            enqueueNeighbor(cur.i(), cur.j() + 1, byPos, visited, q);
        }

        return component;
    }

    private static void enqueueNeighbor(
        int i,
        int j,
        Map<String, TileMatrix.TileCoord> byPos,
        Set<String> visited,
        ArrayDeque<TileMatrix.TileCoord> q
    ) {
        String k = key(i, j);
        if (visited.contains(k)) {
            return;
        }
        TileMatrix.TileCoord n = byPos.get(k);
        if (n == null) {
            return;
        }
        visited.add(k);
        q.addLast(n);
    }

    private static String key(int i, int j) {
        return i + ":" + j;
    }
}
