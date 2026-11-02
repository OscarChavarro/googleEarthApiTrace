package planetdemviewer.terrain;

/** Edge-subdivision schemes of the terrain mesh and what each one makes possible. */
public enum TerrainSubdivisionScheme {
    TRIANGLE_EDGE(new TriangleEdgeTraversal(), false),
    DIAMOND_SQUARE(new DiamondSquareTraversal(TerrainVertexSelection.ALL), true);

    private final TerrainGridTraversal traversal;
    private final boolean imageryBeyondGeometryLevel;

    TerrainSubdivisionScheme(TerrainGridTraversal traversal, boolean imageryBeyondGeometryLevel) {
        this.traversal = traversal;
        this.imageryBeyondGeometryLevel = imageryBeyondGeometryLevel;
    }

    public TerrainGridTraversal traversal() {
        return traversal;
    }

    /**
     * Whether the scheme can subdivide below the DEM sample spacing, which color
     * deeper than the geometry requires. No renderer uses deeper color yet: in
     * every scheme, each tile currently draws the PNG of its own level.
     */
    public boolean allowsImageryBeyondGeometryLevel() {
        return imageryBeyondGeometryLevel;
    }
}
