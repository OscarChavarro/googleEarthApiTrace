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
    private final List<TileBounds> boundsByDepth = new ArrayList<>();
    private int tileCount;

    public PyramidCatalog(Path rootFolder) {
        this.rootFolder = rootFolder;
    }

    public synchronized boolean add(TileRecord tile) {
        int depth = tile.address().depth();
        if (depth > MAX_ADDRESSABLE_DEPTH) {
            return false;
        }
        while (tilesByDepth.size() <= depth) {
            tilesByDepth.add(new HashMap<>());
            boundsByDepth.add(null);
        }
        TileRecord previous = tilesByDepth.get(depth).putIfAbsent(
            key(tile.address().column(), tile.address().southRow()), tile
        );
        if (previous == null) {
            tileCount++;
            TileAddress address = tile.address();
            TileBounds bounds = boundsByDepth.get(depth);
            boundsByDepth.set(depth, bounds == null
                ? new TileBounds(
                    address.column(), address.southRow(), address.column(), address.southRow()
                )
                : new TileBounds(
                    Math.min(bounds.minimumColumn(), address.column()),
                    Math.min(bounds.minimumSouthRow(), address.southRow()),
                    Math.max(bounds.maximumColumn(), address.column()),
                    Math.max(bounds.maximumSouthRow(), address.southRow())
                )
            );
            return true;
        }
        return false;
    }

    public synchronized TileRecord tileAt(int depth, int column, int southRow) {
        if (depth < 0 || depth >= tilesByDepth.size()) {
            return null;
        }
        return tilesByDepth.get(depth).get(key(column, southRow));
    }

    public synchronized TileRecord nearestAncestorAtOrAbove(
        int desiredDepth, int targetDepth, int column, int southRow
    ) {
        for (int depth = desiredDepth; depth >= 0; depth--) {
            int shift = targetDepth - depth;
            TileRecord tile = tileAt(depth, column >> shift, southRow >> shift);
            if (tile != null) {
                return tile;
            }
        }
        return null;
    }

    public synchronized int maxDepth() {
        return Math.max(0, tilesByDepth.size() - 1);
    }

    public synchronized int tileCount() {
        return tileCount;
    }

    public synchronized Optional<TileBounds> tileBoundsAt(int depth) {
        if (depth < 0 || depth >= boundsByDepth.size()) {
            return Optional.empty();
        }
        return Optional.ofNullable(boundsByDepth.get(depth));
    }

    public Path rootFolder() {
        return rootFolder;
    }

    public synchronized Path relativePathFor(TileAddress address) {
        TileRecord tile = tileAt(address.depth(), address.column(), address.southRow());
        if (tile != null) {
            return rootFolder.relativize(tile.imagePath());
        }
        return expectedRelativePathFor(address);
    }

    public synchronized boolean setSelectionRecursively(TileRecord tile, boolean selected) {
        if (tile == null) {
            return false;
        }
        boolean changed = false;
        String quadKeyPrefix = tile.address().quadKey();
        for (int depth = tile.address().depth(); depth < tilesByDepth.size(); depth++) {
            for (TileRecord candidate : tilesByDepth.get(depth).values()) {
                if (candidate.address().quadKey().startsWith(quadKeyPrefix)) {
                    changed |= candidate.setSelected(selected);
                }
            }
        }
        return changed;
    }

    public synchronized boolean clearSelection() {
        boolean changed = false;
        for (Map<Long, TileRecord> tiles : tilesByDepth) {
            for (TileRecord tile : tiles.values()) {
                changed |= tile.setSelected(false);
            }
        }
        return changed;
    }

    private static long key(int column, int southRow) {
        return ((long) column << 32) ^ (southRow & 0xffffffffL);
    }

    private static Path expectedRelativePathFor(TileAddress address) {
        String quadKey = address.quadKey();
        Path path = Path.of(quadKey + ".png");
        for (int index = quadKey.length() - 1; index >= 1; index--) {
            path = Path.of(String.valueOf(quadKey.charAt(index)), path.toString());
        }
        return path;
    }
}
