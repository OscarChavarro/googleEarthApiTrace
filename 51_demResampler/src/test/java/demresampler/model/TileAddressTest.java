package demresampler.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TileAddressTest {
    @Test
    void followsRepositoryQuadrantConvention() {
        assertEquals("03", new TileAddress(1, 0, 0).quadkey());
        assertEquals("02", new TileAddress(1, 0, 1).quadkey());
        assertEquals("00", new TileAddress(1, 1, 0).quadkey());
        assertEquals("01", new TileAddress(1, 1, 1).quadkey());
        assertEquals("002", new TileAddress(2, 2, 1).quadkey());
    }

    @Test
    void serializesOneDirectoryPerDigitAfterRootMarker() {
        Path root = Path.of("/tmp/pyramid");
        assertEquals(root.resolve("0.bin"), new TileAddress(0, 0, 0).path(root));
        assertEquals(root.resolve("0/00.bin"), new TileAddress(1, 1, 0).path(root));
        assertEquals(root.resolve("0/2/002.bin"), new TileAddress(2, 2, 1).path(root));
    }

    @Test
    void parentAndChildrenRoundTrip() {
        TileAddress parent = new TileAddress(4, 6, 9);
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            assertEquals(parent, parent.child(quadrant).parent());
        }
    }
}
