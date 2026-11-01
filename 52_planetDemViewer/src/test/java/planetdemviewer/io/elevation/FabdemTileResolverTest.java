package planetdemviewer.io.elevation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import planetdemviewer.model.DemTile;
import planetdemviewer.spatial.TileAddress;

class FabdemTileResolverTest {
    @TempDir Path temporaryDirectory;

    @Test
    void resolvesTheExistingPerDigitFabdemLayoutFromAnAddress() throws Exception {
        Path directory = Files.createDirectories(temporaryDirectory.resolve("2").resolve("0"));
        Path expected = directory.resolve("020.bin");
        Files.write(expected, new byte[DemTile.BYTE_COUNT]);

        FabdemTileResolver resolver = new FabdemTileResolver(temporaryDirectory);

        assertTrue(resolver.resolve(TileAddress.fabdem("020")).isPresent());
        assertEquals(expected, resolver.resolve(TileAddress.fabdem("020")).orElseThrow());
    }
}
