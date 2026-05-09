package frametexturenormalizer.processing.neighborhood;

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

    public static Set<String> selectedTileIdsAcrossFrames(List<FrameData> frames) {
        if (frames == null || frames.isEmpty()) {
            return Set.of();
        }
        Set<String> cursedTileIds = new LinkedHashSet<>();
        Set<String> selectedCanonicalTextures = new LinkedHashSet<>();
        for (FrameData frame : frames) {
            if (frame == null || frame.getTiles() == null) {
                continue;
            }
            for (TileInstance tile : frame.getTiles()) {
                if (tile != null && tile.isSelected()) {
                    cursedTileIds.add(tile.getScopedId());
                    if (tile.getTextureFile() != null && !tile.getTextureFile().isBlank()) {
                        selectedCanonicalTextures.add(tile.getTextureFile());
                    }
                }
            }
        }
        if (selectedCanonicalTextures.isEmpty()) {
            return cursedTileIds;
        }

        // At this stage tiles have already been normalized to canonical texture paths.
        // Those canonical paths come from grouping by .signature and confirming full PNG equality.
        for (FrameData frame : frames) {
            if (frame == null || frame.getTiles() == null) {
                continue;
            }
            for (TileInstance tile : frame.getTiles()) {
                if (tile == null) {
                    continue;
                }
                String textureFile = tile.getTextureFile();
                if (textureFile != null && selectedCanonicalTextures.contains(textureFile)) {
                    cursedTileIds.add(tile.getScopedId());
                }
            }
        }
        return cursedTileIds;
    }

    public static Set<String> expandWestCutScopedIdsAcrossFrames(List<FrameData> frames, Set<String> cursedTileIds) {
        if (frames == null || frames.isEmpty() || cursedTileIds == null || cursedTileIds.isEmpty()) {
            return Set.of();
        }
        Set<String> expandedScopedIds = new LinkedHashSet<>();
        for (String scopedId : cursedTileIds) {
            if (scopedId != null && !scopedId.isBlank()) {
                expandedScopedIds.add(scopedId.trim());
            }
        }
        if (expandedScopedIds.isEmpty()) {
            return Set.of();
        }

        boolean changed;
        do {
            changed = false;
            for (FrameData frame : frames) {
                if (frame == null || frame.getTiles() == null || frame.getTiles().isEmpty()) {
                    continue;
                }
                Set<Integer> frameTileIds = new LinkedHashSet<>();
                for (TileInstance tile : frame.getTiles()) {
                    if (tile == null) {
                        continue;
                    }
                    String scopedId = tile.getScopedId();
                    if (scopedId != null && expandedScopedIds.contains(scopedId)) {
                        frameTileIds.add(tile.getTileId());
                    }
                }
                if (frameTileIds.isEmpty()) {
                    continue;
                }
                changed |= cutWestFromTileIds(frame.getTiles(), frameTileIds, expandedScopedIds) > 0;
            }
        }
        while (changed);
        return expandedScopedIds;
    }

    public static Set<String> scopedIdsFromLegacyTileIdsAcrossFrames(List<FrameData> frames, Set<Integer> legacyTileIds) {
        if (frames == null || frames.isEmpty() || legacyTileIds == null || legacyTileIds.isEmpty()) {
            return Set.of();
        }
        Set<String> scopedIds = new LinkedHashSet<>();
        for (FrameData frame : frames) {
            if (frame == null || frame.getTiles() == null) {
                continue;
            }
            for (TileInstance tile : frame.getTiles()) {
                if (tile != null && legacyTileIds.contains(tile.getTileId())) {
                    scopedIds.add(tile.getScopedId());
                }
            }
        }
        return scopedIds;
    }

    private static int cutWestFromTileIds(List<TileInstance> tiles, Set<Integer> cursedTileIds, Set<String> scopedIdsOut) {
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
            cutCount += propagateNorthSouthAndCut(seed, tileById, visited, scopedIdsOut);
        }
        return cutCount;
    }

    private static int propagateNorthSouthAndCut(
        TileInstance start,
        Map<Integer, TileInstance> tileById,
        Set<Integer> visited,
        Set<String> scopedIdsOut
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
            if (scopedIdsOut != null) {
                String scopedId = tile.getScopedId();
                if (scopedId != null && !scopedId.isBlank()) {
                    scopedIdsOut.add(scopedId);
                }
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
