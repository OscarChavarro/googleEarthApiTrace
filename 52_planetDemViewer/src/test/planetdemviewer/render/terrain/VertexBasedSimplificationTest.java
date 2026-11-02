package planetdemviewer.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class VertexBasedSimplificationTest {
    private static final int SIDE = TerrainVertexTree.SIDE;
    private static final int LAST = SIDE - 1;
    private static final TerrainMeshPlacement PLACEMENT = new TerrainMeshPlacement(0.0, 0.0, 0.0, 1.0, 1.0);
    private static final TerrainVertexTree TREE = TerrainVertexTree.INSTANCE;

    private static double[] positions(double[] z) {
        double[] positions = new double[TerrainVertexTree.VERTEX_COUNT * 3];
        for (int vertex = 0; vertex < TerrainVertexTree.VERTEX_COUNT; vertex++) {
            positions[vertex * 3] = (double) (vertex % SIDE) / LAST - 0.5;
            positions[vertex * 3 + 1] = 0.5 - (double) (vertex / SIDE) / LAST;
            positions[vertex * 3 + 2] = z[vertex];
        }
        return positions;
    }

    /** Smooth terrain plus noise, in mesh units, in the range of real elevations. */
    private static double[] roughTerrain(long seed) {
        Random random = new Random(seed);
        double[] z = new double[TerrainVertexTree.VERTEX_COUNT];
        for (int vertex = 0; vertex < z.length; vertex++) {
            double x = (double) (vertex % SIDE) / LAST;
            double y = (double) (vertex / SIDE) / LAST;
            z[vertex] = 0.01 * Math.sin(x * 9.0) * Math.cos(y * 7.0) + 1e-4 * random.nextDouble();
        }
        return z;
    }

    private static boolean[] allValid() {
        boolean[] valid = new boolean[TerrainVertexTree.VERTEX_COUNT];
        Arrays.fill(valid, true);
        return valid;
    }

    private static VertexBasedSimplification simplification(double[] z, boolean[] valid) {
        return new VertexBasedSimplification(new VertexDeltaTable(z, valid, 0.0), positions(z));
    }

    private static void settle(VertexBasedSimplification selection, ProjectedDeltaCriterion criterion) {
        int passes = 0;
        while (selection.simplify(criterion, PLACEMENT)) {
            assertTrue(++passes < 64, "the selection must settle");
        }
    }

    private static boolean[] enabled(VertexBasedSimplification selection) {
        boolean[] result = new boolean[TerrainVertexTree.VERTEX_COUNT];
        for (int vertex = 0; vertex < result.length; vertex++) {
            result[vertex] = selection.isEnabled(vertex / SIDE, vertex % SIDE);
        }
        return result;
    }

    private static void assertDependenciesHold(VertexBasedSimplification selection) {
        for (int vertex = 0; vertex < TerrainVertexTree.VERTEX_COUNT; vertex++) {
            if (selection.isEnabled(vertex / SIDE, vertex % SIDE)) {
                for (int tree = 0; tree < 2; tree++) {
                    int parent = TREE.parent(vertex, tree);
                    if (parent != TerrainVertexTree.NONE) {
                        assertTrue(selection.isEnabled(parent / SIDE, parent % SIDE),
                            "vertex " + vertex + " needs its parent " + parent);
                    }
                }
            }
        }
    }

    /** Every edge is shared by two triangles, or lies on the block boundary: there are no T-junctions. */
    private static void assertConformingCover(VertexBasedSimplification selection) {
        Map<Long, Integer> edges = new HashMap<>();
        long[] area = new long[1];
        new DiamondSquareTraversal(selection).traverse((r0, c0, r1, c1, r2, c2) -> {
            area[0] += Math.abs((c1 - c0) * (r2 - r0) - (c2 - c0) * (r1 - r0));
            countEdge(edges, r0, c0, r1, c1);
            countEdge(edges, r1, c1, r2, c2);
            countEdge(edges, r2, c2, r0, c0);
        });
        assertEquals(2L * LAST * LAST, area[0]);
        for (Map.Entry<Long, Integer> edge : edges.entrySet()) {
            int a = (int) (edge.getKey() >> 32);
            int b = (int) (long) edge.getKey();
            boolean onBoundary = TREE.isOnBoundary(a) && TREE.isOnBoundary(b)
                && (a / SIDE == b / SIDE || a % SIDE == b % SIDE);
            assertEquals(onBoundary ? 1 : 2, edge.getValue(), "edge " + a + "-" + b);
        }
    }

    private static void countEdge(Map<Long, Integer> edges, int r0, int c0, int r1, int c1) {
        int a = TerrainVertexTree.vertex(r0, c0);
        int b = TerrainVertexTree.vertex(r1, c1);
        edges.merge(((long) Math.min(a, b) << 32) | Math.max(a, b), 1, Integer::sum);
    }

    private static int triangleCount(VertexBasedSimplification selection) {
        int[] count = new int[1];
        new DiamondSquareTraversal(selection).traverse((r0, c0, r1, c1, r2, c2) -> count[0]++);
        return count[0];
    }

    @Test
    void aSimplifiedBlockStaysConformingAtAnyThreshold() {
        double[] z = roughTerrain(3);
        for (double kappa : new double[] {0.0, 1e-5, 1e-4, 1e-3, 1e-1}) {
            VertexBasedSimplification selection = simplification(z, allValid());
            settle(selection, new ProjectedDeltaCriterion(0.8, -0.9, 0.3, kappa));
            assertDependenciesHold(selection);
            assertConformingCover(selection);
        }
    }

    @Test
    void aLargerThresholdNeverAddsTriangles() {
        double[] z = roughTerrain(5);
        int previous = Integer.MAX_VALUE;
        for (double kappa : new double[] {0.0, 1e-5, 1e-4, 1e-3, 1e-2}) {
            VertexBasedSimplification selection = simplification(z, allValid());
            settle(selection, new ProjectedDeltaCriterion(0.8, -0.9, 0.3, kappa));
            int triangles = triangleCount(selection);
            assertTrue(triangles <= previous, triangles + " after " + previous);
            previous = triangles;
        }
        assertTrue(previous < 131_072 / 2, "got " + previous);
    }

    @Test
    void aFlatBlockKeepsOnlyWhatItsLockedBoundaryRequires() {
        VertexBasedSimplification selection = simplification(new double[TerrainVertexTree.VERTEX_COUNT], allValid());
        settle(selection, new ProjectedDeltaCriterion(0.0, 0.0, 1.0, 0.0));
        for (int i = 0; i <= LAST; i++) {
            assertTrue(selection.isEnabled(0, i));
            assertTrue(selection.isEnabled(LAST, i));
            assertTrue(selection.isEnabled(i, 0));
            assertTrue(selection.isEnabled(i, LAST));
        }
        assertFalse(selection.isEnabled(LAST / 2 + 1, LAST / 2 + 1));
        assertConformingCover(selection);
        assertTrue(triangleCount(selection) < 131_072 / 8, "got " + triangleCount(selection));
    }

    /** The incremental passes of section [LIND1996].5 must reach what a first pass computes. */
    @Test
    void movingTheViewpointReachesTheSameSelectionAsStartingThere() {
        double[] z = roughTerrain(7);
        VertexBasedSimplification moved = simplification(z, allValid());
        Random random = new Random(13);
        for (int step = 0; step < 40; step++) {
            ProjectedDeltaCriterion criterion = new ProjectedDeltaCriterion(
                random.nextDouble() * 3 - 1.5, random.nextDouble() * 3 - 1.5, 0.1 + random.nextDouble(), 2e-4);
            moved.simplify(criterion, PLACEMENT);
            assertDependenciesHold(moved);
            if (step % 8 == 7) {
                settle(moved, criterion);
                VertexBasedSimplification fresh = simplification(z, allValid());
                settle(fresh, criterion);
                assertTrue(Arrays.equals(enabled(fresh), enabled(moved)), "step " + step);
            }
        }
    }

    @Test
    void aRepeatedViewpointDoesNoWork() {
        double[] z = roughTerrain(9);
        VertexBasedSimplification selection = simplification(z, allValid());
        ProjectedDeltaCriterion criterion = new ProjectedDeltaCriterion(0.8, -0.9, 0.3, 1e-4);
        settle(selection, criterion);
        assertFalse(selection.simplify(criterion, PLACEMENT));
        assertEquals(0, selection.deltaProjections());
    }

    @Test
    void theUncertaintyIntervalAvoidsMostEvaluationsOfTheEquation() {
        double[] z = roughTerrain(9);
        VertexBasedSimplification selection = simplification(z, allValid());
        selection.simplify(new ProjectedDeltaCriterion(5.0, -5.0, 3.0, 1e-5), PLACEMENT);
        assertTrue(selection.deltaProjections() < TerrainVertexTree.VERTEX_COUNT);
    }

    /** No hole grows and no NoData sample is covered, however coarse the rest of the block gets. */
    @Test
    void noDataKeepsTheExactOutlineOfTheFullMesh() {
        double[] z = new double[TerrainVertexTree.VERTEX_COUNT];
        boolean[] valid = allValid();
        Random random = new Random(21);
        for (int hole = 0; hole < 12; hole++) {
            int row = random.nextInt(SIDE);
            int column = random.nextInt(SIDE);
            int size = 1 + random.nextInt(40);
            for (int r = Math.max(0, row - size); r <= Math.min(LAST, row + size); r++) {
                for (int c = Math.max(0, column - size); c <= Math.min(LAST, column + size); c++) {
                    valid[TerrainVertexTree.vertex(r, c)] = false;
                }
            }
        }
        VertexBasedSimplification selection = simplification(z, valid);
        settle(selection, new ProjectedDeltaCriterion(0.8, -0.9, 0.3, 10.0));
        assertEquals(validArea(TerrainVertexSelection.ALL, valid), validArea(selection, valid));
        assertTrue(triangleCount(selection) < 131_072);
    }

    private static long validArea(TerrainVertexSelection selection, boolean[] valid) {
        long[] area = new long[1];
        new DiamondSquareTraversal(selection).traverse((r0, c0, r1, c1, r2, c2) -> {
            if (valid[TerrainVertexTree.vertex(r0, c0)] && valid[TerrainVertexTree.vertex(r1, c1)]
                && valid[TerrainVertexTree.vertex(r2, c2)]) {
                area[0] += Math.abs((c1 - c0) * (r2 - r0) - (c2 - c0) * (r1 - r0));
            }
        });
        return area[0];
    }

    /** A block to the west and one to the east, with the same heights on their common edge. */
    private static VertexBasedSimplification[] adjacentRoughBlocks(long seed) {
        double[] westZ = roughTerrain(seed);
        double[] eastZ = roughTerrain(seed + 1);
        for (int position = 0; position < SIDE; position++) {
            eastZ[TerrainVertexTree.vertex(position, 0)] = westZ[TerrainVertexTree.vertex(position, LAST)];
        }
        double[] eastPositions = positions(eastZ);
        for (int vertex = 0; vertex < TerrainVertexTree.VERTEX_COUNT; vertex++) {
            eastPositions[vertex * 3] += 1.0;
        }
        return new VertexBasedSimplification[] {
            simplification(westZ, allValid()),
            new VertexBasedSimplification(new VertexDeltaTable(eastZ, allValid(), 0.0), eastPositions)};
    }

    private static void share(VertexBasedSimplification[] blocks) {
        blocks[0].constrainEdge(TerrainEdge.EAST, false, blocks[1]);
        blocks[1].constrainEdge(TerrainEdge.WEST, false, blocks[0]);
    }

    private static void settleBoth(VertexBasedSimplification[] blocks, ProjectedDeltaCriterion criterion) {
        int passes = 0;
        boolean changed = true;
        while (changed) {
            changed = blocks[0].simplify(criterion, PLACEMENT);
            changed |= blocks[1].simplify(criterion, PLACEMENT);
            assertTrue(++passes < 64, "the selection must settle");
        }
    }

    private static int enabledOnSharedEdge(VertexBasedSimplification[] blocks) {
        int enabled = 0;
        for (int position = 0; position < SIDE; position++) {
            boolean west = blocks[0].isEnabled(position, LAST);
            assertEquals(west, blocks[1].isEnabled(position, 0), "both copies at " + position);
            enabled += west ? 1 : 0;
        }
        return enabled;
    }

    private static void assertSharedSelectionIsValid(VertexBasedSimplification[] blocks) {
        enabledOnSharedEdge(blocks);
        for (VertexBasedSimplification block : blocks) {
            assertDependenciesHold(block);
            assertConformingCover(block);
        }
    }

    @Test
    void theCopiesOfASharedVertexAgreeAtAnyThreshold() {
        VertexBasedSimplification[] blocks = adjacentRoughBlocks(21);
        share(blocks);
        for (double kappa : new double[] {0.0, 1e-4, 1e-3, 1e-2, 1e-1}) {
            settleBoth(blocks, new ProjectedDeltaCriterion(0.3, -0.9, 0.5, kappa));
            assertSharedSelectionIsValid(blocks);
        }
    }

    @Test
    void sharingAnEdgeRemovesSamplesFromIt() {
        VertexBasedSimplification[] blocks = adjacentRoughBlocks(22);
        ProjectedDeltaCriterion criterion = new ProjectedDeltaCriterion(0.5, 0.0, 2.0, 1e-2);
        settleBoth(blocks, criterion);
        assertEquals(SIDE, enabledOnSharedEdge(blocks));
        int lockedTriangles = triangleCount(blocks[0]);
        share(blocks);
        settleBoth(blocks, criterion);
        assertTrue(enabledOnSharedEdge(blocks) < SIDE);
        assertTrue(triangleCount(blocks[0]) < lockedTriangles);
    }

    /** Two copies must not keep each other enabled once neither block needs the vertex. */
    @Test
    void aSharedSelectionDoesNotDependOnThePathOfTheViewpoint() {
        ProjectedDeltaCriterion near = new ProjectedDeltaCriterion(1.0, 0.0, 0.2, 1e-3);
        ProjectedDeltaCriterion far = new ProjectedDeltaCriterion(1.0, 0.0, 8.0, 1e-3);
        VertexBasedSimplification[] moved = adjacentRoughBlocks(23);
        share(moved);
        settleBoth(moved, near);
        int nearEnabled = enabledOnSharedEdge(moved);
        settleBoth(moved, far);
        assertSharedSelectionIsValid(moved);

        VertexBasedSimplification[] direct = adjacentRoughBlocks(23);
        share(direct);
        settleBoth(direct, far);
        assertTrue(enabledOnSharedEdge(moved) < nearEnabled);
        for (int vertex = 0; vertex < TerrainVertexTree.VERTEX_COUNT; vertex++) {
            int row = vertex / SIDE;
            int column = vertex % SIDE;
            assertEquals(direct[0].isEnabled(row, column), moved[0].isEnabled(row, column), "west " + vertex);
            assertEquals(direct[1].isEnabled(row, column), moved[1].isEnabled(row, column), "east " + vertex);
        }
    }

    @Test
    void aVertexOnTheBoundaryHasOneOtherCopy() {
        for (TerrainEdge edge : TerrainEdge.values()) {
            for (int position = 1; position < LAST; position++) {
                int vertex = TerrainVertexTree.edgeVertex(edge, position);
                assertEquals(edge, TerrainVertexTree.edgeOf(vertex));
                assertEquals(TerrainVertexTree.edgeVertex(edge.opposite(), position), TerrainVertexTree.otherCopy(vertex));
                assertEquals(vertex, TerrainVertexTree.otherCopy(TerrainVertexTree.otherCopy(vertex)));
            }
        }
    }
}
