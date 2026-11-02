package planetdemviewer.io.imagery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import planetdemviewer.spatial.TileAddress;
import vsdk.toolkit.io.image.ImagePersistence;
import vsdk.toolkit.media.RGBImageUncompressed;

class ImageryTileResolverTest {
    @TempDir Path temporaryDirectory;

    @Test
    void resolvesPngBelowADirectVolumeLink() throws Exception {
        Path volume = Files.createDirectories(temporaryDirectory.resolve("GE_0").resolve("2"));
        Path expected = Files.write(volume.resolve("002.png"), new byte[] {1});
        Path pyramid = Files.createDirectory(temporaryDirectory.resolve("toplevel"));
        Files.createSymbolicLink(pyramid.resolve("0"), temporaryDirectory.resolve("GE_0"));

        var result = new ImageryTileResolver(pyramid).resolve(TileAddress.fabdem("002"));

        assertTrue(result.isPresent());
        assertEquals(expected.toRealPath(), result.orElseThrow());
    }

    private Path page(Path directory, String quadkey) throws Exception {
        return Files.write(Files.createDirectories(directory).resolve(quadkey + ".png"), new byte[] {1});
    }

    private ImageryDeletionReport deleteSubtree(Path pyramid, String quadkey) {
        ImageryTileLoader loader = new ImageryTileLoader(pyramid);
        try {
            return loader.deleteSubtree(TileAddress.fabdem(quadkey)).join();
        }
        finally {
            loader.shutdown();
        }
    }

    @Test
    void deletesTheTileAndItsSublevelsButNotItsSiblingsOrParent() throws Exception {
        Path pyramid = temporaryDirectory.resolve("toplevel");
        Path parent = page(pyramid, "0");
        Path sibling = page(pyramid.resolve("3"), "03");
        Path siblingChild = page(pyramid.resolve("3/0"), "030");
        Path tileDirectory = pyramid.resolve("2");
        page(tileDirectory, "02");
        page(tileDirectory.resolve("0"), "020");
        page(tileDirectory.resolve("0/3"), "0203");
        page(tileDirectory.resolve("1"), "021");

        ImageryDeletionReport report = deleteSubtree(pyramid, "02");

        assertEquals(new ImageryDeletionReport(4, 4, 0, 0), report);
        assertTrue(Files.notExists(tileDirectory));
        assertTrue(Files.exists(parent));
        assertTrue(Files.exists(sibling));
        assertTrue(Files.exists(siblingChild));
    }

    @Test
    void deletesSublevelsEvenWhenTheTilesOwnPngIsAlreadyGone() throws Exception {
        Path pyramid = temporaryDirectory.resolve("toplevel");
        page(pyramid.resolve("2/0"), "020");

        assertEquals(new ImageryDeletionReport(1, 2, 0, 0), deleteSubtree(pyramid, "02"));
        assertTrue(Files.notExists(pyramid.resolve("2")));
    }

    @Test
    void keepsNonPngFilesAndTheFoldersHoldingThem() throws Exception {
        Path pyramid = temporaryDirectory.resolve("toplevel");
        page(pyramid.resolve("2"), "02");
        Path dem = Files.write(Files.createDirectories(pyramid.resolve("2/0")).resolve("020.bin"), new byte[] {2});
        page(pyramid.resolve("2/0"), "020");

        assertEquals(new ImageryDeletionReport(2, 0, 1, 0), deleteSubtree(pyramid, "02"));
        assertTrue(Files.exists(dem));
    }

    @Test
    void deletingTheRootKeepsThePyramidFolderAndItsCache() throws Exception {
        Path pyramid = temporaryDirectory.resolve("toplevel");
        page(pyramid, "0");
        page(pyramid.resolve("0"), "00");
        Path cache = Files.write(pyramid.resolve("cache.bin"), new byte[] {2});

        assertEquals(new ImageryDeletionReport(2, 1, 0, 0), deleteSubtree(pyramid, "0"));
        assertTrue(Files.exists(cache));
    }

    @Test
    void prunesThroughAVolumeLinkButKeepsTheLinkAndTheVolumeRoot() throws Exception {
        Path volume = temporaryDirectory.resolve("GE_2");
        page(volume, "02");
        Path deep = page(volume.resolve("0"), "020");
        Path pyramid = Files.createDirectory(temporaryDirectory.resolve("toplevel"));
        Path link = Files.createSymbolicLink(pyramid.resolve("2"), volume);

        assertEquals(new ImageryDeletionReport(2, 1, 0, 0), deleteSubtree(pyramid, "02"));
        assertTrue(Files.notExists(deep));
        assertTrue(Files.exists(volume));
        assertTrue(Files.isSymbolicLink(link));

        page(volume.resolve("1"), "021");
        assertEquals(new ImageryDeletionReport(1, 1, 0, 0), deleteSubtree(pyramid, "0"));
        assertTrue(Files.isSymbolicLink(link));
    }

    @Test
    void doesNotDescendIntoLinksBelowTheFirstLevel() throws Exception {
        Path outside = page(temporaryDirectory.resolve("outside"), "keep");
        Path pyramid = temporaryDirectory.resolve("toplevel");
        page(pyramid.resolve("2/0"), "020");
        Files.createSymbolicLink(pyramid.resolve("2/0/1"), outside.getParent());

        assertEquals(new ImageryDeletionReport(1, 0, 1, 0), deleteSubtree(pyramid, "02"));
        assertTrue(Files.exists(outside));
    }

    @Test
    void aDeletedSubtreeIsNoLongerServedButItsSiblingsAre() throws Exception {
        Path pyramid = temporaryDirectory.resolve("toplevel");
        RGBImageUncompressed image = new RGBImageUncompressed();
        image.init(1, 1);
        Files.createDirectories(pyramid.resolve("2/0"));
        Files.createDirectories(pyramid.resolve("3"));
        ImagePersistence.exportPNG(pyramid.resolve("2/0/020.png").toFile(), image);
        ImagePersistence.exportPNG(pyramid.resolve("3/03.png").toFile(), image);
        ImageryTileLoader loader = new ImageryTileLoader(pyramid);
        TileAddress child = TileAddress.fabdem("020");
        TileAddress sibling = TileAddress.fabdem("03");
        CountDownLatch decoded = new CountDownLatch(2);
        loader.setOnReady(decoded::countDown);
        loader.request(child);
        loader.request(sibling);
        assertTrue(decoded.await(10, TimeUnit.SECONDS));

        loader.deleteSubtree(TileAddress.fabdem("02")).join();
        loader.request(child);

        assertNull(loader.takeReady(child));
        assertNotNull(loader.takeReady(sibling));
        loader.shutdown();
    }
}
