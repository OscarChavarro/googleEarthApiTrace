package pyramidalimageexporter.processing.toplevels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.TileCoord;
import pyramidalimageexporter.model.TopLevelTiles;

/**
 * Rebuilds the top levels (0..MAX_TOP_LEVEL) of the pyramidal image from the
 * globe-level strips exported by 22_dumpAnalyzer in topLevelTiles.json.
 *
 * The reconstruction relies on two facts observed in the traced data:
 * - Each globe strip covers exactly 1/32 x 1/32 of the world texture space,
 *   so the strip lattice IS the level-5 quadtree grid.
 * - Each appearance image is itself a quadtree-aligned 256x256 tile at some
 *   level k (0..4). An appearance whose texCoord spans 1/32 identifies a
 *   whole-world image (level 0) and pins the strip's world rectangle; any
 *   other appearance is affinely unmapped to recover its image's level and
 *   world cell.
 *
 * All u/v values use the OpenGL texture convention: v = 0 at the image
 * bottom, which in world terms means v = 0 at the south pole side.
 */
public final class TopLevelsMatricesImporter {
    private static final int MAX_TOP_LEVEL = 5;
    private static final int STRIP_GRID_SIDE = 32;
    private static final double STRIP_SIZE = 1.0 / STRIP_GRID_SIDE;
    private static final double SIZE_TOLERANCE = 1.0e-4;
    private static final double GRID_TOLERANCE = 1.0e-3;

    private record ImageTileVote(int level, int cellU, int cellV) {}
    private record ImageTile(String path, int level, int cellU, int cellV, int observations) {}

    public List<MatrixLayer> importLayers(TopLevelTiles topLevelTiles) {
        if (topLevelTiles == null || topLevelTiles.getByStripId() == null || topLevelTiles.getByStripId().isEmpty()) {
            System.out.println("TopLevelsMatricesImporter: no top-level tiles to import.");
            return List.of();
        }

        Map<String, double[]> stripWorldOriginByStripId = computeStripWorldOrigins(topLevelTiles);
        if (stripWorldOriginByStripId.isEmpty()) {
            System.out.println(
                "TopLevelsMatricesImporter: no strip has a whole-world appearance (texCoord size 1/"
                    + STRIP_GRID_SIDE + "); can not anchor strips to world coordinates."
            );
            return List.of();
        }
        Map<String, ImageTile> imagesByCellKey = catalogImages(topLevelTiles, stripWorldOriginByStripId);
        System.out.println(
            "TopLevelsMatricesImporter: anchored strips=" + stripWorldOriginByStripId.size()
                + "/" + topLevelTiles.getByStripId().size()
                + ", catalogued quadtree-aligned images=" + imagesByCellKey.size()
        );

        List<MatrixLayer> out = new ArrayList<>();
        for (int level = 0; level <= MAX_TOP_LEVEL; level++) {
            out.add(buildLayer(level, imagesByCellKey));
        }
        System.out.println("TopLevelsMatricesImporter: levels imported=" + out.size());
        return out;
    }

