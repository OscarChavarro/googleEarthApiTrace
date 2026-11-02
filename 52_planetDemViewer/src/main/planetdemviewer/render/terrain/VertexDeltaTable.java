package planetdemviewer.render.terrain;

import java.util.Arrays;
import planetdemviewer.config.Configuration;
import planetdemviewer.model.DemTile;

/**
 * The delta value of each vertex of a block, in mesh z units, and the base vertices
 * sorted by it. The values do not depend on the view, the vertical exaggeration or
 * the placement of the block, so they are computed once.
 *
 * <p>References:</p>
 * <ul>
 * <li>[LIND1996] Lindstrom, P. et al. "Real-Time, Continuous Level of Detail
 * Rendering of Height Fields", SIGGRAPH 1996.</li>
 * </ul>
 *
 * <p>Section [LIND1996].6 stores each delta in 8 bits and uses 16-bit indices, for
 * blocks of up to 2^7+1 vertices per side. This class keeps plain floats and ints: a
 * block here has 2^8+1 per side, and its heights are not integer metres.</p>
 *
 * <p>NoData is not treated by the paper. A delta is infinite when a hypotenuse mixes
 * valid and NoData samples, which keeps the exact outline of the data: a coalesced
 * triangle can neither cover a NoData sample nor be dropped with valid samples in it.</p>
 */
final class VertexDeltaTable {
    private final float[] deltas = new float[TerrainVertexTree.VERTEX_COUNT];
    private final int[] verticesByDelta;
    private final int finiteCount;
    private final double deltaMax;

    /**
     * @param meshZ the height of each vertex
     * @param valid whether each vertex has data
     * @param levelJump largest height difference to the parent block, or 0 when it is unknown
     */
    VertexDeltaTable(double[] meshZ, boolean[] valid, double levelJump) {
        TerrainVertexTree tree = TerrainVertexTree.INSTANCE;
        int baseCount = 0;
        int finite = 0;
        double largest = levelJump;
        long[] sortKeys = new long[TerrainVertexTree.VERTEX_COUNT];
        for (int vertex = 0; vertex < TerrainVertexTree.VERTEX_COUNT; vertex++) {
            if (!tree.isBase(vertex)) {
                continue;
            }
            float delta = (float) delta(meshZ, valid, vertex, tree);
            deltas[vertex] = delta;
            // Non-negative floats sort as their bits, and infinity comes last.
            sortKeys[baseCount++] = ((long) Float.floatToRawIntBits(delta) << 32) | vertex;
            if (!Float.isInfinite(delta)) {
                finite++;
                if (tree.isLowestLevel(vertex)) {
                    largest = Math.max(largest, delta);
                }
            }
        }
        Arrays.sort(sortKeys, 0, baseCount);
        verticesByDelta = new int[baseCount];
        for (int i = 0; i < baseCount; i++) {
            verticesByDelta[i] = (int) sortKeys[i];
        }
        finiteCount = finite;
        deltaMax = largest;
    }

    /** See figure [LIND1996].3: delta(B) = |Bz - (Az + Cz) / 2|. */
    private static double delta(double[] meshZ, boolean[] valid, int vertex, TerrainVertexTree tree) {
        int a = tree.hypotenuseEnd(vertex, 0);
        int c = tree.hypotenuseEnd(vertex, 1);
        if (valid[vertex] != valid[a] || valid[vertex] != valid[c]) {
            return Double.POSITIVE_INFINITY;
        }
        return valid[vertex] ? Math.abs(meshZ[vertex] - (meshZ[a] + meshZ[c]) * 0.5) : 0.0;
    }

    /**
     * Section [LIND1996].4 obtains a parent block by discarding every other row and column
     * of its children. A filtered pyramid does not satisfy this, so going to the parent
     * also moves the vertices they share, by up to the value returned here, in mesh z
     * units. The shared vertices are the ones the renderer places at the same position.
     *
     * @param quadrantDigit the last quadkey digit of the child: 0 SW, 1 SE, 2 NE, 3 NW
     */
    static double levelJump(DemTile parent, DemTile child, char quadrantDigit) {
        int half = DemTile.CORE_SIZE / 2;
        int firstRow = quadrantDigit == '0' || quadrantDigit == '1' ? half : 0;
        int firstColumn = quadrantDigit == '1' || quadrantDigit == '2' ? half : 0;
        double largest = 0.0;
        for (int row = 0; row <= half; row++) {
            for (int column = 0; column <= half; column++) {
                double difference = Math.abs(parent.elevationMetres(firstRow + row, firstColumn + column)
                    - child.elevationMetres(row * 2, column * 2));
                // NaN, which is NoData, never compares as larger.
                if (difference > largest) {
                    largest = difference;
                }
            }
        }
        return largest / Configuration.WORLD_WIDTH_METRES;
    }

    float delta(int vertex) {
        return deltas[vertex];
    }

    /** The number of base vertices. */
    int orderedCount() {
        return verticesByDelta.length;
    }

    int orderedVertex(int position) {
        return verticesByDelta[position];
    }

    /** The first position whose delta is larger than the given one; infinite deltas are never passed. */
    int positionAfter(double delta) {
        int low = 0;
        int high = finiteCount;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (deltas[verticesByDelta[middle]] <= delta) {
                low = middle + 1;
            }
            else {
                high = middle;
            }
        }
        return low;
    }

    /** The first position with an infinite delta. */
    int finiteCount() {
        return finiteCount;
    }

    /**
     * Section [LIND1996].4.2: the largest finite delta of the lowest level vertices,
     * extended with the jump to the parent block when it was known.
     */
    double deltaMax() {
        return deltaMax;
    }
}
