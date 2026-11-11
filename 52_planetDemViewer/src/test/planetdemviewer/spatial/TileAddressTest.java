package planetdemviewer.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class TileAddressTest {
    @Test
    void preservesTheCurrentFabdemQuadkeyConventionWithoutFilePaths() {
        TileAddress root = TileAddress.fabdem("0");
        TileAddress child = root.child(2);
        TileAddress grandchild = child.child(1);

        assertEquals(0, root.level());
        assertEquals("02", child.quadkey());
        assertEquals(1, child.level());
        assertEquals("021", grandchild.quadkey());
        assertEquals(child, grandchild.parent());
        assertNull(root.parent());
    }

    @Test
    void containsItselfAndItsDescendantsOnly() {
        TileAddress tile = TileAddress.fabdem("02");

        assertTrue(tile.contains(tile));
        assertTrue(tile.contains(TileAddress.fabdem("0213")));
        assertFalse(tile.contains(TileAddress.fabdem("0")));
        assertFalse(tile.contains(TileAddress.fabdem("03")));
        assertFalse(tile.contains(new TileAddress("other-scheme", "021")));
    }

    @Test
    void neighborsAreTheAdjacentTilesOfTheSameLevel() {
        // Digits 0 to 3 are south-west, south-east, north-east, north-west.
        assertEquals("01", TileAddress.fabdem("00").neighbor(1, 0).quadkey());
        assertEquals("03", TileAddress.fabdem("00").neighbor(0, 1).quadkey());
        assertEquals("010", TileAddress.fabdem("001").neighbor(1, 0).quadkey());
        assertEquals("001", TileAddress.fabdem("010").neighbor(-1, 0).quadkey());
        assertEquals("030", TileAddress.fabdem("003").neighbor(0, 1).quadkey());
        TileAddress deep = TileAddress.fabdem("0123012301230123");
        assertEquals(deep.x0() + deep.size(), deep.neighbor(1, 0).x0());
        assertEquals(deep.y0(), deep.neighbor(1, 0).y0());
        assertEquals(deep, deep.neighbor(1, 0).neighbor(-1, 0));
    }

    @Test
    void thereIsNoNeighborOutsideTheRootTile() {
        assertNull(TileAddress.fabdem("0").neighbor(1, 0));
        assertNull(TileAddress.fabdem("00").neighbor(-1, 0));
        assertNull(TileAddress.fabdem("00").neighbor(0, -1));
        assertNull(TileAddress.fabdem("02").neighbor(1, 0));
        assertNull(TileAddress.fabdem("02").neighbor(0, 1));
    }
}
