package pyramidalimageexporter.processing.uncles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.MatrixLayerTile;
import pyramidalimageexporter.model.ParentGridTransform;

final class TileRootPathResolverTest {
    @Test
    void propagatesAnExplicitContainingParentGridWithoutInventingAnUncle() {
        MatrixLayer parent = layer(tile("parent-a", 0, 0), tile("parent-b", 0, 1));
        parent.setSourceFolderName("matrix_0");
        MatrixLayer child = layer(tile("child-a", 0, 0), tile("child-b", 0, 1));
        child.setSourceFolderName("matrix_1");
        child.setParentMatrixIndex(0);
        child.setParentGridTransform(new ParentGridTransform(0, 0));

        TileRootPathResolver.Resolution resolution = new TileRootPathResolver().resolve(
            List.of(parent, child),
            Map.of("parent-a", "033"),
            Map.of()
        );

        assertEquals("0333", resolution.pathById().get("child-a"));
        assertEquals("0332", resolution.pathById().get("child-b"));
        assertEquals(TileRootPathResolver.PathSource.GRID, resolution.sourceById().get("child-a"));
    }

    @Test
    void keepsAbsoluteSeedsAndRejectsRelativeLabels() {
        MatrixLayer layer = layer(tile("0", 0, 0), tile("00", 1, 0), tile("1", 1, 1));

        TileRootPathResolver.Resolution resolution = new TileRootPathResolver().resolve(List.of(layer));

        assertEquals("0", resolution.pathById().get("0"));
        assertEquals("00", resolution.pathById().get("00"));
        assertEquals(TileRootPathResolver.PathSource.DIRECT, resolution.sourceById().get("0"));
        assertEquals(TileRootPathResolver.PathSource.DIRECT, resolution.sourceById().get("00"));
        assertFalse(resolution.pathById().containsKey("1"));
    }

    @Test
    void positionsTheWholeMatrixFromOneAbsoluteUncleAnchor() {
        MatrixLayerTile anchored = tile("child-a", 0, 0);
        anchored.setUncles(List.of(new ToUncleRelationship(UncleDirections.SOUTH_WEST, "parent")));
        MatrixLayer layer = layer(anchored, tile("child-b", 0, 1));

        TileRootPathResolver.Resolution resolution = new TileRootPathResolver().resolve(
            List.of(layer),
            Map.of("parent", "03"),
            Map.of()
        );

        assertEquals("003", resolution.pathById().get("child-a"));
        assertEquals("002", resolution.pathById().get("child-b"));
        assertEquals(TileRootPathResolver.PathSource.UNCLE, resolution.sourceById().get("child-a"));
        assertEquals(TileRootPathResolver.PathSource.GRID, resolution.sourceById().get("child-b"));
    }

    @Test
    void resolvesAllUncleBordersAcrossTheAdjacentParentCell() {
        assertUncleResolution(UncleDirections.WEST_NORTH, "0302");
        assertUncleResolution(UncleDirections.WEST_SOUTH, "0301");
        assertUncleResolution(UncleDirections.EAST_NORTH, "0203");
        assertUncleResolution(UncleDirections.EAST_SOUTH, "0200");
        assertUncleResolution(UncleDirections.NORTH_WEST, "0320");
        assertUncleResolution(UncleDirections.NORTH_EAST, "0321");
        assertUncleResolution(UncleDirections.SOUTH_WEST, "0023");
        assertUncleResolution(UncleDirections.SOUTH_EAST, "0022");
    }

    @Test
    void wrapsAcrossTheWestLongitudeBoundary() {
        MatrixLayerTile child = tile("child", 0, 0);
        child.setUncles(List.of(new ToUncleRelationship(UncleDirections.WEST_NORTH, "uncle")));

        TileRootPathResolver.Resolution resolution = new TileRootPathResolver().resolve(
            List.of(layer(child)),
            Map.of("uncle", "030"),
            Map.of()
        );

        assertEquals("0212", resolution.pathById().get("child"));
    }

    @Test
    void fullWorldWidthForcesTheAntimeridianAtMatrixColumnZero() {
        MatrixLayer layer = layer(
            tile("anchor-a", 0, 0),
            tile("anchor-b", 0, 1),
            tile("outlier", 0, 2),
            tile("child", 0, 3)
        );
        layer.setCols(4);

        TileRootPathResolver.Resolution resolution = new TileRootPathResolver().resolve(
            List.of(layer),
            Map.of("anchor-a", "022", "anchor-b", "033", "outlier", "023"),
            Map.of()
        );

        assertEquals("033", resolution.pathById().get("anchor-a"));
        assertEquals("032", resolution.pathById().get("anchor-b"));
        assertEquals("023", resolution.pathById().get("outlier"));
        assertEquals("022", resolution.pathById().get("child"));
    }

