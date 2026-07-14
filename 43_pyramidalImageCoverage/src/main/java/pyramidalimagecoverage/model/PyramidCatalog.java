package pyramidalimagecoverage.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class PyramidCatalog {
    public static final int MAX_ADDRESSABLE_DEPTH = 30;

    private final Path rootFolder;
    private final List<Map<Long, TileRecord>> tilesByDepth = new ArrayList<>();
    private int tileCount;

    public PyramidCatalog(Path rootFolder) {
        this.rootFolder = rootFolder;
    }

    public void add(TileRecord tile) {
        int depth = tile.address().depth();
        if (depth > MAX_ADDRESSABLE_DEPTH) {
            return;
        }
        while (tilesByDepth.size() <= depth) {
            tilesByDepth.add(new HashMap<>());
        }
        tilesByDepth.get(depth).put(key(tile.address().column(), tile.address().southRow()), tile);
        tileCount++;
    }

    public TileRecord tileAt(int depth, int column, int southRow) {
        if (depth < 0 || depth >= tilesByDepth.size()) {
            return null;
        }
        return tilesByDepth.get(depth).get(key(column, southRow));
    }

    public TileRecord nearestAncestorAtOrAbove(int desiredDepth, int targetDepth, int column, int southRow) {
        for (int depth = desiredDepth; depth >= 0; depth--) {
            int shift = targetDepth - depth;
            TileRecord tile = tileAt(depth, column >> shift, southRow >> shift);
            if (tile != null) {
                return tile;
            }
        }
        return null;
    }

    public int maxDepth() {
        return Math.max(0, tilesByDepth.size() - 1);
    }

    public int tileCount() {
        return tileCount;
    }

    public Optional<TileBounds> tileBoundsAt(int depth) {
        if (depth < 0 || depth >= tilesByDepth.size() || tilesByDepth.get(depth).isEmpty()) {
            return Optional.empty();
        }
        int minimumColumn = Integer.MAX_VALUE;
        int minimumSouthRow = Integer.MAX_VALUE;
        int maximumColumn = Integer.MIN_VALUE;
        int maximumSouthRow = Integer.MIN_VALUE;
        for (TileRecord tile : tilesByDepth.get(depth).values()) {
            TileAddress address = tile.address();
            minimumColumn = Math.min(minimumColumn, address.column());
            minimumSouthRow = Math.min(minimumSouthRow, address.southRow());
            maximumColumn = Math.max(maximumColumn, address.column());
            maximumSouthRow = Math.max(maximumSouthRow, address.southRow());
        }
        return Optional.of(new TileBounds(
            minimumColumn, minimumSouthRow, maximumColumn, maximumSouthRow
        ));
    }

    public Path rootFolder() {
        return rootFolder;
    }

    private static long key(int column, int southRow) {
        return ((long) column << 32) ^ (southRow & 0xffffffffL);
    }
}
