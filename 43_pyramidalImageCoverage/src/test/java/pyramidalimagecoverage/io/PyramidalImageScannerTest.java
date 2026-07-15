package pyramidalimagecoverage.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
