package planetdemviewer.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import planetdemviewer.config.Configuration;
import planetdemviewer.model.DemTile;
import planetdemviewer.palette.PaletteCatalog;

class TileImageLoaderDatumTest {
    @TempDir Path temporaryDirectory;

    @Test
    void normalizesFabdemValuesToWgs84AtLoadTime() throws Exception {
        short[] source = new short[DemTile.SAMPLE_COUNT];
        java.util.Arrays.fill(source, DemTile.NO_DATA);
        for (int row = 1; row <= DemTile.CORE_SIZE; row++) {
            for (int column = 1; column <= DemTile.CORE_SIZE; column++) {
                source[row * DemTile.STORED_SIZE + column] = 1_000;
            }
        }
        ByteBuffer bytes = ByteBuffer.allocate(DemTile.BYTE_COUNT).order(ByteOrder.LITTLE_ENDIAN);
        for (short value : source) {
            bytes.putShort(value);
        }
        Path tilePath = temporaryDirectory.resolve("020.bin");
        Files.write(tilePath, bytes.array());
        PaletteCatalog palettes = new PaletteCatalog(Configuration.PALETTE_DIRECTORY,
            Configuration.MINIMUM_ELEVATION_METRES, Configuration.MAXIMUM_ELEVATION_METRES);
        TileImageLoader loader = new TileImageLoader(palettes);
        try {
            DemTile tile = loader.getElevationTile(tilePath.toFile());
            assertEquals(1_000, tile.elevation(1, 1));
            assertNotEquals(1_000.0, tile.elevationMetres(1, 1), 1e-6);
        }
        finally {
            loader.shutdown();
        }
    }
}
