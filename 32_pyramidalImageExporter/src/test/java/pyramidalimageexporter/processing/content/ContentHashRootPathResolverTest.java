package pyramidalimageexporter.processing.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ContentHashRootPathResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesACopyByContent() throws IOException {
        Path catalogued = Files.write(tempDir.resolve("catalogued.png"), new byte[]{1, 2, 3});
        Path copy = Files.write(tempDir.resolve("copy.png"), new byte[]{1, 2, 3});
        ContentHashRootPathResolver resolver = new ContentHashRootPathResolver();
        resolver.indexCataloguedImages(Map.of(catalogued.toString(), "031"));

        assertEquals("031", resolver.resolveQuadPath(copy.toString()).orElseThrow());
    }

    @Test
    void rejectsContentUsedByDifferentQuadtreeCells() throws IOException {
        Path first = Files.write(tempDir.resolve("first.png"), new byte[]{4, 5, 6});
        Path second = Files.write(tempDir.resolve("second.png"), new byte[]{4, 5, 6});
        Path copy = Files.write(tempDir.resolve("copy.png"), new byte[]{4, 5, 6});
        ContentHashRootPathResolver resolver = new ContentHashRootPathResolver();
        resolver.indexCataloguedImages(Map.of(first.toString(), "030", second.toString(), "031"));

        assertTrue(resolver.resolveQuadPath(copy.toString()).isEmpty());
    }
}
