package planetdemviewer.terrain;

import vsdk.toolkit.environment.geometry.surface.TriangleMesh;

/**
 * A tile mesh whose vertices are fixed and whose triangles follow the vertex-based
 * simplification. {@link #getTriangleIndexes()} is the triangulation of the last pass,
 * so that a backend and the picking use the triangles in view.
 *
 * <p>References:</p>
 * <ul>
 * <li>[LIND1996] Lindstrom, P. et al. "Real-Time, Continuous Level of Detail
 * Rendering of Height Fields", SIGGRAPH 1996.</li>
 * </ul>
 *
 * <p>Section [LIND1996].5.1 renders a pre-order descent of the vertex trees, stopping at
 * a false enabled attribute. The descent is {@link DiamondSquareTraversal}. Its triangle
 * mesh primitive and the swaps of the graphics buffer are not used, because a backend
 * draws indexed triangles.</p>
 */
public final class AdaptiveTerrainMesh extends TriangleMesh {
    private boolean[] validVertices;
    private VertexBasedSimplification simplification;
    private DiamondSquareTraversal traversal;
    private int[] triangles;
    private boolean uploaded;

    /** Call this after the mesh has its vertices and all its triangles. */
    void prepareSimplification(boolean[] validVertices, double levelJump) {
        double[] positions = getVertexPositions();
        double[] meshZ = new double[TerrainVertexTree.VERTEX_COUNT];
        for (int vertex = 0; vertex < meshZ.length; vertex++) {
            meshZ[vertex] = positions[vertex * 3 + 2];
        }
        this.validVertices = validVertices;
        simplification = new VertexBasedSimplification(new VertexDeltaTable(meshZ, validVertices, levelJump), positions);
        traversal = new DiamondSquareTraversal(simplification);
    }

    /** Updates the triangles for this view, for a block that shares no vertices. */
    public void simplify(ProjectedDeltaCriterion criterion, TerrainMeshPlacement placement) {
        updateVertexSelection(criterion, placement);
        selectTriangles();
    }

    /** Updates the vertices for this view, which can also change the vertices of adjacent blocks. */
    public void updateVertexSelection(ProjectedDeltaCriterion criterion, TerrainMeshPlacement placement) {
        simplification.simplify(criterion, placement);
    }

    /** Call this once every block of the frame has updated its vertices. */
    public void selectTriangles() {
        if (simplification.takeEnabledSetChanged() || triangles == null) {
            triangles = selectedTriangles();
            uploaded = false;
        }
    }

    /** See {@link VertexBasedSimplification#constrainEdge}. */
    void constrainEdge(TerrainEdge edge, boolean locked, AdaptiveTerrainMesh adjacent) {
        simplification.constrainEdge(edge, locked, adjacent == null ? null : adjacent.simplification);
    }

    /** Goes back to every triangle, for a camera that has no criterion. */
    public void useAllTriangles() {
        if (triangles != null) {
            triangles = null;
            uploaded = false;
        }
    }

    private int[] selectedTriangles() {
        int[] count = new int[1];
        traversal.traverse((r0, c0, r1, c1, r2, c2) -> {
            if (valid(r0, c0) && valid(r1, c1) && valid(r2, c2)) {
                count[0]++;
            }
        });
        int[] result = new int[count[0] * 3];
        int[] out = new int[1];
        traversal.traverse((r0, c0, r1, c1, r2, c2) -> {
            if (valid(r0, c0) && valid(r1, c1) && valid(r2, c2)) {
                result[out[0]++] = TerrainVertexTree.vertex(r0, c0);
                result[out[0]++] = TerrainVertexTree.vertex(r1, c1);
                result[out[0]++] = TerrainVertexTree.vertex(r2, c2);
            }
        });
        return result;
    }

    private boolean valid(int row, int column) {
        return validVertices[TerrainVertexTree.vertex(row, column)];
    }

    /** Returns the triangles a backend must upload, or null when it already has them. */
    public int[] takeTrianglesToUpload() {
        if (uploaded) {
            return null;
        }
        uploaded = true;
        return getTriangleIndexes();
    }

    @Override
    public int[] getTriangleIndexes() {
        return triangles != null ? triangles : super.getTriangleIndexes();
    }

    @Override
    public int getNumTriangles() {
        return getTriangleIndexes().length / 3;
    }

    /** The triangles before any simplification: n1 in section [LIND1996].7, for this block. */
    public int fullTriangleCount() {
        return super.getTriangleIndexes().length / 3;
    }

    public int deltaProjections() {
        return simplification.deltaProjections();
    }

    public boolean couldUseLowerResolutionBlock() {
        return simplification.couldUseLowerResolutionBlock();
    }
}