    @Test
    void canonicalizesAFullWorldLayerBeforeResolvingItsChildLayer() {
        MatrixLayerTile worldAnchor = tile("world-anchor", 0, 0);
        MatrixLayer world = layer(
            worldAnchor,
            tile("world-1", 0, 1),
            tile("world-2", 0, 2),
            tile("world-3", 0, 3)
        );
        world.setCols(4);

        MatrixLayerTile child = tile("child", 0, 0);
        child.setUncles(List.of(new ToUncleRelationship(UncleDirections.SOUTH_WEST, "world-anchor")));

        TileRootPathResolver.Resolution resolution = new TileRootPathResolver().resolve(
            List.of(world, layer(child)),
            Map.of("world-anchor", "022"),
            Map.of()
        );

        assertEquals("033", resolution.pathById().get("world-anchor"));
        assertEquals("0303", resolution.pathById().get("child"));
    }

    @Test
    void combinesIndependentStrictMajoritiesForAFullWorldMatrix() {
        MatrixLayer layer = layer(
            tile("a", 0, 0),
            tile("b", 0, 1),
            tile("c", 0, 2),
            tile("d", 0, 3),
            tile("e", 1, 0),
            tile("child", 2, 1)
        );
        layer.setCols(4);

        TileRootPathResolver.Resolution resolution = new TileRootPathResolver().resolve(
            List.of(layer),
            Map.of("a", "023", "b", "022", "c", "031", "d", "020", "e", "021"),
            Map.of()
        );

        assertEquals("033", resolution.pathById().get("a"));
        assertEquals("032", resolution.pathById().get("b"));
        assertEquals("023", resolution.pathById().get("c"));
        assertEquals("022", resolution.pathById().get("d"));
        assertEquals("030", resolution.pathById().get("e"));
        assertEquals("002", resolution.pathById().get("child"));
    }

    @Test
    void canonicalizesMinorityOutliersWithTheWinningGridAnchor() {
        MatrixLayer layer = layer(
            tile("a", 0, 0),
            tile("b", 0, 1),
            tile("c", 1, 0),
            tile("outlier", 1, 1)
        );

        TileRootPathResolver.Resolution resolution = new TileRootPathResolver().resolve(
            List.of(layer),
            Map.of("a", "030", "b", "031", "c", "003", "outlier", "032"),
            Map.of()
        );

        assertEquals("002", resolution.pathById().get("outlier"));
    }

    @Test
    void directAnchorOutranksAMajorityOfRelativeUncleAnchors() {
        MatrixLayerTile direct = tile("direct", 0, 0);
        MatrixLayerTile relativeA = tile("relative-a", 0, 2);
        relativeA.setUncles(List.of(new ToUncleRelationship(UncleDirections.WEST_NORTH, "uncle")));
        MatrixLayerTile relativeB = tile("relative-b", 0, 1);
        relativeB.setUncles(List.of(new ToUncleRelationship(UncleDirections.EAST_NORTH, "uncle")));

        TileRootPathResolver.Resolution resolution = new TileRootPathResolver().resolve(
            List.of(layer(direct, relativeA, relativeB)),
            Map.of("direct", "033", "uncle", "03"),
            Map.of()
        );

        assertEquals("033", resolution.pathById().get("direct"));
        assertEquals("023", resolution.pathById().get("relative-a"));
        assertEquals("032", resolution.pathById().get("relative-b"));
        assertEquals(TileRootPathResolver.PathSource.DIRECT, resolution.sourceById().get("direct"));
        assertEquals(TileRootPathResolver.PathSource.GRID, resolution.sourceById().get("relative-a"));
        assertEquals(TileRootPathResolver.PathSource.GRID, resolution.sourceById().get("relative-b"));
    }