    /**
     * Pass 1: a strip's world rectangle equals its texCoord inside any
     * whole-world appearance (texCoord size exactly one strip).
     */
    private static Map<String, double[]> computeStripWorldOrigins(TopLevelTiles topLevelTiles) {
        Map<String, double[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, TopLevelTiles.TopLevelTile> entry : topLevelTiles.getByStripId().entrySet()) {
            TopLevelTiles.TopLevelTile tile = entry.getValue();
            if (tile == null || tile.getAppearances() == null) {
                continue;
            }
            for (TopLevelTiles.FrameAppearance appearance : tile.getAppearances()) {
                TopLevelTiles.TexCoord texCoord = appearance == null ? null : appearance.getTexCoord();
                if (texCoord == null) {
                    continue;
                }
                double width = texCoord.getU1() - texCoord.getU0();
                double height = texCoord.getV1() - texCoord.getV0();
                if (Math.abs(width - STRIP_SIZE) <= SIZE_TOLERANCE && Math.abs(height - STRIP_SIZE) <= SIZE_TOLERANCE) {
                    out.put(entry.getKey(), new double[]{texCoord.getU0(), texCoord.getV0()});
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Pass 2: unmap every appearance to recover the world cell its image
     * covers. Images seen through several strips vote; the majority wins.
     */
    private static Map<String, ImageTile> catalogImages(
        TopLevelTiles topLevelTiles,
        Map<String, double[]> stripWorldOriginByStripId
    ) {
        Map<String, Map<ImageTileVote, Integer>> votesByImagePath = new LinkedHashMap<>();
        for (Map.Entry<String, TopLevelTiles.TopLevelTile> entry : topLevelTiles.getByStripId().entrySet()) {
            double[] worldOrigin = stripWorldOriginByStripId.get(entry.getKey());
            TopLevelTiles.TopLevelTile tile = entry.getValue();
            if (worldOrigin == null || tile == null || tile.getAppearances() == null) {
                continue;
            }
            for (TopLevelTiles.FrameAppearance appearance : tile.getAppearances()) {
                ImageTileVote vote = toImageTileVote(appearance, worldOrigin);
                if (vote == null) {
                    continue;
                }
                votesByImagePath
                    .computeIfAbsent(appearance.getImagePath().trim(), key -> new HashMap<>())
                    .merge(vote, 1, Integer::sum);
            }
        }

        Map<String, ImageTile> imagesByCellKey = new LinkedHashMap<>();
        for (Map.Entry<String, Map<ImageTileVote, Integer>> entry : votesByImagePath.entrySet()) {
            ImageTileVote best = null;
            int bestCount = 0;
            int totalCount = 0;
            for (Map.Entry<ImageTileVote, Integer> vote : entry.getValue().entrySet()) {
                totalCount += vote.getValue();
                if (vote.getValue() > bestCount) {
                    bestCount = vote.getValue();
                    best = vote.getKey();
                }
            }
            if (best == null) {
                continue;
            }
            if (bestCount < totalCount) {
                System.out.println(
                    "TopLevelsMatricesImporter: image " + entry.getKey()
                        + " has inconsistent placement votes (" + bestCount + "/" + totalCount
                        + " for the winner); keeping majority."
                );
            }
            ImageTile candidate = new ImageTile(entry.getKey(), best.level(), best.cellU(), best.cellV(), bestCount);
            String cellKey = cellKey(best.level(), best.cellU(), best.cellV());
            ImageTile current = imagesByCellKey.get(cellKey);
            if (current == null || candidate.observations() > current.observations()
                || (candidate.observations() == current.observations() && candidate.path().compareTo(current.path()) < 0)) {
                imagesByCellKey.put(cellKey, candidate);
            }
        }
        return imagesByCellKey;
    }

    private static ImageTileVote toImageTileVote(TopLevelTiles.FrameAppearance appearance, double[] stripWorldOrigin) {
        if (appearance == null || appearance.getImagePath() == null || appearance.getImagePath().isBlank()) {
            return null;
        }
        TopLevelTiles.TexCoord texCoord = appearance.getTexCoord();
        if (texCoord == null) {
            return null;
        }
        double texWidth = texCoord.getU1() - texCoord.getU0();
        double texHeight = texCoord.getV1() - texCoord.getV0();
        if (texWidth <= SIZE_TOLERANCE || texHeight <= SIZE_TOLERANCE) {
            return null;
        }
        double imageWorldWidth = STRIP_SIZE / texWidth;
        double imageWorldHeight = STRIP_SIZE / texHeight;
        if (Math.abs(imageWorldWidth - imageWorldHeight) > GRID_TOLERANCE) {
            return null;
        }
        int level = (int) Math.round(Math.log(1.0 / imageWorldWidth) / Math.log(2.0));
        if (level < 0 || level > MAX_TOP_LEVEL || Math.abs(imageWorldWidth - 1.0 / (1 << level)) > GRID_TOLERANCE) {
            return null;
        }
        double originU = stripWorldOrigin[0] - texCoord.getU0() * imageWorldWidth;
        double originV = stripWorldOrigin[1] - texCoord.getV0() * imageWorldHeight;
        int cellU = (int) Math.round(originU / imageWorldWidth);
        int cellV = (int) Math.round(originV / imageWorldHeight);
        if (Math.abs(originU - cellU * imageWorldWidth) > GRID_TOLERANCE
            || Math.abs(originV - cellV * imageWorldHeight) > GRID_TOLERANCE) {
            return null;
        }
        int side = 1 << level;
        if (cellU < 0 || cellV < 0 || cellU >= side || cellV >= side) {
            return null;
        }
        return new ImageTileVote(level, cellU, cellV);
    }

    /**
     * Pass 3: fill every cell of the level with the deepest catalogued image
     * containing it, exposing the corresponding texture sub-rectangle.
     */
    private static MatrixLayer buildLayer(int level, Map<String, ImageTile> imagesByCellKey) {
        int side = 1 << level;
        List<TileCoord> tiles = new ArrayList<>();
        int nativeResolutionCells = 0;
        int derivedCells = 0;
        for (int row = 0; row < side; row++) {
            for (int col = 0; col < side; col++) {
                // Matrix row 0 is the north edge; world v grows to the north pole side.
                double cellU0 = (double) col / side;
                double cellV0 = 1.0 - (double) (row + 1) / side;
                double cellSize = 1.0 / side;
                ImageTile source = findDeepestCoveringImage(level, cellU0, cellV0, imagesByCellKey);
                if (source == null) {
                    continue;
                }
                double sourceSize = 1.0 / (1 << source.level());
                TileCoord tile = new TileCoord();
                tile.setId(quadtreePathLabel(level, row, col));
                tile.setI(row);
                tile.setJ(col);
                tile.setTextureFile(source.path());
                tile.setTextureSubRect(
                    (cellU0 - source.cellU() * sourceSize) / sourceSize,
                    (cellV0 - source.cellV() * sourceSize) / sourceSize,
                    (cellU0 + cellSize - source.cellU() * sourceSize) / sourceSize,
                    (cellV0 + cellSize - source.cellV() * sourceSize) / sourceSize
                );
                tiles.add(tile);
                if (source.level() == level) {
                    nativeResolutionCells++;
                }
                else {
                    derivedCells++;
                }
            }
        }

        MatrixLayer layer = new MatrixLayer();
        layer.setFrameId(level);
        layer.setRows(side);
        layer.setCols(side);
        layer.setSourceFolderName(String.format("topLevel_matrix_%02d", level));
        layer.setTiles(tiles);
        System.out.println(
            "TopLevelsMatricesImporter: level " + level
                + " matrix=" + side + "x" + side
                + ", nativeResolutionCells=" + nativeResolutionCells
                + ", derivedCells=" + derivedCells
                + ", emptyCells=" + (side * side - tiles.size())
        );
        return layer;
    }

    private static ImageTile findDeepestCoveringImage(
        int level,
        double cellU0,
        double cellV0,
        Map<String, ImageTile> imagesByCellKey
    ) {
        for (int candidateLevel = level; candidateLevel >= 0; candidateLevel--) {
            double candidateSize = 1.0 / (1 << candidateLevel);
            int cellU = (int) Math.floor(cellU0 / candidateSize + GRID_TOLERANCE);
            int cellV = (int) Math.floor(cellV0 / candidateSize + GRID_TOLERANCE);
            ImageTile image = imagesByCellKey.get(cellKey(candidateLevel, cellU, cellV));
            if (image != null) {
                return image;
            }
        }
        return null;
    }

    private static String cellKey(int level, int cellU, int cellV) {
        return level + "_" + cellU + "_" + cellV;
    }

    /**
     * Quadrant labels use the same convention as the rest of the pipeline:
     * 0 = south-west, 1 = south-east, 2 = north-east, 3 = north-west, where
     * "south" corresponds to growing matrix row i.
     */
    private static String quadtreePathLabel(int level, int row, int col) {
        if (level <= 0) {
            return "0";
        }
        StringBuilder sb = new StringBuilder(level);
        for (int depth = level - 1; depth >= 0; depth--) {
            boolean south = ((row >> depth) & 1) == 1;
            boolean east = ((col >> depth) & 1) == 1;
            int quadrant;
            if (south && !east) {
                quadrant = 0;
            }
            else if (south) {
                quadrant = 1;
            }
            else if (east) {
                quadrant = 2;
            }
            else {
                quadrant = 3;
            }
            sb.append(quadrant);
        }
        return sb.toString();
    }
}
