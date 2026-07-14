package pyramidalimageexporter.processing.uncles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.MatrixLayerTile;

final class TileRootPathResolverTest {
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
    void fullWorldWidthForcesZeroLongitudeOffset() {
        MatrixLayerTile anchored = tile("child-a", 0, 0);
        anchored.setUncles(List.of(new ToUncleRelationship(UncleDirections.WEST_NORTH, "uncle")));
        MatrixLayer layer = layer(anchored, tile("child-b", 0, 3));
        layer.setCols(4);

        TileRootPathResolver.Resolution resolution = new TileRootPathResolver().resolve(
            List.of(layer),
            Map.of("uncle", "03"),
            Map.of()
        );

        assertEquals("033", resolution.pathById().get("child-a"));
        assertEquals("022", resolution.pathById().get("child-b"));
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
