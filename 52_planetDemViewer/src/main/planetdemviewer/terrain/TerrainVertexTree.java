package planetdemviewer.terrain;

import java.util.Arrays;
import planetdemviewer.model.DemTile;

/**
 * The vertex trees of one block, whose topology is the same for every tile, so
 * one table serves them all. A vertex is {@code row * SIDE + column}.
 *
 * <p>References:</p>
 * <ul>
 * <li>[LIND1996] Lindstrom, P. et al. "Real-Time, Continuous Level of Detail
 * Rendering of Height Fields", SIGGRAPH 1996.</li>
 * </ul>
 *
 * <p>Section [LIND1996].4.3: each vertex occurs once per triangle pair, in the binary
 * subtrees T0 and T1, which gives it two parents (its dependents) and four children.
 * The table is built with the walk of {@link DiamondSquareTraversal}, so each base
 * vertex gets the hypotenuse that this triangulation really bisects. A vertex on the
 * block boundary has its other parent and two other children in the adjacent block;
 * this table only holds the ones inside the block, and
 * {@link VertexBasedSimplification} connects the copies of that vertex.</p>
 */
final class TerrainVertexTree {
    static final TerrainVertexTree INSTANCE = new TerrainVertexTree();
    static final int SIDE = DemTile.CORE_SIZE + 1;
    static final int VERTEX_COUNT = SIDE * SIDE;
    /** Line 1 of NOTIFY in section [LIND1996].5 calls this an invalid vertex. */
    static final int NONE = -1;
    private static final int LAST = SIDE - 1;

    private final int[] hypotenuses = new int[VERTEX_COUNT * 2];
    private final int[] parents = new int[VERTEX_COUNT * 2];
    private final byte[] branches = new byte[VERTEX_COUNT * 2];
    private final int[] children = new int[VERTEX_COUNT * 4];

    private TerrainVertexTree() {
        Arrays.fill(hypotenuses, NONE);
        Arrays.fill(parents, NONE);
        Arrays.fill(children, NONE);
        DiamondSquareTraversal.visitBisections((apexRow, apexColumn, leftRow, leftColumn, rightRow, rightColumn,
                                                baseRow, baseColumn) -> {
            int base = vertex(baseRow, baseColumn);
            hypotenuses[base * 2] = vertex(leftRow, leftColumn);
            hypotenuses[base * 2 + 1] = vertex(rightRow, rightColumn);
            link(vertex(apexRow, apexColumn), base);
        });
    }

    private void link(int parent, int child) {
        int branch = 0;
        while (children[parent * 4 + branch] != NONE) {
            branch++;
        }
        children[parent * 4 + branch] = child;
        int slot = parents[child * 2] == NONE ? 0 : 1;
        parents[child * 2 + slot] = parent;
        branches[child * 2 + slot] = (byte) branch;
    }

    static int vertex(int row, int column) {
        return row * SIDE + column;
    }

    /** One end of the hypotenuse whose midpoint is this base vertex, or NONE for a vertex that is no base. */
    int hypotenuseEnd(int vertex, int end) {
        return hypotenuses[vertex * 2 + end];
    }

    /** The block's corners and center are in every triangulation: they are the quadrants' vertices. */
    boolean isBase(int vertex) {
        return hypotenuses[vertex * 2] != NONE;
    }

    /** The dependent of the vertex in subtree T0 or T1. */
    int parent(int vertex, int tree) {
        return parents[vertex * 2 + tree];
    }

    /** The dependency flag of the parent that reflects this vertex: branch(v) in section [LIND1996].5. */
    int branch(int vertex, int tree) {
        return branches[vertex * 2 + tree];
    }

    int child(int vertex, int branch) {
        return children[vertex * 4 + branch];
    }

    boolean isOnBoundary(int vertex) {
        int row = vertex / SIDE;
        int column = vertex % SIDE;
        return row == 0 || column == 0 || row == LAST || column == LAST;
    }

    /** The vertex at a position along an edge, from west to east or from north to south. Row 0 is the north. */
    static int edgeVertex(TerrainEdge edge, int position) {
        return switch (edge) {
            case NORTH -> vertex(0, position);
            case SOUTH -> vertex(LAST, position);
            case WEST -> vertex(position, 0);
            case EAST -> vertex(position, LAST);
        };
    }

    /** The edge of a boundary vertex, which must not be a corner. */
    static TerrainEdge edgeOf(int vertex) {
        int row = vertex / SIDE;
        int column = vertex % SIDE;
        return row == 0 ? TerrainEdge.NORTH : row == LAST ? TerrainEdge.SOUTH
            : column == 0 ? TerrainEdge.WEST : TerrainEdge.EAST;
    }

    /** The same point in the block across the edge of this boundary vertex, which must not be a corner. */
    static int otherCopy(int vertex) {
        TerrainEdge edge = edgeOf(vertex);
        int position = edge == TerrainEdge.NORTH || edge == TerrainEdge.SOUTH ? vertex % SIDE : vertex / SIDE;
        return edgeVertex(edge.opposite(), position);
    }

    /**
     * The vertices that a block of the previous level lacks, because it discards every
     * other row and column: the "lowest level vertices" of section [LIND1996].4,
     * shown in figure [LIND1996].2c.
     */
    boolean isLowestLevel(int vertex) {
        return ((vertex / SIDE) & 1) != 0 || ((vertex % SIDE) & 1) != 0;
    }
}
