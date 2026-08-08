package pyramidalimageexporter.processing.geography;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.MatrixLayerTile;
import pyramidalimageexporter.processing.uncles.TileRootPathResolver;

final class FrameCameraGeoAnchorResolverTest {
    @Test
    void mapsIbizaCameraToTheSquareGeographicQuadtree() {
        assertEquals(
            "02003303203323",
            FrameCameraGeoAnchorResolver.quadPath(1.512, 38.650, 13)
        );
    }

    @Test
    void usesTheMiddleHalfOfTheSquareForEarthLatitudes() {
        assertEquals("020", FrameCameraGeoAnchorResolver.quadPath(0.0, 0.0, 2));
        assertEquals("003", FrameCameraGeoAnchorResolver.quadPath(-180.0, -90.0, 2));
        assertEquals("022", FrameCameraGeoAnchorResolver.quadPath(180.0, 90.0, 2));
    }

    @Test
    void rejectsACameraOffsetThatContradictsTheStructuralPlacement() {
        // matrix_0 of the Iberia session: TopLevelVisualAnchorResolver placed it
        // at level 5 [10, 9] from 14/14 confident visual probes, while the camera
        // votes produced [6, 2]. Anchoring on the camera offset overwrote correct
        // absolute paths and made program 42 report conflicts at levels 5/6/8/12/13.
        MatrixLayer layer = layerWithTiles("matrix_0", tile("00125_110", 0, 0), tile("00129_126", 0, 1));
        TileRootPathResolver.Resolution structure = new TileRootPathResolver.Resolution(
            Map.of(
                "00125_110", FrameCameraGeoAnchorResolver.quadPathForCell(5, 10, 9),
                "00129_126", FrameCameraGeoAnchorResolver.quadPathForCell(5, 10, 10)
            ),
            Set.of(),
            Map.of()
        );

        assertTrue(FrameCameraGeoAnchorResolver.contradictsStructuralPlacement(
            layer, 5, new FrameCameraGeoAnchorResolver.GridOffset(6, 2), structure
        ));
        assertFalse(FrameCameraGeoAnchorResolver.contradictsStructuralPlacement(
            layer, 5, new FrameCameraGeoAnchorResolver.GridOffset(10, 9), structure
        ));
    }

    @Test
    void keepsCameraAnchoringWhenStructureResolvedNothing() {
        MatrixLayer layer = layerWithTiles("matrix_7", tile("01199_1409", 0, 0));
        TileRootPathResolver.Resolution empty =
            new TileRootPathResolver.Resolution(Map.of(), Set.of(), Map.of());

        assertFalse(FrameCameraGeoAnchorResolver.contradictsStructuralPlacement(
            layer, 12, new FrameCameraGeoAnchorResolver.GridOffset(1594, 1938), empty
        ));
    }

    private static MatrixLayer layerWithTiles(String name, MatrixLayerTile... tiles) {
        MatrixLayer layer = new MatrixLayer();
        layer.setSourceFolderName(name);
        layer.setTiles(new ArrayList<>(List.of(tiles)));
        return layer;
    }

    private static MatrixLayerTile tile(String id, int i, int j) {
        MatrixLayerTile tile = new MatrixLayerTile();
        tile.setId(id);
        tile.setI(i);
        tile.setJ(j);
        return tile;
    }

    @Test
    void rejectsWeakApproximateEvidenceForCoarseLayers() {
        assertFalse(FrameCameraGeoAnchorResolver.hasSufficientExactCameraEvidence(5, 2));
        assertTrue(FrameCameraGeoAnchorResolver.hasSufficientExactCameraEvidence(5, 3));
        assertTrue(FrameCameraGeoAnchorResolver.hasSufficientExactCameraEvidence(13, 0));
    }
}
