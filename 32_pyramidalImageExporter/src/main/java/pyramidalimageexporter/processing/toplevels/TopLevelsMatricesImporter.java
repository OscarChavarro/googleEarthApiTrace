package pyramidalimageexporter.processing.toplevels;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.TileCoord;
import pyramidalimageexporter.model.TopLevelTiles;

public final class TopLevelsMatricesImporter {
    private static final int MAX_TOP_LEVEL = 5;

    public List<MatrixLayer> importLayers(TopLevelTiles topLevelTiles) {
        if (topLevelTiles == null || topLevelTiles.getByStripId() == null || topLevelTiles.getByStripId().isEmpty()) {
            System.out.println("TopLevelsMatricesImporter: no top-level tiles to import.");
            return List.of();
        }

        Map<String, String> textureByPrefix = new LinkedHashMap<>();
        for (TopLevelTiles.TopLevelTile tile : topLevelTiles.getByStripId().values()) {
            if (tile == null) {
                continue;
            }
            String texture = pickTexture(tile);
            if (texture == null || texture.isBlank()) {
                continue;
            }
            List<Integer> path = normalizePath(tile.getPathFromRoot());
            for (int depth = 0; depth <= MAX_TOP_LEVEL; depth++) {
                String prefix = keyOf(path, depth);
                textureByPrefix.putIfAbsent(prefix, texture);
            }
        }

        List<MatrixLayer> out = new ArrayList<>();
        for (int level = 0; level <= MAX_TOP_LEVEL; level++) {
            int side = 1 << level;
            Map<String, TileCoord> byCell = new LinkedHashMap<>();
            for (TopLevelTiles.TopLevelTile tile : topLevelTiles.getByStripId().values()) {
                if (tile == null) {
                    continue;
                }
                List<Integer> path = normalizePath(tile.getPathFromRoot());
                String prefix = keyOf(path, level);
                int[] rc = rowColFromPrefix(prefix);
                if (rc == null || rc[0] < 0 || rc[1] < 0 || rc[0] >= side || rc[1] >= side) {
                    continue;
                }
                String cellKey = rc[0] + "_" + rc[1];
                if (byCell.containsKey(cellKey)) {
                    continue;
                }
                TileCoord coord = new TileCoord();
                coord.setId(prefix.isBlank() ? "0" : prefix);
                coord.setI(rc[0]);
                coord.setJ(rc[1]);
                coord.setTextureFile(textureByPrefix.get(prefix));
                byCell.put(cellKey, coord);
            }

            if (level == 0 && byCell.isEmpty()) {
                TileCoord root = new TileCoord();
                root.setId("0");
                root.setI(0);
                root.setJ(0);
                root.setTextureFile(textureByPrefix.get(""));
                byCell.put("0_0", root);
            }
            if (byCell.isEmpty()) {
                continue;
            }

            MatrixLayer layer = new MatrixLayer();
            layer.setFrameId(level);
            layer.setRows(side);
            layer.setCols(side);
            layer.setSourceFolderName(String.format("topLevel_matrix_%02d", level));
            layer.setTiles(new ArrayList<>(byCell.values()));
            out.add(layer);
            System.out.println(
                "TopLevelsMatricesImporter: level " + level
                    + " matrix=" + side + "x" + side
                    + ", occupiedCells=" + byCell.size()
            );
        }

        System.out.println("TopLevelsMatricesImporter: levels imported=" + out.size());
        return out;
    }

    private static String pickTexture(TopLevelTiles.TopLevelTile tile) {
        if (tile.getAppearances() == null || tile.getAppearances().isEmpty()) {
            return null;
        }
        for (int i = tile.getAppearances().size() - 1; i >= 0; i--) {
            TopLevelTiles.FrameAppearance appearance = tile.getAppearances().get(i);
            if (appearance == null) {
                continue;
            }
            String imagePath = appearance.getImagePath();
            if (imagePath != null && !imagePath.isBlank()) {
                return imagePath.trim();
            }
        }
        return null;
    }

    private static List<Integer> normalizePath(List<Integer> path) {
        if (path == null || path.isEmpty()) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>(path.size());
        for (Integer step : path) {
            if (step == null || step < 0 || step > 3) {
                break;
            }
            out.add(step);
        }
        return out;
    }

    private static String keyOf(List<Integer> path, int depth) {
        if (depth <= 0 || path == null || path.isEmpty()) {
            return "";
        }
        int max = Math.min(depth, path.size());
        StringBuilder sb = new StringBuilder(max);
        for (int i = 0; i < max; i++) {
            sb.append(path.get(i));
        }
        return sb.toString();
    }

    private static int[] rowColFromPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return new int[]{0, 0};
        }
        int row = 0;
        int col = 0;
        int depth = prefix.length();
        for (int i = 0; i < depth; i++) {
            char c = prefix.charAt(i);
            int q = c - '0';
            if (q < 0 || q > 3) {
                return null;
            }
            row <<= 1;
            col <<= 1;
            boolean south = (q == 0 || q == 1);
            boolean east = (q == 1 || q == 2);
            if (south) {
                row |= 1;
            }
            if (east) {
                col |= 1;
            }
        }
        return new int[]{row, col};
    }
}
