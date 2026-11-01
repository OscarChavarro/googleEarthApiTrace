package planetdemviewer.io.imagery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import planetdemviewer.spatial.TileAddress;

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

    @Test
    void deletesOnlyTheResolvedPngPage() throws Exception {
        Path pyramid = Files.createDirectory(temporaryDirectory.resolve("toplevel"));
        Path png = Files.write(pyramid.resolve("0.png"), new byte[] {1});
        Path bin = Files.write(pyramid.resolve("0.bin"), new byte[] {2});
        ImageryTileLoader loader = new ImageryTileLoader(pyramid);

        assertEquals(png.toRealPath(), loader.delete(TileAddress.fabdem("0")).orElseThrow());
        assertTrue(Files.notExists(png));
        assertTrue(Files.exists(bin));

        loader.shutdown();
    }
}
