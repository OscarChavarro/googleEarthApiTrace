package pyramidalimageexporter.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PyramidalImageReferenceCatalogTest {
    @TempDir
    Path tempDir;

    @Test
    void cataloguesOnlyTheDeepestValidQuadtreeLevel() throws IOException {
        Files.write(tempDir.resolve("0.png"), new byte[]{0});
        writeTile("01");
        Path deepestA = writeTile("012");
        Path deepestB = writeTile("013");
        Files.write(tempDir.resolve("unrelated.png"), new byte[]{0});

        Map<String, String> catalog = new PyramidalImageReferenceCatalog().readDeepestLevel(tempDir);

        assertEquals(Map.of(
            deepestA.toAbsolutePath().normalize().toString(), "012",
            deepestB.toAbsolutePath().normalize().toString(), "013"
        ), catalog);
    }

    @Test
    void cataloguesTheImmediateParentLevelNeededToAnchorDisconnectedTiles() throws IOException {
        Files.write(tempDir.resolve("0.png"), new byte[]{0});
        Path parent = writeTile("01");
        Path deepestA = writeTile("012");
        Path deepestB = writeTile("013");

        Map<String, String> catalog = new PyramidalImageReferenceCatalog()
            .readDeepestLevels(tempDir, 2);

        assertEquals(Map.of(
            parent.toAbsolutePath().normalize().toString(), "01",
            deepestA.toAbsolutePath().normalize().toString(), "012",
            deepestB.toAbsolutePath().normalize().toString(), "013"
        ), catalog);
    }

    @Test
    void cataloguesTopScaffoldAndDeepPlacementLevels() throws IOException {
        Path root = tempDir.resolve("0.png");
        Files.write(root, new byte[]{0});
        Path top = writeTile("01");
        writeTile("012");
        Path deepParent = writeTile("0123");
        Path deepest = writeTile("01230");

        Map<String, String> catalog = new PyramidalImageReferenceCatalog()
            .readTopAndDeepestLevels(tempDir, 1, 2);

        assertEquals(Map.of(
            root.toAbsolutePath().normalize().toString(), "0",
            top.toAbsolutePath().normalize().toString(), "01",
            deepParent.toAbsolutePath().normalize().toString(), "0123",
            deepest.toAbsolutePath().normalize().toString(), "01230"
        ), catalog);
    }

    private Path writeTile(String quadPath) throws IOException {
        Path directory = tempDir;
        for (int index = 1; index < quadPath.length(); index++) {
            directory = directory.resolve(String.valueOf(quadPath.charAt(index)));
        }
        Files.createDirectories(directory);
        return Files.write(directory.resolve(quadPath + ".png"), new byte[]{1});
    }
}
