package pyramidalimageexporter.processing.geography;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void resolvesL9ThroughL14WithoutFollowingAnUnrelatedL22Reference(
        @org.junit.jupiter.api.io.TempDir java.nio.file.Path frames
    ) throws java.io.IOException {
        List<MatrixLayer> layers = new ArrayList<>();
        Map<String, String> paths = new java.util.HashMap<>();
        for (int i = 0; i < 6; i++) {
            String id = String.format("%05d_1", 100 + i);
            MatrixLayer layer = layerWithTiles("matrix_" + i, tile(id, 0, 0));
            layer.setHierarchyLevel(i);
            layer.setFrameId(100 + i);
            layers.add(layer);
            java.nio.file.Path frame = frames.resolve(String.format("%05d", 100 + i));
            java.nio.file.Files.createDirectories(frame);
            java.nio.file.Files.writeString(frame.resolve("frame.json"),
                "{\"camera\":{\"googleCamera\":{\"frontX\":0,\"frontY\":0,\"frontZ\":-1}},\"tiles\":[]}");
            if (i < 4) {
                paths.put(id, FrameCameraGeoAnchorResolver.quadPath(0, 0, 9 + i));
            }
        }
        var anchors = new FrameCameraGeoAnchorResolver().resolve(layers, frames,
            Map.of("unrelated.png", "0" + "3".repeat(22)),
            new TileRootPathResolver.Resolution(paths, Set.of(), Map.of()));
        assertEquals(FrameCameraGeoAnchorResolver.quadPath(0, 0, 13), anchors.fullPathByTileId().get("00104_1"));
        assertEquals(FrameCameraGeoAnchorResolver.quadPath(0, 0, 14), anchors.fullPathByTileId().get("00105_1"));
        var unanchored = new FrameCameraGeoAnchorResolver().resolve(layers, frames,
            Map.of("unrelated.png", "0" + "3".repeat(22)),
            new TileRootPathResolver.Resolution(Map.of(), Set.of(), Map.of()));
        assertTrue(unanchored.fullPathByTileId().isEmpty());
    }

    @Test
    void infersSessionScaleWithoutUsingUnrelatedDeepReferenceIslands() {
        List<MatrixLayer> layers = new ArrayList<>();
        Map<String, String> paths = new java.util.HashMap<>();
        for (int i = 0; i < 6; i++) {
            MatrixLayer layer = layerWithTiles("matrix_" + i, tile("09000_" + i, 0, 0));
            layer.setHierarchyLevel(i);
            layers.add(layer);
            if (i < 4) {
                paths.put("09000_" + i, FrameCameraGeoAnchorResolver.quadPathForCell(9 + i, 0, 0));
            }
        }
        assertEquals(9, FrameCameraGeoAnchorResolver.hierarchyBaseLevel(layers,
            new TileRootPathResolver.Resolution(paths, Set.of(), Map.of())));
    }

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

    @Test
    void requiresAnExactMajorityBeforeCameraEvidenceCanOverrideStructure() {
        assertTrue(FrameCameraGeoAnchorResolver.hasStrongExactCameraEvidence(72, 72, 68));
        assertFalse(FrameCameraGeoAnchorResolver.hasStrongExactCameraEvidence(72, 72, 2));
        assertFalse(FrameCameraGeoAnchorResolver.hasStrongExactCameraEvidence(40, 72, 35));
    }

    @Test
    void onlyLetsExactCameraEvidenceOverrideStructureAtDetailedLevels() {
        assertFalse(FrameCameraGeoAnchorResolver.canOverrideStructuralPlacement(5, 72, 72, 68));
        assertFalse(FrameCameraGeoAnchorResolver.canOverrideStructuralPlacement(10, 72, 72, 68));
        assertTrue(FrameCameraGeoAnchorResolver.canOverrideStructuralPlacement(11, 72, 72, 68));
        assertTrue(FrameCameraGeoAnchorResolver.canOverrideStructuralPlacement(17, 72, 72, 68));
    }

    @Test
    void preservesTheResolvedLevelForADisconnectedDeepLayer() {
        MatrixLayer layer = layerWithTiles(
            "matrix_8",
            tile("09000_100", 0, 0),
            tile("09000_101", 0, 1)
        );
        layer.setHierarchyLevel(1);
        TileRootPathResolver.Resolution structure = new TileRootPathResolver.Resolution(
            Map.of(
                "09000_100", FrameCameraGeoAnchorResolver.quadPathForCell(17, 50900, 64100),
                "09000_101", FrameCameraGeoAnchorResolver.quadPathForCell(17, 50900, 64101)
            ),
            Set.of(),
            Map.of()
        );

        assertEquals(17, FrameCameraGeoAnchorResolver.consistentlyResolvedLevel(layer, structure));
    }

    @Test
    void rejectsAMixedResolvedLevelAsAGeographicSeed() {
        MatrixLayer layer = layerWithTiles(
            "matrix_2",
            tile("09000_100", 0, 0),
            tile("09000_101", 0, 1)
        );
        TileRootPathResolver.Resolution structure = new TileRootPathResolver.Resolution(
            Map.of(
                "09000_100", FrameCameraGeoAnchorResolver.quadPathForCell(12, 1000, 2000),
                "09000_101", FrameCameraGeoAnchorResolver.quadPathForCell(13, 2000, 4001)
            ),
            Set.of(),
            Map.of()
        );

        assertNull(FrameCameraGeoAnchorResolver.consistentlyResolvedLevel(layer, structure));
    }
}
