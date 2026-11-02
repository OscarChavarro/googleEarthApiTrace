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
}

