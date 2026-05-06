package frametexturenormalizer.processing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.TileInstance;

public final class NeighborhoodDebugReporter {
    private NeighborhoodDebugReporter() {
    }

    public static void dumpFrames(String stage, List<FrameData> frames) {
        if (frames == null || frames.isEmpty()) {
            return;
        }
        for (FrameData frame : frames) {
            dumpFrame(stage, frame);
        }
    }

    public static void dumpFrame(String stage, FrameData frame) {
        Integer debugFrame = parseDebugFrame();
        if (debugFrame == null || frame == null || frame.getId() != debugFrame.intValue()) {
            return;
        }
        List<TileInstance> tiles = frame.getTiles();
        Map<Integer, TileInstance> byId = new HashMap<>();
        for (TileInstance tile : tiles) {
            if (tile != null) {
                byId.put(tile.getTileId(), tile);
            }
        }
        List<Integer> componentSizes = componentSizes(byId, false);
        List<Integer> ewSizes = componentSizes(byId, true);
        System.out.println("[NeighborhoodDebug] stage=" + stage + " frame=" + frame.getId()
            + " tiles=" + byId.size()
            + " components=" + componentSizes
            + " ewComponents=" + ewSizes);

        List<TileInstance> ordered = new ArrayList<>(byId.values());
        ordered.sort(Comparator.comparingInt(TileInstance::getTileId));
        for (TileInstance tile : ordered) {
            System.out.println(String.format(
                "[NeighborhoodDebug] tile=%d full=%s westCut=%s N=%s S=%s E=%s W=%s tex=%s",
                tile.getTileId(),
                tile.isFullResolutionWithRespectToTexture(),
                tile.isWestCuttingCell(),
                value(tile.getNorthNeighbor()),
                value(tile.getSouthNeighbor()),
                value(tile.getEastNeighbor()),
                value(tile.getWestNeighbor()),
                tile.getTextureFile()
            ));
        }
    }

    private static List<Integer> componentSizes(Map<Integer, TileInstance> byId, boolean ewOnly) {
        if (byId.isEmpty()) {
            return List.of();
        }
        Set<Integer> visited = new LinkedHashSet<>();
        List<Integer> sizes = new ArrayList<>();
        for (Integer seed : byId.keySet()) {
            if (seed == null || visited.contains(seed)) {
                continue;
            }
            int size = bfs(seed, byId, visited, ewOnly);
            if (size > 0) {
                sizes.add(size);
            }
        }
        sizes.sort(Comparator.reverseOrder());
        return sizes;
    }

    private static int bfs(Integer seed, Map<Integer, TileInstance> byId, Set<Integer> visited, boolean ewOnly) {
        if (seed == null || !byId.containsKey(seed) || visited.contains(seed)) {
            return 0;
        }
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(seed);
        visited.add(seed);
        int size = 0;
        while (!queue.isEmpty()) {
            Integer currentId = queue.removeFirst();
            TileInstance tile = byId.get(currentId);
            if (tile == null) {
                continue;
            }
            size++;
            enqueue(tile.getEastNeighbor(), byId, visited, queue);
            enqueue(tile.getWestNeighbor(), byId, visited, queue);
            if (!ewOnly) {
                enqueue(tile.getNorthNeighbor(), byId, visited, queue);
                enqueue(tile.getSouthNeighbor(), byId, visited, queue);
            }
        }
        return size;
    }

    private static void enqueue(
        Integer neighborId,
        Map<Integer, TileInstance> byId,
        Set<Integer> visited,
        ArrayDeque<Integer> queue
    ) {
        if (neighborId == null || visited.contains(neighborId) || !byId.containsKey(neighborId)) {
            return;
        }
        visited.add(neighborId);
        queue.addLast(neighborId);
    }

    private static Integer parseDebugFrame() {
        String raw = System.getProperty("pib.debug.matrix.frame");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String value(Integer v) {
        return v == null ? "-" : String.valueOf(v);
    }
}
