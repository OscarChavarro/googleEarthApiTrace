package planetdemviewer.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class VertexDeltaTableTest {
    private static boolean[] allValid() {
        boolean[] valid = new boolean[TerrainVertexTree.VERTEX_COUNT];
        Arrays.fill(valid, true);
        return valid;
    }

    @Test
    void aFlatOrPlanarBlockHasNoDeltas() {
        double[] z = new double[TerrainVertexTree.VERTEX_COUNT];
        for (int vertex = 0; vertex < z.length; vertex++) {
            z[vertex] = 0.25 * (vertex / TerrainVertexTree.SIDE) + 0.5 * (vertex % TerrainVertexTree.SIDE);
        }
        VertexDeltaTable table = new VertexDeltaTable(z, allValid(), 0.0);
        assertEquals(TerrainVertexTree.VERTEX_COUNT - 5, table.orderedCount());
        assertEquals(0.0, table.deltaMax());
        assertEquals(table.orderedCount(), table.positionAfter(0.0));
    }

    /** See figure [LIND1996].3: |Bz - (Az + Cz) / 2|. */
    @Test
    void theDeltaIsTheDistanceToTheMidpointOfTheHypotenuse() {
        double[] z = new double[TerrainVertexTree.VERTEX_COUNT];
        int vertex = TerrainVertexTree.vertex(1, 1);
        z[vertex] = 4.0;
        VertexDeltaTable table = new VertexDeltaTable(z, allValid(), 0.0);
        assertEquals(4.0f, table.delta(vertex));
        assertEquals(4.0, table.deltaMax());

        // A base with that height at one end of its hypotenuse sees half of it.
        double[] end = new double[TerrainVertexTree.VERTEX_COUNT];
        int high = TerrainVertexTree.vertex(4, 4);
        end[high] = 4.0;
        assertEquals(2.0f, new VertexDeltaTable(end, allValid(), 0.0)
            .delta(neighbourUsing(TerrainVertexTree.INSTANCE, high)));
    }

    private static int neighbourUsing(TerrainVertexTree tree, int end) {
        for (int candidate = 0; candidate < TerrainVertexTree.VERTEX_COUNT; candidate++) {
            if (tree.isBase(candidate)
                && (tree.hypotenuseEnd(candidate, 0) == end || tree.hypotenuseEnd(candidate, 1) == end)) {
                return candidate;
            }
        }
        throw new AssertionError();
    }

    @Test
    void verticesAreSortedByDeltaAndPositionsFindThem() {
        Random random = new Random(1);
        double[] z = new double[TerrainVertexTree.VERTEX_COUNT];
        for (int vertex = 0; vertex < z.length; vertex++) {
            z[vertex] = random.nextDouble();
        }
        VertexDeltaTable table = new VertexDeltaTable(z, allValid(), 0.0);
        for (int position = 1; position < table.orderedCount(); position++) {
            assertTrue(table.delta(table.orderedVertex(position - 1)) <= table.delta(table.orderedVertex(position)));
        }
        int after = table.positionAfter(0.25);
        assertTrue(table.delta(table.orderedVertex(after - 1)) <= 0.25);
        assertTrue(table.delta(table.orderedVertex(after)) > 0.25);
    }

    @Test
    void aHypotenuseThatMixesNoDataHasAnInfiniteDelta() {
        boolean[] valid = allValid();
        int noData = TerrainVertexTree.vertex(100, 100);
        valid[noData] = false;
        VertexDeltaTable table = new VertexDeltaTable(new double[TerrainVertexTree.VERTEX_COUNT], valid, 0.0);
        assertTrue(Float.isInfinite(table.delta(noData)));
        assertTrue(table.finiteCount() < table.orderedCount());
        assertEquals(table.finiteCount(), table.positionAfter(Double.MAX_VALUE));
        assertEquals(0.0, table.deltaMax());
    }

    @Test
    void aBaseWithoutAnyDataHasNoDelta() {
        boolean[] valid = new boolean[TerrainVertexTree.VERTEX_COUNT];
        VertexDeltaTable table = new VertexDeltaTable(new double[TerrainVertexTree.VERTEX_COUNT], valid, 0.0);
        assertEquals(table.orderedCount(), table.finiteCount());
    }

    @Test
    void theLevelJumpExtendsDeltaMax() {
        assertEquals(0.75, new VertexDeltaTable(new double[TerrainVertexTree.VERTEX_COUNT], allValid(), 0.75).deltaMax());
    }
}
