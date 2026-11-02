package planetdemviewer.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TerrainGridTraversalTest {
    private static final TerrainGridTraversal ROWS = TerrainSubdivisionScheme.TRIANGLE_EDGE.traversal();
    private static final TerrainGridTraversal DIAMONDS = TerrainSubdivisionScheme.DIAMOND_SQUARE.traversal();

    /** Twice the area in row/column units, positive when counter-clockwise seen from above. */
    private static int doubleArea(int r0, int c0, int r1, int c1, int r2, int c2) {
        return (c1 - c0) * (r0 - r2) - (r0 - r1) * (c2 - c0);
    }

    private static long[] countAndArea(TerrainGridTraversal traversal) {
        long[] result = new long[2];
        traversal.traverse((r0, c0, r1, c1, r2, c2) -> {
            int area = doubleArea(r0, c0, r1, c1, r2, c2);
            assertTrue(area > 0, "triangle must be counter-clockwise");
            result[0]++;
            result[1] += area;
        });
        return result;
    }

    @Test
    void bothSchemesCoverTheTileExactlyOnce() {
        assertEquals(255 * 255 * 2, countAndArea(ROWS)[0]);
        assertEquals(255 * 255 * 2, countAndArea(ROWS)[1]);
        assertEquals(256 * 256 * 2, countAndArea(DIAMONDS)[0]);
        assertEquals(256 * 256 * 2, countAndArea(DIAMONDS)[1]);
    }

    @Test
    void diamondSquareUsesEveryVertexOfItsGrid() {
        Set<Integer> vertices = new HashSet<>();
        DIAMONDS.traverse((r0, c0, r1, c1, r2, c2) -> {
            vertices.add(r0 * 257 + c0);
            vertices.add(r1 * 257 + c1);
            vertices.add(r2 * 257 + c2);
        });
        assertEquals(257 * 257, vertices.size());
    }

    @Test
    void triangleEdgeHasOneDiagonalAndDiamondSquareAlternatesIt() {
        assertEquals(Set.of(-1), diagonalDirections(ROWS, 0, 0, 4));
        assertEquals(Set.of(-1, 1), diagonalDirections(DIAMONDS, 0, 0, 4));
        assertNotEquals(diagonal(DIAMONDS, 0, 0), diagonal(DIAMONDS, 0, 1));
        assertNotEquals(diagonal(DIAMONDS, 0, 0), diagonal(DIAMONDS, 1, 0));
        assertEquals(diagonal(DIAMONDS, 0, 0), diagonal(DIAMONDS, 1, 1));
    }

    private static Set<Integer> diagonalDirections(TerrainGridTraversal traversal, int row, int column, int size) {
        Set<Integer> directions = new HashSet<>();
        for (int r = row; r < row + size; r++) {
            for (int c = column; c < column + size; c++) {
                directions.add(diagonal(traversal, r, c));
            }
        }
        return directions;
    }

    /** +1 when the cell's diagonal runs north-west to south-east, -1 otherwise. */
    private static int diagonal(TerrainGridTraversal traversal, int row, int column) {
        int[] direction = new int[1];
        traversal.traverse((r0, c0, r1, c1, r2, c2) -> {
            int[][] points = {{r0, c0}, {r1, c1}, {r2, c2}};
            for (int i = 0; i < 3; i++) {
                int[] a = points[i];
                int[] b = points[(i + 1) % 3];
                if (Math.abs(a[0] - b[0]) == 1 && Math.abs(a[1] - b[1]) == 1
                    && Math.min(a[0], b[0]) == row && Math.min(a[1], b[1]) == column) {
                    direction[0] = (a[0] - b[0]) * (a[1] - b[1]);
                }
            }
        });
        return direction[0];
    }

    @Test
    void diamondSquareVerticesHaveTheValencesOfFigureTwo() {
        Map<Integer, Integer> triangles = new HashMap<>();
        DIAMONDS.traverse((r0, c0, r1, c1, r2, c2) -> {
            triangles.merge(r0 * 257 + c0, 1, Integer::sum);
            triangles.merge(r1 * 257 + c1, 1, Integer::sum);
            triangles.merge(r2 * 257 + c2, 1, Integer::sum);
        });
        assertEquals(8, triangles.get(2 * 257 + 2));
        assertEquals(4, triangles.get(1 * 257 + 2));
        assertEquals(8, triangles.get(1 * 257 + 1));
    }

    @Test
    void disabledVerticesStopTheBisectionWithoutLeavingHoles() {
        TerrainGridTraversal coarse = new DiamondSquareTraversal(
            (row, column) -> row % 2 == 0 && column % 2 == 0);
        long[] result = countAndArea(coarse);
        assertEquals(2 * 128 * 128, result[0]);
        assertEquals(2 * 256 * 256, result[1]);
    }

    @Test
    void onlyDiamondSquareCanSubdivideBelowTheGeometry() {
        assertFalse(TerrainSubdivisionScheme.TRIANGLE_EDGE.allowsImageryBeyondGeometryLevel());
        assertTrue(TerrainSubdivisionScheme.DIAMOND_SQUARE.allowsImageryBeyondGeometryLevel());
    }
}
