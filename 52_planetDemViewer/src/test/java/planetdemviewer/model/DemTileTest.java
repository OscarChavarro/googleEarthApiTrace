package planetdemviewer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import planetdemviewer.config.Configuration;
import planetdemviewer.palette.PaletteCatalog;

class DemTileTest {
    @TempDir Path temporaryDirectory;

    @Test
    void decodesLittleEndianAndColorizesOnlyTheCore() throws Exception {
        short[] values = new short[DemTile.SAMPLE_COUNT];
        values[0] = 12_000; // halo: must not become the first image pixel
        values[DemTile.STORED_SIZE + 1] = 1_234;
        ByteBuffer bytes = ByteBuffer.allocate(DemTile.BYTE_COUNT).order(ByteOrder.LITTLE_ENDIAN);
        for (short value : values) {
            bytes.putShort(value);
        }
        Path file = temporaryDirectory.resolve("0.bin");
        Files.write(file, bytes.array());

        DemTile tile = DemTile.read(file);
        PaletteCatalog palettes = new PaletteCatalog(
            Configuration.PALETTE_DIRECTORY,
            Configuration.MINIMUM_ELEVATION_METRES,
            Configuration.MAXIMUM_ELEVATION_METRES
        );
        BufferedImage image = tile.colorizeCore(palettes);

        assertEquals(12_000, tile.elevation(0, 0));
        assertEquals(1_234, tile.elevation(1, 1));
        assertEquals(256, image.getWidth());
        assertEquals(palettes.snapshot().argbFor((short) 1_234), image.getRGB(0, 0));
    }
}
