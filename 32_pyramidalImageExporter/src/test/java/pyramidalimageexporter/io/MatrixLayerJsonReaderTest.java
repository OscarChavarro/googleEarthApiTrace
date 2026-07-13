package pyramidalimageexporter.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.processing.uncles.UncleDirections;

final class MatrixLayerJsonReaderTest {
    @TempDir
    Path tempDir;

    @Test
    void importsVersionTwoHierarchyAndRestoresRelationsOnTiles() throws Exception {
        Path layerDirectory = Files.createDirectory(tempDir.resolve("matrix_1"));
        Files.writeString(layerDirectory.resolve("matrixLayer.json"), """
            {
              "contractVersion": 2,
              "hierarchyLevel": 1,
              "parentMatrixIndex": 0,
              "frameId": 20,
              "hierarchyUnclesByTileId": {"00020_1": ["00010_1"]},
              "hierarchyRelationshipsByTileId": {
                "00020_1": [{"direction": "NORTH_EAST", "uncleContentId": "00010_1"}]
              },
              "matrices": [{
                "frameId": 20,
                "rows": 1,
                "cols": 1,
                "tiles": [{"id": "00020_1", "i": 0, "j": 0, "textureFile": "/tmp/child.png", "uncles": []}]
              }]
            }
            """);

        List<MatrixLayer> layers = new MatrixLayerJsonReader().readAllFromInput(tempDir);

        assertEquals(1, layers.size());
        MatrixLayer layer = layers.get(0);
        assertEquals(2, layer.getContractVersion());
        assertEquals(1, layer.getHierarchyLevel());
        assertEquals(0, layer.getParentMatrixIndex());
        assertEquals(1, layer.getTiles().get(0).getUncles().size());
        assertEquals(UncleDirections.NORTH_EAST, layer.getTiles().get(0).getUncles().get(0).direction());
        assertEquals("00010_1", layer.getTiles().get(0).getUncles().get(0).uncleContentId());
    }
}
