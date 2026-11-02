package planetdemviewer.terrain;

/** Edge-subdivision schemes of the terrain mesh and what each one makes possible. */
public enum TerrainSubdivisionScheme {
    TRIANGLE_EDGE(new TriangleEdgeTraversal(), false, false),
    DIAMOND_SQUARE(new DiamondSquareTraversal(TerrainVertexSelection.ALL), true, false),
    /** The grid of diamond-square, with its triangles chosen per frame: see {@link AdaptiveTerrainMesh}. */
    LIND1996(new DiamondSquareTraversal(TerrainVertexSelection.ALL), false, true);

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
     * Whether the scheme can subdivide below the DEM sample spacing, which color
     * deeper than the geometry requires. No renderer uses deeper color yet: in
     * every scheme, each tile currently draws the PNG of its own level.
     */
    public boolean allowsImageryBeyondGeometryLevel() {
        return imageryBeyondGeometryLevel;
    }
}
