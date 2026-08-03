package pyramidalimageexporter.processing.uncles;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.MatrixLayerTile;

final class ExternalUncleBridgeBuilderTest {
    @TempDir
    Path tempDir;

    @Test
    void anchorsAnExternalUncleFromAReferencePyramidContentMatch() throws IOException {
        Path reference = Files.write(tempDir.resolve("reference.png"), new byte[]{7, 8, 9});
        Path uncleCopy = Files.write(tempDir.resolve("uncle.png"), new byte[]{7, 8, 9});
        MatrixLayerTile child = new MatrixLayerTile();
        child.setId("child");
        child.setUncles(List.of(new ToUncleRelationship(UncleDirections.SOUTH_WEST, "external-parent")));
        MatrixLayer layer = new MatrixLayer();
        layer.setTiles(List.of(child));
        layer.setExternalUncleTextureFilesById(Map.of("external-parent", uncleCopy.toString()));

        ExternalUncleBridgeBuilder.Bridge bridge = new ExternalUncleBridgeBuilder().build(
            List.of(layer),
            Map.of(reference.toString(), "031122020330"),
            null
        );

        assertEquals("031122020330", bridge.fullPathByExternalId().get("external-parent"));
    }
}