    @Test
    void rejectsAWellSeparatedPluralityWithoutAStrictMajority() {
        MatrixLayer layer = layer(
            tile("a", 0, 0),
            tile("b", 0, 1),
            tile("c", 0, 2),
            tile("noise-1", 1, 0),
            tile("noise-2", 1, 1),
            tile("noise-3", 1, 2),
            tile("unanchored", 2, 0)
        );

        TileRootPathResolver.Resolution resolution = new TileRootPathResolver().resolve(
            List.of(layer),
            Map.of(
                "a", "0300", "b", "0301", "c", "0310",
                "noise-1", "0330", "noise-2", "0322", "noise-3", "0031"
            ),
            Map.of()
        );

        assertFalse(resolution.pathById().containsKey("unanchored"));
        assertEquals("0300", resolution.pathById().get("a"));
        assertEquals(TileRootPathResolver.PathSource.DIRECT, resolution.sourceById().get("noise-1"));
    }

    @Test
    void rejectsTheObservedLevelSevenPluralityThatShiftsTheWholeLayer() {
        java.util.ArrayList<MatrixLayerTile> tiles = new java.util.ArrayList<>();
        java.util.LinkedHashMap<String, String> anchors = new java.util.LinkedHashMap<>();
        addAnchorVotes(tiles, anchors, 35, 48, 3);
        addAnchorVotes(tiles, anchors, 8, 49, 2);
        addAnchorVotes(tiles, anchors, 12, 48, 1);
        addAnchorVotes(tiles, anchors, 15, 48, 2);
        addAnchorVotes(tiles, anchors, 1, 47, 2);
        MatrixLayerTile unanchored = tile("unanchored-level-seven", 10, 10);
        tiles.add(unanchored);
        MatrixLayer layer = new MatrixLayer();
        layer.setSourceFolderName("matrix_level_seven_regression");
        layer.setTiles(tiles);

        TileRootPathResolver.Resolution resolution = new TileRootPathResolver().resolve(
            List.of(layer),
            anchors,
            Map.of()
        );

        assertFalse(resolution.pathById().containsKey(unanchored.getId()));
    }

    @Test
    void rejectsAWeakPluralityOfGridAnchors() {
        MatrixLayer layer = layer(
            tile("a", 0, 0),
            tile("b", 0, 1),
            tile("noise-1", 1, 0),
            tile("noise-2", 1, 1),
            tile("unanchored", 2, 0)
        );

        TileRootPathResolver.Resolution resolution = new TileRootPathResolver().resolve(
            List.of(layer),
            Map.of("a", "0300", "b", "0301", "noise-1", "0330", "noise-2", "0322"),
            Map.of()
        );

        assertFalse(resolution.pathById().containsKey("unanchored"));
        assertEquals("0330", resolution.pathById().get("noise-1"));
    }

    private static MatrixLayer layer(MatrixLayerTile... tiles) {
        MatrixLayer layer = new MatrixLayer();
        layer.setSourceFolderName("matrix_test");
        layer.setTiles(List.of(tiles));
        return layer;
    }

    private static MatrixLayerTile tile(String id, int i, int j) {
        MatrixLayerTile tile = new MatrixLayerTile();
        tile.setId(id);
        tile.setI(i);
        tile.setJ(j);
        return tile;
    }

    private static void addAnchorVotes(
        java.util.List<MatrixLayerTile> tiles,
        java.util.Map<String, String> anchors,
        int count,
        int rowOffset,
        int colOffset
    ) {
        for (int index = 0; index < count; index++) {
            int localRow = index / 16;
            int localCol = index % 16;
            String id = "anchor-" + rowOffset + "-" + colOffset + "-" + index;
            tiles.add(tile(id, localRow, localCol));
            anchors.put(id, quadPath(7, localRow + rowOffset, localCol + colOffset));
        }
    }

    private static String quadPath(int level, int row, int col) {
        StringBuilder path = new StringBuilder("0");
        for (int depth = level - 1; depth >= 0; depth--) {
            boolean south = ((row >> depth) & 1) == 1;
            boolean east = ((col >> depth) & 1) == 1;
            path.append(south ? (east ? '1' : '0') : (east ? '2' : '3'));
        }
        return path.toString();
    }

    private static void assertUncleResolution(UncleDirections direction, String expectedPath) {
        MatrixLayerTile child = tile("child", 0, 0);
        child.setUncles(List.of(new ToUncleRelationship(direction, "uncle")));

        TileRootPathResolver.Resolution resolution = new TileRootPathResolver().resolve(
            List.of(layer(child)),
            Map.of("uncle", "031"),
            Map.of()
        );

        assertEquals(expectedPath, resolution.pathById().get("child"), direction.name());
    }
}
