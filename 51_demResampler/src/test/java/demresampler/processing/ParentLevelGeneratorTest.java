package demresampler.processing;

import demresampler.io.RawTileIO;
import demresampler.model.TileAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ParentLevelGeneratorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void placesAllFourRepositoryQuadrantsInTheCorrectOrientation() {
        short[][] children = {
            constant((short) 10),
            constant((short) 20),
            constant((short) 30),
            constant((short) 40)
        };
        short[] parent = ParentLevelGenerator.downsample(children);

        assertEquals(40, at(parent, 10, 10));    // NW = 3
        assertEquals(30, at(parent, 200, 10));   // NE = 2
        assertEquals(10, at(parent, 10, 200));   // SW = 0
        assertEquals(20, at(parent, 200, 200));  // SE = 1
    }

    @Test
    void averagesEachValidTwoByTwoGroupAndIgnoresNoData() {
        short[][] children = new short[4][];
        children[3] = constant(RawTileIO.NODATA);
        children[3][0] = 10;
        children[3][1] = 20;
        children[3][RawTileIO.CORE_SIDE] = 30;
        children[3][RawTileIO.CORE_SIDE + 1] = RawTileIO.NODATA;

        short[] parent = ParentLevelGenerator.downsample(children);

        assertEquals(20, at(parent, 0, 0));
        assertEquals(RawTileIO.NODATA, at(parent, 1, 0));
        assertEquals(RawTileIO.NODATA, at(parent, 200, 200));
    }

    @Test
    void automaticallyKeepsAnExistingCompleteParent() throws Exception {
        TileAddress parent = new TileAddress(0, 0, 0);
        TileAddress child = parent.child(3);
        short[] existing = storedConstant((short) 123);
        RawTileIO.write(temporaryDirectory, parent, existing);
        RawTileIO.write(temporaryDirectory, child, storedConstant((short) 50));

        Set<Long> generated = ParentLevelGenerator.generate(
            temporaryDirectory, 0, Set.of(child.packedCoordinates()), 1);

        assertEquals(Set.of(parent.packedCoordinates()), generated);
        assertArrayEquals(existing, RawTileIO.read(temporaryDirectory, parent));
    }

    private static short[] constant(short value) {
        short[] result = new short[RawTileIO.CORE_SAMPLE_COUNT];
        Arrays.fill(result, value);
        return result;
    }

    private static short[] storedConstant(short value) {
        short[] result = new short[RawTileIO.STORED_SAMPLE_COUNT];
        Arrays.fill(result, value);
        return result;
    }

    private static short at(short[] samples, int x, int y) {
        return samples[y * RawTileIO.CORE_SIDE + x];
    }
}
