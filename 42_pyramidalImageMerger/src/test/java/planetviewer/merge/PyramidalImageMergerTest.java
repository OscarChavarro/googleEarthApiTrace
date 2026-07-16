package planetviewer.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import planetviewer.io.PyramidalImageFolderReader;
import planetviewer.model.PyramidalImage;

class PyramidalImageMergerTest {
    @TempDir
    Path temporaryFolder;

    @Test
    void writesNewTilesUsingPerDigitLayout() throws IOException {
        Path destinationRoot = temporaryFolder.resolve("destination");
        Path deltaRoot = temporaryFolder.resolve("delta");
        createPerDigitTile(destinationRoot, "0");
        createPerDigitTile(destinationRoot, "02");
        createPerDigitTile(deltaRoot, "0");
        createPerDigitTile(deltaRoot, "02010020");

        PyramidalImage destination = read(destinationRoot);
        PyramidalImage delta = read(deltaRoot);
        PyramidalImageMerger merger = new PyramidalImageMerger();

        PyramidalImageMerger.MergeResult result = merger.mergeTiles(destination, delta, Set.of());
        PyramidalImage refreshed = read(destinationRoot);

        assertEquals(1, result.copiedTiles());
        assertTrue(Files.isRegularFile(destinationRoot.resolve("2/0/1/0/0/2/0/02010020.png")));
        assertFalse(Files.exists(destinationRoot.resolve("02/020/0201")));
        assertTrue(merger.findMissingDeltaTileIds(refreshed, delta).isEmpty());
    }

    @Test
    void prefersPerDigitLayoutForAnAlreadyHybridDestination() throws IOException {
        Path destinationRoot = temporaryFolder.resolve("hybrid-destination");
        Path deltaRoot = temporaryFolder.resolve("hybrid-delta");
        createPerDigitTile(destinationRoot, "0");
        createPerDigitTile(destinationRoot, "02");
        createLegacyTile(destinationRoot, "03");
        createPerDigitTile(deltaRoot, "0");
        createPerDigitTile(deltaRoot, "0301");

        PyramidalImageMerger merger = new PyramidalImageMerger();
        merger.mergeTiles(read(destinationRoot), read(deltaRoot), Set.of());
        PyramidalImage refreshed = read(destinationRoot);

        assertTrue(Files.isRegularFile(destinationRoot.resolve("3/0/1/0301.png")));
        assertTrue(merger.findMissingDeltaTileIds(refreshed, read(deltaRoot)).isEmpty());
    }

    @Test
    void preservesLegacyLayoutForALegacyOnlyDestination() throws IOException {
        Path destinationRoot = temporaryFolder.resolve("legacy-destination");
        Path deltaRoot = temporaryFolder.resolve("legacy-delta");
        createPerDigitTile(destinationRoot, "0");
        createLegacyTile(destinationRoot, "02");
        createPerDigitTile(deltaRoot, "0");
        createPerDigitTile(deltaRoot, "0201");

        PyramidalImageMerger merger = new PyramidalImageMerger();
        merger.mergeTiles(read(destinationRoot), read(deltaRoot), Set.of());
        PyramidalImage refreshed = read(destinationRoot);

        assertTrue(Files.isRegularFile(destinationRoot.resolve("02/020/0201/0201.png")));
        assertFalse(Files.exists(destinationRoot.resolve("2")));
        assertTrue(merger.findMissingDeltaTileIds(refreshed, read(deltaRoot)).isEmpty());
    }

    @Test
    void postconditionReportsTilesNotVisibleInDestination() throws IOException {
        Path destinationRoot = temporaryFolder.resolve("incomplete-destination");
        Path deltaRoot = temporaryFolder.resolve("incomplete-delta");
        createPerDigitTile(destinationRoot, "0");
        createPerDigitTile(deltaRoot, "0");
        createPerDigitTile(deltaRoot, "0201");

        assertEquals(
            java.util.List.of("0201"),
            new PyramidalImageMerger().findMissingDeltaTileIds(read(destinationRoot), read(deltaRoot))
        );
    }

    private PyramidalImage read(Path root) {
        return new PyramidalImageFolderReader().read(root).orElseThrow();
    }

    private void createPerDigitTile(Path root, String id) throws IOException {
        Path directory = root;
        for (int index = 1; index < id.length(); index++) {
            directory = directory.resolve(String.valueOf(id.charAt(index)));
        }
        Files.createDirectories(directory);
        Files.createFile(directory.resolve(id + ".png"));
    }

    private void createLegacyTile(Path root, String id) throws IOException {
        Path directory = root;
        for (int index = 1; index < id.length(); index++) {
            directory = directory.resolve(id.substring(0, index + 1));
        }
        Files.createDirectories(directory);
        Files.createFile(directory.resolve(id + ".png"));
    }
}
