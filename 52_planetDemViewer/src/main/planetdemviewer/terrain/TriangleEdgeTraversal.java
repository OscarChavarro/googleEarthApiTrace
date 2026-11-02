package planetdemviewer.terrain;

import planetdemviewer.model.DemTile;

/**
 * Triangle edge subdivision (figure [MILL1986a].1) at full resolution: a row
 * by row walk that splits every cell along the same diagonal.
 *
 * <p>References:</p>
 * <ul>
 * <li>[MILL1986a] Miller, G. S. P. "The Definition and Rendering of Terrain Maps",
 * SIGGRAPH 1986.</li>
 * </ul>
 *
 * <p>The 258 raster is [west/north halo, 256 core samples, east/south halo].
 * The shared edge is owned by the west/north halo of the tile on its
 * east/south side, so it is the same sample as the core edge of the other
 * tile. Keeping this mesh at 256 vertices requires omitting core index 1 on
 * those two edges rather than rendering two unequal samples at one edge.</p>
 */
final class TriangleEdgeTraversal implements TerrainGridTraversal {
    private static final int SIDE = DemTile.CORE_SIZE;

    @Override
    public int verticesPerSide() {
        return SIDE;
    }

    @Override
    public int storedCoordinate(int renderedCoordinate) {
        return renderedCoordinate == 0 ? 0 : renderedCoordinate + 1;
    }

    @Override
    public int intervalsFromTileEdge(int storedCoordinate) {
        return storedCoordinate - 1;
    }

    @Override
    public void traverse(TriangleSink sink) {
        for (int row = 0; row < SIDE - 1; row++) {
            for (int column = 0; column < SIDE - 1; column++) {
                sink.triangle(row, column, row + 1, column, row, column + 1);
                sink.triangle(row, column + 1, row + 1, column, row + 1, column + 1);
            }
        }
    }
}
