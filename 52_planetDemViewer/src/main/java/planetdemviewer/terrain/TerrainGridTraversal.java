package planetdemviewer.terrain;

/**
 * How one edge-subdivision scheme samples a tile and visits its triangles.
 * Rendered coordinates number the mesh vertices from the north-west corner;
 * stored coordinates index the 258-sample DEM raster.
 */
public interface TerrainGridTraversal {
    /** Receives one triangle in rendered coordinates, counter-clockwise seen from above. */
    @FunctionalInterface
    interface TriangleSink {
        void triangle(int row0, int column0, int row1, int column1, int row2, int column2);
    }

    int verticesPerSide();

    /** The DEM sample used by a rendered row or column. */
    int storedCoordinate(int renderedCoordinate);

    /** Grid intervals from the tile's west or north edge to a stored sample; outside samples fall out of range. */
    int intervalsFromTileEdge(int storedCoordinate);

    void traverse(TriangleSink sink);
}
