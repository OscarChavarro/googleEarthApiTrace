package planetdemviewer.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import planetdemviewer.model.DemTile;

class PyramidalImageFolderReaderTest {
    @TempDir Path temporaryDirectory;

    @Test
    void startupIndexesOnlyRootAndDiscoversExactlyOneLevelOnDemand() throws Exception {
        byte[] tile = new byte[DemTile.BYTE_COUNT];
        Files.write(temporaryDirectory.resolve("0.bin"), tile);
        Path northWest = Files.createDirectory(temporaryDirectory.resolve("3"));
        Files.write(northWest.resolve("03.bin"), tile);
        Path grandchild = Files.createDirectory(northWest.resolve("0"));
        Files.write(grandchild.resolve("030.bin"), tile);

        var image = new PyramidalImageFolderReader().read(temporaryDirectory).orElseThrow();
        assertEquals(1, image.getTileCount());
        assertEquals(0, image.getHeight());
        assertFalse(image.getRoot().isDiscoveryComplete());
        assertFalse(image.getRoot().hasChildren());

        image.discoverChildren(image.getRoot());
        assertEquals(2, image.getTileCount());
        assertEquals(1, image.getHeight());
        assertTrue(image.getRoot().isDiscoveryComplete());
        assertNotNull(image.getRoot().getChildren()[3]);
        assertFalse(image.getRoot().getChildren()[3].isDiscoveryComplete());

        image.discoverChildren(image.getRoot().getChildren()[3]);
        assertEquals(3, image.getTileCount());
        assertEquals(2, image.getHeight());
    }

    @Test
    void rejectsWrongRootSizeWithoutScanningTree() throws Exception {
        Files.write(temporaryDirectory.resolve("0.bin"), new byte[3]);
        Files.createDirectory(temporaryDirectory.resolve("3"));
        assertTrue(new PyramidalImageFolderReader().read(temporaryDirectory).isEmpty());
    }
}
