package planetdemviewer.render.terrain;

/** Edge-subdivision schemes of the terrain mesh and what each one makes possible. */
public enum TerrainSubdivisionScheme {
    TRIANGLE_EDGE(new TriangleEdgeTraversal(), false, false),
    DIAMOND_SQUARE(new DiamondSquareTraversal(TerrainVertexSelection.ALL), true, false),
    /** The grid of diamond-square, with its triangles chosen per frame: see {@link AdaptiveTerrainMesh}. */
    LIND1996(new DiamondSquareTraversal(TerrainVertexSelection.ALL), true, true);

    private final TerrainGridTraversal traversal;
    private final boolean imageryBeyondGeometryLevel;
    private final boolean viewDependent;

    TerrainSubdivisionScheme(TerrainGridTraversal traversal, boolean imageryBeyondGeometryLevel,
                             boolean viewDependent) {
        this.traversal = traversal;
        this.imageryBeyondGeometryLevel = imageryBeyondGeometryLevel;
        this.viewDependent = viewDependent;
    }

    /** Whether the triangles of a mesh change with the view, while its vertices stay. */
    public boolean isViewDependent() {
        return viewDependent;
    }

    public TerrainGridTraversal traversal() {
        return traversal;
    }

    /**
     * Whether color and geometry can come from different levels of their quadtrees. Deeper color
     * needs squares of the grid that are exact sets of triangles: see {@link QuadrantTerrainMesh}.
     * The row-by-row mesh has none, because its 255 intervals per side cannot be halved.
     */
    public boolean allowsImageryBeyondGeometryLevel() {
        return imageryBeyondGeometryLevel;
    }
}
