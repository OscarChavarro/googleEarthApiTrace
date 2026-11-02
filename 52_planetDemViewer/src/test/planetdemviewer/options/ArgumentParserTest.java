package planetdemviewer.options;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArgumentParserTest {
    @TempDir
    Path temporaryDirectory;
    private String dem;
    private String imagery;

    @BeforeEach
    void createFolders() throws Exception {
        dem = Files.createDirectory(temporaryDirectory.resolve("dem")).toString();
        imagery = Files.createDirectory(temporaryDirectory.resolve("imagery")).toString();
    }

    private static String error(String... args) {
        return assertThrows(IllegalArgumentException.class, () -> ArgumentParser.parse(args)).getMessage();
    }

    @Test
    void readsTheDemFolderFirstAndTheImageryFolderSecond() {
        CliArguments arguments = ArgumentParser.parse(new String[] {dem, imagery});
        assertEquals(dem, arguments.getDemFolder());
        assertEquals(imagery, arguments.getImageryFolder());
        assertFalse(arguments.isOffline());
    }

    @Test
    void foldersCanBeMixedWithFlags() {
        CliArguments arguments = ArgumentParser.parse(new String[] {
            "--storage-profile", "fast", dem, "--offline", imagery, "--mcp-port=42817"});
        assertEquals(dem, arguments.getDemFolder());
        assertEquals(imagery, arguments.getImageryFolder());
        assertTrue(arguments.isOffline());
        assertEquals(42817, arguments.getMcpPort());
    }

    @Test
    void bothFoldersAreRequired() {
        assertTrue(error().contains("got 0 folder"));
        assertTrue(error(dem).contains("got 1 folder"));
        assertTrue(error("--offline", "--wires").contains("got 0 folder"));
    }

    @Test
    void aThirdFolderIsRejected() {
        assertTrue(error(dem, imagery, dem).contains("got 3 folder"));
    }

    @Test
    void aMissingFolderIsNamedInTheError() {
        String missing = temporaryDirectory.resolve("missing").toString();
        assertEquals("DEM folder does not exist: " + missing, error(missing, imagery));
        assertEquals("Imagery folder does not exist: " + missing, error(dem, missing));
    }

    @Test
    void aFileIsNotAFolder() throws Exception {
        String file = Files.createFile(temporaryDirectory.resolve("file.bin")).toString();
        assertEquals("Imagery folder does not exist: " + file, error(dem, file));
    }
}
