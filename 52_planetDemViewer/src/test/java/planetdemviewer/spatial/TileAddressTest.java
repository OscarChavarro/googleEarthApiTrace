package planetdemviewer.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
}

