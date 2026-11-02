package planetdemviewer.render.terrain;

import planetdemviewer.model.DemTile;

/**
 * Diamond-square subdivision as a recursive walk over the 2^8+1 samples between stored coordinates 0 and 256. The last sample of a
 * tile is the first sample of its east or south neighbour, so all of them can
 * be rendered without skipping any.
 *
 * <p>References:</p>
 * <ul>
 * <li>[MILL1986a] Miller, G. S. P. "The Definition and Rendering of Terrain Maps",
 * SIGGRAPH 1986.</li>
 * <li>[LIND1996] Lindstrom, P. et al. "Real-Time, Continuous Level of Detail
 * Rendering of Height Fields", SIGGRAPH 1996.</li>
 * </ul>
 *
 * <p>This is the subdivision of figure [MILL1986a].2 at the DEM spacing; subdividing
 * below that spacing is not implemented. The walk starts from the tile's four
 * quadrants, each with its apex on the tile center, and bisects each triangle at the
 * midpoint of its hypotenuse. That is the triangulation and quadrant layout of figure
 * [LIND1996].2, and {@link VertexBasedSimplification} supplies the
 * {@link TerrainVertexSelection} of that algorithm.</p>
 */
public final class DiamondSquareTraversal implements TerrainGridTraversal {
    private static final int LAST = DemTile.CORE_SIZE;
    private static final int CENTER = LAST / 2;

    /** Receives a triangle that can be bisected, with the vertex that would bisect it. */
    @FunctionalInterface
    interface BisectionSink {
        void bisection(int apexRow, int apexColumn, int leftRow, int leftColumn,
                       int rightRow, int rightColumn, int baseRow, int baseColumn);
    }

    private final TerrainVertexSelection selection;

    public DiamondSquareTraversal(TerrainVertexSelection selection) {
        this.selection = selection;
    }

    @Override
    public int verticesPerSide() {
        return LAST + 1;
    }

    @Override
    public int storedCoordinate(int renderedCoordinate) {
        return renderedCoordinate;
    }

    @Override
    public int intervalsFromTileEdge(int storedCoordinate) {
        return storedCoordinate;
    }

    @Override
    public void traverse(TriangleSink sink) {
        traverseQuadrant(GridQuadrant.WHOLE_TILE, sink);
    }

    /**
     * Visits only the triangles of a square. Two bisections leave the four triangles of the tile
     * as those of its quadrants, so the walk can start from any square, as long as the selection
     * has its center and everything that depends on: see {@link AdaptiveTerrainMesh}.
     */
    void traverseQuadrant(GridQuadrant quadrant, TriangleSink sink) {
        int r0 = quadrant.row();
        int c0 = quadrant.column();
        int r1 = r0 + quadrant.side();
        int c1 = c0 + quadrant.side();
        int rowCenter = r0 + quadrant.side() / 2;
        int columnCenter = c0 + quadrant.side() / 2;
        bisect(rowCenter, columnCenter, r0, c0, r1, c0, sink);
        bisect(rowCenter, columnCenter, r1, c0, r1, c1, sink);
        bisect(rowCenter, columnCenter, r1, c1, r0, c1, sink);
        bisect(rowCenter, columnCenter, r0, c1, r0, c0, sink);
    }

    /** Visits every triangle of the complete hierarchy that has a base vertex. */
    static void visitBisections(BisectionSink sink) {
        visit(CENTER, CENTER, 0, 0, LAST, 0, sink);
        visit(CENTER, CENTER, LAST, 0, LAST, LAST, sink);
        visit(CENTER, CENTER, LAST, LAST, 0, LAST, sink);
        visit(CENTER, CENTER, 0, LAST, 0, 0, sink);
    }

    private static void visit(int apexRow, int apexColumn, int leftRow, int leftColumn,
                              int rightRow, int rightColumn, BisectionSink sink) {
        int rowSum = leftRow + rightRow;
        int columnSum = leftColumn + rightColumn;
        if ((rowSum & 1) != 0 || (columnSum & 1) != 0) {
            return;
        }
        int baseRow = rowSum / 2;
        int baseColumn = columnSum / 2;
        sink.bisection(apexRow, apexColumn, leftRow, leftColumn, rightRow, rightColumn, baseRow, baseColumn);
        visit(baseRow, baseColumn, apexRow, apexColumn, leftRow, leftColumn, sink);
        visit(baseRow, baseColumn, rightRow, rightColumn, apexRow, apexColumn, sink);
    }

    /** The apex is opposite the hypotenuse that joins the left and right vertices. */
    private void bisect(int apexRow, int apexColumn, int leftRow, int leftColumn,
                        int rightRow, int rightColumn, TriangleSink sink) {
        int rowSum = leftRow + rightRow;
        int columnSum = leftColumn + rightColumn;
        boolean hasBaseVertex = (rowSum & 1) == 0 && (columnSum & 1) == 0;
        if (!hasBaseVertex || !selection.isEnabled(rowSum / 2, columnSum / 2)) {
            sink.triangle(apexRow, apexColumn, leftRow, leftColumn, rightRow, rightColumn);
            return;
        }
        int baseRow = rowSum / 2;
        int baseColumn = columnSum / 2;
        bisect(baseRow, baseColumn, apexRow, apexColumn, leftRow, leftColumn, sink);
        bisect(baseRow, baseColumn, rightRow, rightColumn, apexRow, apexColumn, sink);
    }
}
