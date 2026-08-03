package planetdemviewer.io;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import planetdemviewer.config.StorageProfile;
import planetdemviewer.model.DemTile;
import planetdemviewer.model.QuadtreeNode;

class TileTreeDiscoveryServiceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void continuesFromAPrefetchedNodeWhenItLaterBecomesVisible() throws Exception {
        byte[] tile = new byte[DemTile.BYTE_COUNT];
        Files.write(temporaryDirectory.resolve("0.bin"), tile);
        Path level1 = Files.createDirectory(temporaryDirectory.resolve("3"));
        Files.write(level1.resolve("03.bin"), tile);
        Path level2 = Files.createDirectory(level1.resolve("0"));
        Files.write(level2.resolve("030.bin"), tile);
        Path level3 = Files.createDirectory(level2.resolve("2"));
        Files.write(level3.resolve("0302.bin"), tile);

        var image = new PyramidalImageFolderReader().read(temporaryDirectory).orElseThrow();
        TileTreeDiscoveryService service = new TileTreeDiscoveryService(StorageProfile.SLOW);
        try {
            CountDownLatch initialLookAhead = new CountDownLatch(2);
            service.setOnTreeChanged(initialLookAhead::countDown);
            service.requestVisible(image, image.getRoot());
            assertTrue(initialLookAhead.await(2, TimeUnit.SECONDS));

            QuadtreeNode prefetched = image.getRoot().getChildren()[3];
            assertNotNull(prefetched);
            assertTrue(prefetched.isDiscoveryComplete());
            QuadtreeNode visibleFrontier = prefetched.getChildren()[0];
            assertNotNull(visibleFrontier);

            CountDownLatch nextVisibleLevel = new CountDownLatch(1);
            service.setOnTreeChanged(nextVisibleLevel::countDown);
            service.requestVisible(image, prefetched);
            assertTrue(nextVisibleLevel.await(2, TimeUnit.SECONDS));
            assertTrue(visibleFrontier.isDiscoveryComplete());
            assertNotNull(visibleFrontier.getChildren()[2]);
        }
        finally {
            service.shutdown();
        }
    }
}
