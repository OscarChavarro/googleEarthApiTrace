package pyramidalimagecoverage.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pyramidalimagecoverage.model.PyramidCatalog;

class PyramidalImageScannerTest {
    @TempDir
    Path temporaryFolder;

    @Test
    void refreshDiscoversNewLevelTenPngsForTheExistingCatalog() throws IOException {
        Files.createFile(temporaryFolder.resolve("0.png"));
        PyramidalImageScanner scanner = new PyramidalImageScanner();
        PyramidCatalog catalog = scanner.scanRoot(temporaryFolder);
        Path path = temporaryFolder;
        for (int digit = 0; digit < 10; digit++) path = path.resolve("0");
        Files.createDirectories(path);
        Files.createFile(path.resolve("00000000000.png"));
        int[] notifications = {0};
        scanner.scanLevel(catalog, 10, () -> notifications[0]++);
        assertNotNull(catalog.tileAt(10, 0, 0));
        assertEquals(1, notifications[0]);
    }

    @Test
    void rootScanDoesNotTraverseDescendantFolders() throws IOException {
        Path root = temporaryFolder.resolve("lazy-layout");
        Files.createDirectories(root.resolve("0/2"));
        Files.createFile(root.resolve("0.png"));
        Files.createFile(root.resolve("0/00.png"));
        Files.createFile(root.resolve("0/2/002.png"));

        PyramidalImageScanner scanner = new PyramidalImageScanner();
        PyramidCatalog catalog = scanner.scanRoot(root);

        assertEquals(1, catalog.tileCount());
        assertNotNull(catalog.tileAt(0, 0, 0));
        assertNull(catalog.tileAt(1, 0, 0));

        scanner.scanRemaining(catalog, () -> { });

        assertEquals(3, catalog.tileCount());
        assertNotNull(catalog.tileAt(1, 0, 0));
        assertNotNull(catalog.tileAt(2, 1, 1));
    }

    @Test
    void scansNewPerDigitFolderLayout() throws IOException {
        Path root = temporaryFolder.resolve("new-layout");
        Files.createDirectories(root.resolve("3/0/3"));
        Files.createFile(root.resolve("0.png"));
        Files.createFile(root.resolve("3/03.png"));
        Files.createFile(root.resolve("3/0/030.png"));
        Files.createFile(root.resolve("3/0/3/0303.png"));

        PyramidCatalog catalog = new PyramidalImageScanner().scan(root);

        assertEquals(4, catalog.tileCount());
        assertNotNull(catalog.tileAt(0, 0, 0));
        assertNotNull(catalog.tileAt(1, 0, 1));
        assertNotNull(catalog.tileAt(2, 0, 2));
        assertNotNull(catalog.tileAt(3, 0, 5));
    }

    @Test
    void scansDeepTilesWhenIntermediateTileImagesAreMissing() throws IOException {
        Path root = temporaryFolder.resolve("sparse-layout");
        Files.createDirectories(root.resolve("3/0/3"));
        Files.createFile(root.resolve("0.png"));
        Files.createFile(root.resolve("3/0/3/0303.png"));

        PyramidCatalog catalog = new PyramidalImageScanner().scan(root);

        assertEquals(2, catalog.tileCount());
        assertNull(catalog.tileAt(1, 0, 1));
        assertNull(catalog.tileAt(2, 0, 2));
        assertNotNull(catalog.tileAt(3, 0, 5));
    }

    @Test
    void stillScansLegacyCumulativeFolderLayout() throws IOException {
        Path root = temporaryFolder.resolve("legacy-layout");
        Files.createDirectories(root.resolve("03/030/0303"));
        Files.createFile(root.resolve("0.png"));
        Files.createFile(root.resolve("03/03.png"));
        Files.createFile(root.resolve("03/030/030.png"));
        Files.createFile(root.resolve("03/030/0303/0303.png"));

        PyramidCatalog catalog = new PyramidalImageScanner().scan(root);

        assertEquals(4, catalog.tileCount());
        assertNotNull(catalog.tileAt(0, 0, 0));
        assertNotNull(catalog.tileAt(1, 0, 1));
        assertNotNull(catalog.tileAt(2, 0, 2));
        assertNotNull(catalog.tileAt(3, 0, 5));
    }

    @Test
    void writesOneSparseExistenceCacheFilePerPopulatedLevel() throws IOException {
        Path root = temporaryFolder.resolve("cached-pyramid");
        Path cache = temporaryFolder.resolve("ramdisk");
        Files.createDirectories(root.resolve("3/0"));
        Files.createFile(root.resolve("0.png"));
        Files.createFile(root.resolve("3/03.png"));
        Files.createFile(root.resolve("3/0/030.png"));

        new PyramidalImageScanner(root, cache).scan(root);

        assertTrue(Files.isRegularFile(cache.resolve(cacheFile(0))));
        assertTrue(Files.isRegularFile(cache.resolve(cacheFile(1))));
        assertTrue(Files.isRegularFile(cache.resolve(cacheFile(2))));
        assertFalse(Files.exists(cache.resolve(cacheFile(3))));
    }

    @Test
    void trustsCachedPositiveFlagsWithoutRecheckingDescendantImages() throws IOException {
        Path root = temporaryFolder.resolve("monotonic-pyramid");
        Path cache = temporaryFolder.resolve("ramdisk");
        Files.createDirectories(root.resolve("3"));
        Files.createFile(root.resolve("0.png"));
        Path descendant = Files.createFile(root.resolve("3/03.png"));
        PyramidalImageScanner scanner = new PyramidalImageScanner(root, cache);
        scanner.scan(root);
        Files.delete(descendant); // Simulates an impossible deletion in the production dataset.

        PyramidCatalog loadedFromFlags = scanner.scanRoot(root);

        assertEquals(2, loadedFromFlags.tileCount());
        assertNotNull(loadedFromFlags.tileAt(1, 0, 1));
    }

    @Test
    void addsNewTilesToAnExistingCache() throws IOException {
        Path root = temporaryFolder.resolve("growing-pyramid");
        Path cache = temporaryFolder.resolve("ramdisk");
        Files.createDirectories(root.resolve("3/0"));
        Files.createFile(root.resolve("0.png"));
        Files.createFile(root.resolve("3/03.png"));
        PyramidalImageScanner scanner = new PyramidalImageScanner(root, cache);
        scanner.scan(root);

        Files.createFile(root.resolve("3/0/030.png"));
        PyramidCatalog updated = scanner.scan(root);
        PyramidCatalog loadedFromFlags = scanner.scanRoot(root);

        assertNotNull(updated.tileAt(2, 0, 2));
        assertNotNull(loadedFromFlags.tileAt(2, 0, 2));
    }

    @Test
    void neverUsesTheCacheForAnotherPyramid() throws IOException {
        Path eligible = temporaryFolder.resolve("eligible");
        Path other = temporaryFolder.resolve("other");
        Path cache = temporaryFolder.resolve("ramdisk");
        Files.createDirectories(eligible.resolve("3"));
        Files.createFile(eligible.resolve("0.png"));
        Files.createFile(eligible.resolve("3/03.png"));
        PyramidalImageScanner scanner = new PyramidalImageScanner(eligible, cache);
        scanner.scan(eligible);
        Files.createDirectories(other);
        Files.createFile(other.resolve("0.png"));

        PyramidCatalog otherCatalog = scanner.scanRoot(other);

        assertEquals(1, otherCatalog.tileCount());
        assertNull(otherCatalog.tileAt(1, 0, 1));
    }

    private static String cacheFile(int level) {
        return TileExistenceCache.FILE_PREFIX + level + ".flags";
    }
}
