package planetdemviewer.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerrainVertexTreeTest {
    private static final TerrainVertexTree TREE = TerrainVertexTree.INSTANCE;
    private static final int LAST = TerrainVertexTree.SIDE - 1;

    private static int parentCount(int vertex) {
        return (TREE.parent(vertex, 0) != TerrainVertexTree.NONE ? 1 : 0)
            + (TREE.parent(vertex, 1) != TerrainVertexTree.NONE ? 1 : 0);
    }

    private static int childCount(int vertex) {
        int count = 0;
        for (int branch = 0; branch < 4; branch++) {
            if (TREE.child(vertex, branch) != TerrainVertexTree.NONE) {
                count++;
            }
        }
        return count;
    }

    @Test
    void onlyTheQuadrantVerticesAreNotBases() {
        int notBases = 0;
        for (int vertex = 0; vertex < TerrainVertexTree.VERTEX_COUNT; vertex++) {
            if (!TREE.isBase(vertex)) {
                notBases++;
            }
        }
        assertEquals(5, notBases);
        assertFalse(TREE.isBase(TerrainVertexTree.vertex(0, 0)));
        assertFalse(TREE.isBase(TerrainVertexTree.vertex(LAST, LAST)));
        assertFalse(TREE.isBase(TerrainVertexTree.vertex(LAST / 2, LAST / 2)));
    }

    @Test
    void everyBaseIsTheMidpointOfItsHypotenuse() {
        for (int vertex = 0; vertex < TerrainVertexTree.VERTEX_COUNT; vertex++) {
            if (TREE.isBase(vertex)) {
                int a = TREE.hypotenuseEnd(vertex, 0);
                int c = TREE.hypotenuseEnd(vertex, 1);
                assertEquals(vertex / TerrainVertexTree.SIDE * 2, a / TerrainVertexTree.SIDE + c / TerrainVertexTree.SIDE);
                assertEquals(vertex % TerrainVertexTree.SIDE * 2, a % TerrainVertexTree.SIDE + c % TerrainVertexTree.SIDE);
            }
        }
    }

    /** Section [LIND1996].4.3: two parents and four children, fewer at the boundary of the data. */
    @Test
    void verticesHaveTheParentsAndChildrenOfThePaper() {
        for (int vertex = 0; vertex < TerrainVertexTree.VERTEX_COUNT; vertex++) {
            if (!TREE.isBase(vertex)) {
                continue;
            }
            assertEquals(TREE.isOnBoundary(vertex) ? 1 : 2, parentCount(vertex), "vertex " + vertex);
            int row = vertex / TerrainVertexTree.SIDE;
            int column = vertex % TerrainVertexTree.SIDE;
            boolean deepest = ((row + column) & 1) != 0;
            int expected = deepest ? 0 : TREE.isOnBoundary(vertex) ? 2 : 4;
            assertEquals(expected, childCount(vertex), "vertex " + vertex);
        }
        assertEquals(4, childCount(TerrainVertexTree.vertex(LAST / 2, LAST / 2)));
    }

    @Test
    void parentsAndChildrenAgree() {
        for (int vertex = 0; vertex < TerrainVertexTree.VERTEX_COUNT; vertex++) {
            for (int tree = 0; tree < 2; tree++) {
                int parent = TREE.parent(vertex, tree);
                if (parent != TerrainVertexTree.NONE) {
                    assertEquals(vertex, TREE.child(parent, TREE.branch(vertex, tree)));
                }
            }
        }
    }

    @Test
    void lowestLevelVerticesAreTheOnesInDiscardedRowsAndColumns() {
        assertTrue(TREE.isLowestLevel(TerrainVertexTree.vertex(1, 2)));
        assertTrue(TREE.isLowestLevel(TerrainVertexTree.vertex(2, 1)));
        assertTrue(TREE.isLowestLevel(TerrainVertexTree.vertex(1, 1)));
        assertFalse(TREE.isLowestLevel(TerrainVertexTree.vertex(2, 2)));
    }
}
