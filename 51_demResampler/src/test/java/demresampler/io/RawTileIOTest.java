package demresampler.io;

import demresampler.model.TileAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawTileIOTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesExactlyLittleEndianSignedSixteenBitStoredSamples() throws Exception {
        short[] samples = new short[RawTileIO.STORED_SAMPLE_COUNT];
        samples[0] = (short) 0x1234;
        samples[1] = (short) -2;
        TileAddress root = new TileAddress(0, 0, 0);

        RawTileIO.write(temporaryDirectory, root, samples);

        byte[] bytes = Files.readAllBytes(root.path(temporaryDirectory));
        assertEquals(258 * 258 * 2, RawTileIO.BYTE_SIZE);
        assertEquals(RawTileIO.BYTE_SIZE, bytes.length);
        assertEquals(0x34, Byte.toUnsignedInt(bytes[0]));
        assertEquals(0x12, Byte.toUnsignedInt(bytes[1]));
        assertEquals(0xfe, Byte.toUnsignedInt(bytes[2]));
        assertEquals(0xff, Byte.toUnsignedInt(bytes[3]));
        assertArrayEquals(samples, RawTileIO.read(temporaryDirectory, root));
    }

    @Test
    void coreIsStoredInsideOneCompleteTileWithoutAnEdgeSidecar() throws Exception {
        short[] core = new short[RawTileIO.CORE_SAMPLE_COUNT];
        for (int y = 0; y < RawTileIO.CORE_SIDE; y++) {
            for (int x = 0; x < RawTileIO.CORE_SIDE; x++) {
                core[y * RawTileIO.CORE_SIDE + x] = (short) (y - x);
            }
        }
        TileAddress root = new TileAddress(0, 0, 0);

        RawTileIO.writeCore(temporaryDirectory, root, core);

        assertTrue(RawTileIO.isCoreComplete(temporaryDirectory, root));
        assertEquals(RawTileIO.BYTE_SIZE, Files.size(root.path(temporaryDirectory)));
        assertFalse(Files.exists(root.path(temporaryDirectory).resolveSibling("0.bin.edges")));
        assertArrayEquals(core, RawTileIO.readCore(temporaryDirectory, root));
        short[] stored = RawTileIO.read(temporaryDirectory, root);
        assertEquals(RawTileIO.NODATA, stored[0]);
        assertEquals(core[0], stored[RawTileIO.STORED_SIDE + 1]);
        assertEquals(
            core[255 * 256],
            RawTileIO.readCoreBorder(
                temporaryDirectory, root, RawTileIO.Border.SOUTH)[0]);
        assertEquals(
            core[255],
            RawTileIO.readCoreBorder(
                temporaryDirectory, root, RawTileIO.Border.EAST)[0]);
    }

    @Test
    void extractsCoreFromPublishedTileWithoutIncludingHalo() throws Exception {
        short[] stored = new short[RawTileIO.STORED_SAMPLE_COUNT];
        Arrays.fill(stored, (short) -7);
        for (int y = 0; y < RawTileIO.CORE_SIDE; y++) {
            Arrays.fill(
                stored,
                (y + 1) * RawTileIO.STORED_SIDE + 1,
                (y + 1) * RawTileIO.STORED_SIDE + 1 + RawTileIO.CORE_SIDE,
                (short) 42);
        }
        TileAddress root = new TileAddress(0, 0, 0);

        RawTileIO.write(temporaryDirectory, root, stored);

        short[] core = RawTileIO.readCore(temporaryDirectory, root);
        assertEquals(RawTileIO.CORE_SAMPLE_COUNT, core.length);
        for (short sample : core) {
            assertEquals(42, sample);
        }
    }
}
