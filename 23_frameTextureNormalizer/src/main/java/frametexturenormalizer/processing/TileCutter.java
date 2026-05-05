package frametexturenormalizer.processing;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.TileInstance;

public final class TileCutter {
    private TileCutter() {
    }

    public static int cutWestOnSelectedTiles(List<TileInstance> tiles) {
        if (tiles == null || tiles.isEmpty()) {
            return 0;
        }

        Map<Integer, TileInstance> tileById = new HashMap<>();
        ArrayDeque<TileInstance> seeds = new ArrayDeque<>();
        for (TileInstance tile : tiles) {
            if (tile == null) {
                continue;
            }
            tileById.put(tile.getTileId(), tile);
            if (tile.isSelected()) {
                seeds.addLast(tile);
            }
        }
        if (seeds.isEmpty()) {
            return 0;
        }

        Set<Integer> visited = new HashSet<>();
        int cutCount = 0;
        while (!seeds.isEmpty()) {
            TileInstance seed = seeds.pollFirst();
            if (seed == null) {
                continue;
            }
            cutCount += propagateNorthSouthAndCut(seed, tileById, visited);
        }
        return cutCount;
    }

    public static int cutWestOnSelectedTilesAcrossFrames(List<FrameData> frames) {
        Set<Integer> cursedTileIds = selectedTileIdsAcrossFrames(frames);
        if (cursedTileIds.isEmpty()) {
            return 0;
        }
        return cutWestFromTileIdsAcrossFrames(frames, cursedTileIds);
    }

    public static Set<Integer> selectedTileIdsAcrossFrames(List<FrameData> frames) {
        if (frames == null || frames.isEmpty()) {
            return Set.of();
        }
        Set<Integer> cursedTileIds = new LinkedHashSet<>();
        for (FrameData frame : frames) {
            if (frame == null || frame.getTiles() == null) {
                continue;
            }
            for (TileInstance tile : frame.getTiles()) {
                if (tile != null && tile.isSelected()) {
                    cursedTileIds.add(tile.getTileId());
                }
            }
        }
        return cursedTileIds;
    }

    public static int cutWestFromTileIdsAcrossFrames(List<FrameData> frames, Set<Integer> cursedTileIds) {
        if (frames == null || frames.isEmpty() || cursedTileIds == null || cursedTileIds.isEmpty()) {
            return 0;
        }
        int totalCutCount = 0;
        for (FrameData frame : frames) {
            if (frame == null) {
                continue;
            }
            totalCutCount += cutWestFromTileIds(frame.getTiles(), cursedTileIds);
        }
        return totalCutCount;
    }

    private static int cutWestFromTileIds(List<TileInstance> tiles, Set<Integer> cursedTileIds) {
        if (tiles == null || tiles.isEmpty() || cursedTileIds == null || cursedTileIds.isEmpty()) {
            return 0;
        }
        Map<Integer, TileInstance> tileById = new HashMap<>();
        ArrayDeque<TileInstance> seeds = new ArrayDeque<>();
        for (TileInstance tile : tiles) {
            if (tile == null) {
                continue;
            }
            tileById.put(tile.getTileId(), tile);
        }
        for (Integer cursedId : cursedTileIds) {
            TileInstance seed = tileById.get(cursedId);
            if (seed != null) {
                seeds.addLast(seed);
            }
        }
        if (seeds.isEmpty()) {
            return 0;
        }

        Set<Integer> visited = new HashSet<>();
        int cutCount = 0;
        while (!seeds.isEmpty()) {
            TileInstance seed = seeds.pollFirst();
            if (seed == null) {
                continue;
            }
            cutCount += propagateNorthSouthAndCut(seed, tileById, visited);
        }
        return cutCount;
    }

    private static int propagateNorthSouthAndCut(
        TileInstance start,
        Map<Integer, TileInstance> tileById,
        Set<Integer> visited
    ) {
        if (start == null) {
            return 0;
        }
        ArrayDeque<TileInstance> pending = new ArrayDeque<>();
        pending.addLast(start);
        int markedNow = 0;
        while (!pending.isEmpty()) {
            TileInstance tile = pending.pollFirst();
            if (tile == null || !visited.add(tile.getTileId())) {
                continue;
            }

            if (!tile.isWestCuttingCell()) {
                tile.setWestCuttingCell(true);
                markedNow++;
            }
            tile.setSelected(false);
            cutWestConnection(tile, tileById);

            TileInstance north = tileById.get(tile.getNorthNeighbor());
            if (north != null && !visited.contains(north.getTileId())) {
                pending.addLast(north);
            }
            TileInstance south = tileById.get(tile.getSouthNeighbor());
            if (south != null && !visited.contains(south.getTileId())) {
                pending.addLast(south);
            }
        }
        return markedNow;
    }

    private static void cutWestConnection(TileInstance tile, Map<Integer, TileInstance> tileById) {
        Integer westId = tile.getWestNeighbor();
        if (westId == null) {
            return;
        }
        TileInstance westNeighbor = tileById.get(westId);
        if (westNeighbor != null && Objects.equals(westNeighbor.getEastNeighbor(), tile.getTileId())) {
            westNeighbor.setEastNeighbor(null);
        }
        tile.setWestNeighbor(null);
    }
}
