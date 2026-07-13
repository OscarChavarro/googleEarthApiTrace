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

        assertEquals("030", resolution.pathById().get("child-a"));
        assertEquals("031", resolution.pathById().get("child-b"));
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
}
