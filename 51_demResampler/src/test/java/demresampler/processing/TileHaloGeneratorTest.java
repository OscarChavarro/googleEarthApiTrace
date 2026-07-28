package demresampler.processing;

import demresampler.io.RawTileIO;
import demresampler.model.TileAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TileHaloGeneratorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void copiesAllNeighborEdgesAndWrapsAcrossAntimeridian() throws Exception {
        TileAddress target = tile(1, 0);
        writeCore(target, (short) 10);
        writeCore(tile(0, 0), (short) 20); // north
        writeCore(tile(2, 0), (short) 30); // south
        writeCore(tile(1, 3), (short) 40); // west, wrapped from column 0
        writeCore(tile(1, 1), (short) 50); // east
        writeCore(tile(0, 3), (short) 60); // north-west, wrapped
        writeCore(tile(0, 1), (short) 70); // north-east
        writeCore(tile(2, 3), (short) 80); // south-west, wrapped
        writeCore(tile(2, 1), (short) 90); // south-east

        TileHaloGenerator.generate(
            temporaryDirectory, target.level(), Set.of(target.packedCoordinates()), 1);

        short[] stored = RawTileIO.read(temporaryDirectory, target);
        assertEquals(10, at(stored, 1, 1));
        assertEquals(10, at(stored, 256, 256));
        assertEquals(20, at(stored, 100, 0));
        assertEquals(30, at(stored, 100, 257));
        assertEquals(40, at(stored, 0, 100));
        assertEquals(50, at(stored, 257, 100));
        assertEquals(60, at(stored, 0, 0));
        assertEquals(70, at(stored, 257, 0));
        assertEquals(80, at(stored, 0, 257));
        assertEquals(90, at(stored, 257, 257));
    }

    @Test
    void missingVerticalAndDiagonalNeighborsBecomeNoData() throws Exception {
        TileAddress target = tile(0, 2);
        writeCore(target, (short) 10);

        TileHaloGenerator.generate(
            temporaryDirectory, target.level(), Set.of(target.packedCoordinates()), 1);

        short[] stored = RawTileIO.read(temporaryDirectory, target);
        assertEquals(RawTileIO.NODATA, at(stored, 100, 0));
        assertEquals(RawTileIO.NODATA, at(stored, 0, 0));
    }

    @Test
    void keepsRowAndColumnOrderWhenCopyingBorders() throws Exception {
        TileAddress target = tile(1, 1);
        writeCore(target, (short) 10);

        short[] north = new short[RawTileIO.CORE_SAMPLE_COUNT];
        short[] west = new short[RawTileIO.CORE_SAMPLE_COUNT];
        for (int index = 0; index < RawTileIO.CORE_SIDE; index++) {
            north[(RawTileIO.CORE_SIDE - 1) * RawTileIO.CORE_SIDE + index] =
                (short) (1000 + index);
            west[index * RawTileIO.CORE_SIDE + RawTileIO.CORE_SIDE - 1] =
                (short) (2000 + index);
        }
        RawTileIO.writeCore(temporaryDirectory, tile(0, 1), north);
        RawTileIO.writeCore(temporaryDirectory, tile(1, 0), west);

        TileHaloGenerator.generate(
            temporaryDirectory, target.level(), Set.of(target.packedCoordinates()), 1);

        short[] stored = RawTileIO.read(temporaryDirectory, target);
        assertEquals(1000, at(stored, 1, 0));
        assertEquals(1127, at(stored, 128, 0));
        assertEquals(1255, at(stored, 256, 0));
        assertEquals(2000, at(stored, 0, 1));
        assertEquals(2127, at(stored, 0, 128));
        assertEquals(2255, at(stored, 0, 256));
    }

    @Test
    void republishesHaloOnResumeAndRemovesLegacySidecar() throws Exception {
        TileAddress target = tile(1, 1);
        TileAddress north = tile(0, 1);
        writeCore(target, (short) 10);
        writeCore(north, (short) 20);

        Path legacyEdges = target.path(temporaryDirectory).resolveSibling(
            target.path(temporaryDirectory).getFileName() + ".edges");
        Files.write(legacyEdges, new byte[] {1});

        TileHaloGenerator.generate(
            temporaryDirectory, target.level(), Set.of(target.packedCoordinates()), 1);
        assertEquals(20, at(RawTileIO.read(temporaryDirectory, target), 100, 0));
        assertFalse(Files.exists(legacyEdges));

        writeCore(north, (short) 30);
        TileHaloGenerator.generate(
            temporaryDirectory, target.level(), Set.of(target.packedCoordinates()), 1);
        assertEquals(30, at(RawTileIO.read(temporaryDirectory, target), 100, 0));
    }

    private TileAddress tile(int row, int column) {
        return new TileAddress(2, row, column);
    }

    private void writeCore(TileAddress address, short value) throws Exception {
        short[] core = new short[RawTileIO.CORE_SAMPLE_COUNT];
        Arrays.fill(core, value);
        RawTileIO.writeCore(temporaryDirectory, address, core);
    }

    private static short at(short[] samples, int x, int y) {
        return samples[y * RawTileIO.STORED_SIDE + x];
    }
}
