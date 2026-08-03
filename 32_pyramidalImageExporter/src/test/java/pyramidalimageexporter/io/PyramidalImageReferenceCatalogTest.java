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

    private Path writeTile(String quadPath) throws IOException {
        Path directory = tempDir;
        for (int index = 1; index < quadPath.length(); index++) {
            directory = directory.resolve(String.valueOf(quadPath.charAt(index)));
        }
        Files.createDirectories(directory);
        return Files.write(directory.resolve(quadPath + ".png"), new byte[]{1});
    }
}
