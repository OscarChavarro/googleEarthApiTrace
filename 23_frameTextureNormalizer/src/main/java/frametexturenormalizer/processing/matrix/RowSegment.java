package frametexturenormalizer.processing.matrix;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class RowSegment {
    private final Map<Integer, MatrixCell> byTileId = new HashMap<>();
    private final Map<String, MatrixCell> byCoord = new HashMap<>();

    RowSegment copyShifted(int di, int dj) {
        RowSegment out = new RowSegment();
        for (MatrixCell c : byTileId.values()) {
            MatrixCell nc = new MatrixCell(c.i() + di, c.j() + dj, c.tile());
            out.put(nc);
        }
        return out;
    }

    void put(MatrixCell cell) {
        byTileId.put(cell.tileId(), cell);
        byCoord.put(key(cell.i(), cell.j()), cell);
    }

    MatrixCell getByCoord(int i, int j) {
        return byCoord.get(key(i, j));
    }

    boolean containsTileId(int id) {
        return byTileId.containsKey(id);
    }

    MatrixCell getByTileId(int id) {
        return byTileId.get(id);
    }

    List<MatrixCell> cellsSortedLeftToRight() {
        List<MatrixCell> out = new ArrayList<>(byTileId.values());
        out.sort(Comparator.comparingInt(MatrixCell::j).thenComparingInt(MatrixCell::tileId));
        return out;
    }

    Collection<MatrixCell> cells() {
        return byTileId.values();
    }

    List<Integer> sortedRowIndices() {
        return byTileId.values().stream().map(MatrixCell::i).distinct().sorted().toList();
    }

    int size() {
        return byTileId.size();
    }

    void normalizeRowStartToZero() {
        int minJ = minJ();
        if (minJ == 0) {
            return;
        }
        rebuildShifted(0, -minJ);
    }

    private void rebuildShifted(int di, int dj) {
        List<MatrixCell> cells = new ArrayList<>(byTileId.values());
        byTileId.clear();
        byCoord.clear();
        for (MatrixCell c : cells) {
            c.shift(di, dj);
            put(c);
        }
    }

    int minI() {
        int min = Integer.MAX_VALUE;
        for (MatrixCell c : byTileId.values()) {
            min = Math.min(min, c.i());
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    int maxI() {
        int max = Integer.MIN_VALUE;
        for (MatrixCell c : byTileId.values()) {
            max = Math.max(max, c.i());
        }
        return max == Integer.MIN_VALUE ? 0 : max;
    }

    int height() {
        return maxI() - minI() + 1;
    }

    int minJ() {
        int min = Integer.MAX_VALUE;
        for (MatrixCell c : byTileId.values()) {
            min = Math.min(min, c.j());
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    int maxJ() {
        int max = Integer.MIN_VALUE;
        for (MatrixCell c : byTileId.values()) {
            max = Math.max(max, c.j());
        }
        return max == Integer.MIN_VALUE ? 0 : max;
    }

    int width() {
        return maxJ() - minJ() + 1;
    }

    private static String key(int i, int j) {
        return i + ":" + j;
    }
}
