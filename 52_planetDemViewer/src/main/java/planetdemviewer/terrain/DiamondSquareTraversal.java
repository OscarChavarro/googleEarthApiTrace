package planetdemviewer.terrain;

import planetdemviewer.model.DemTile;

/**
 * Diamond-square subdivision (Miller 1986, figure 2) as a recursive walk over
 * the 2^8+1 samples between stored coordinates 0 and 256. The last sample of a
 * tile is the first sample of its east or south neighbour, so all of them can
 * be rendered without skipping any.
 *
 * <p>The walk starts from the tile's four quadrants, each with its apex on the
 * tile center, and bisects each triangle at the midpoint of its hypotenuse.
 * That is the triangulation and quadrant layout of Lindstrom et al. (1996,
 * figure 2), so their algorithm only has to supply a {@link TerrainVertexSelection}.</p>
 */
public final class DiamondSquareTraversal implements TerrainGridTraversal {
    private static final int LAST = DemTile.CORE_SIZE;
    private static final int CENTER = LAST / 2;

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
        bisect(CENTER, CENTER, 0, 0, LAST, 0, sink);
        bisect(CENTER, CENTER, LAST, 0, LAST, LAST, sink);
        bisect(CENTER, CENTER, LAST, LAST, 0, LAST, sink);
        bisect(CENTER, CENTER, 0, LAST, 0, 0, sink);
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
