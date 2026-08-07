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

    @Test
    void aliasesCopiedExternalUncleTexturesToLoadedTilesByContent() throws IOException {
        Path outputDir = Files.createDirectories(tempDir.resolve("output"));
        Path originalTexture = Files.createDirectories(outputDir.resolve("00001"))
            .resolve("256x256_1.png");
        Files.write(originalTexture, new byte[]{1, 2, 3, 4});
        Path copiedUncleTexture = Files.write(tempDir.resolve("uncle-copy.png"), new byte[]{1, 2, 3, 4});

        MatrixLayerTile loaded = new MatrixLayerTile();
        loaded.setId("00001_1");
        loaded.setTextureFile(tempDir.resolve("matrix_0").resolve("00001_1.png").toString());

        MatrixLayerTile child = new MatrixLayerTile();
        child.setId("child");
        child.setUncles(List.of(new ToUncleRelationship(UncleDirections.SOUTH_WEST, "external-parent")));

        MatrixLayer layer = new MatrixLayer();
        layer.setTiles(List.of(loaded, child));
        layer.setExternalUncleTextureFilesById(Map.of("external-parent", copiedUncleTexture.toString()));

        ExternalUncleBridgeBuilder.Bridge bridge = new ExternalUncleBridgeBuilder().build(
            List.of(layer),
            Map.of(),
            outputDir
        );

        assertEquals("00001_1", bridge.aliasById().get("external-parent"));
    }
}
