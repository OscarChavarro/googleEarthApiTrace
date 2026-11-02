package planetdemviewer.render.terrain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 *
 * <p>A coarse triangle may cross the squares of {@link QuadrantTerrainMesh}. The center of each
 * square is therefore kept enabled, which is not in the paper: with the dependencies of section
 * [LIND1996].4.3 this leaves the square's four triangles in any triangulation, and the mesh
 * stays free of T-junctions. It adds detail only where a texture block requires it.</p>
 */
public final class AdaptiveTerrainMesh extends QuadrantTerrainMesh {
    private VertexBasedSimplification simplification;
    private DiamondSquareTraversal traversal;
    /** The current triangles ignore the simplification, for a camera that has no criterion. */
    private boolean showingAllTriangles;

    /** Call this after the mesh has its vertices and all its triangles. */
    void prepareSimplification(boolean[] validVertices, double levelJump) {
        double[] positions = getVertexPositions();
        double[] meshZ = new double[TerrainVertexTree.VERTEX_COUNT];
        for (int vertex = 0; vertex < meshZ.length; vertex++) {
            meshZ[vertex] = positions[vertex * 3 + 2];
        }
        prepareQuadrants(validVertices);
        simplification = new VertexBasedSimplification(new VertexDeltaTable(meshZ, validVertices, levelJump), positions);
        traversal = new DiamondSquareTraversal(simplification);
    }

    @Override
    void quadrantsReplaced(List<GridQuadrant> previous, List<GridQuadrant> current) {
        Set<Integer> centers = new HashSet<>();
        for (GridQuadrant quadrant : current) {
            if (!quadrant.isWholeTile()) {
                centers.add(center(quadrant));
                simplification.requireVertex(center(quadrant), true);
            }
        }
        for (GridQuadrant quadrant : previous) {
            if (!quadrant.isWholeTile() && !centers.contains(center(quadrant))) {
                simplification.requireVertex(center(quadrant), false);
            }
        }
    }

    private static int center(GridQuadrant quadrant) {
        return TerrainVertexTree.vertex(quadrant.row() + quadrant.side() / 2, quadrant.column() + quadrant.side() / 2);
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
        if (simplification.takeEnabledSetChanged() || !hasSelectedTriangles() || quadrantsChanged()
            || showingAllTriangles) {
            showingAllTriangles = false;
            selectTriangles(traversal);
        }
    }

    /** See {@link VertexBasedSimplification#constrainEdge}. */
    void constrainEdge(TerrainEdge edge, boolean locked, AdaptiveTerrainMesh adjacent) {
        simplification.constrainEdge(edge, locked, adjacent == null ? null : adjacent.simplification);
    }

    /** Goes back to every triangle, for a camera that has no criterion. */
    public void useAllTriangles() {
        if (!showingAllTriangles || quadrantsChanged()) {
            showingAllTriangles = true;
            selectAllTriangles();
        }
    }

    public int deltaProjections() {
        return simplification.deltaProjections();
    }

    public boolean couldUseLowerResolutionBlock() {
        return simplification.couldUseLowerResolutionBlock();
    }
}
