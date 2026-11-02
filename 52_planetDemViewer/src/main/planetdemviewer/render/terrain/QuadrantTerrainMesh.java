package planetdemviewer.render.terrain;

import java.util.List;
import vsdk.toolkit.environment.geometry.surface.TriangleMesh;

/**
 * A tile mesh of the diamond-square grid whose triangles can be grouped by squares of that
 * grid, so that each square is drawn with its own texture. The vertices never change.
 *
 * <p>References:</p>
 * <ul>
 * <li>[MILL1986a] Miller, G. S. P. "The Definition and Rendering of Terrain Maps",
 * SIGGRAPH 1986.</li>
 * <li>[LERB2010] Lerbour, R., Marvie, J.-E. and Gautron, P. "Adaptive Real-Time Rendering of
 * Planetary Terrains", WSCG 2010.</li>
 * </ul>
 *
 * <p>This is how section [LERB2010].4.2 maps several texture blocks onto one geometry block,
 * without its multiple textures in one shader. It relies on the recursive bisection of figure
 * [MILL1986a].2: two bisections of a tile's four triangles leave its four quadrants, each with
 * the same four triangles around its center, so a quadrant is an exact set of triangles. The
 * row-by-row mesh has no such squares: its 255 intervals per side cannot be halved.</p>
 */
public class QuadrantTerrainMesh extends TriangleMesh {
    private static final DiamondSquareTraversal ALL_TRIANGLES = new DiamondSquareTraversal(TerrainVertexSelection.ALL);
    private static final List<GridQuadrant> WHOLE_TILE = List.of(GridQuadrant.WHOLE_TILE);

    private boolean[] validVertices;
    private List<GridQuadrant> quadrants = WHOLE_TILE;
    private boolean quadrantsChanged;
    /** Null while the mesh has the triangles it was generated with, in traversal order. */
    private int[] triangles;
    /** Where the indices of each quadrant start, and where the last one ends. */
    private int[] quadrantStarts;
    /** The backend starts with the triangles the mesh was generated with. */
    private boolean uploaded = true;

    /** Call this after the mesh has its vertices and all its triangles. */
    void prepareQuadrants(boolean[] validVertices) {
        this.validVertices = validVertices;
    }

    /** Groups the triangles by these squares, which must not overlap. Only their triangles remain. */
    public final void useQuadrants(List<GridQuadrant> newQuadrants) {
        if (!newQuadrants.equals(quadrants)) {
            quadrantsReplaced(quadrants, newQuadrants);
            quadrants = List.copyOf(newQuadrants);
            quadrantsChanged = true;
        }
    }

    /** What a subclass must do so that the triangles of the new squares exist. */
    void quadrantsReplaced(List<GridQuadrant> previous, List<GridQuadrant> current) {
    }

    /** Applies the squares to a mesh that has all its triangles. */
    public final void applyQuadrants() {
        if (quadrantsChanged) {
            selectAllTriangles();
        }
    }

    final void selectAllTriangles() {
        selectTriangles(ALL_TRIANGLES);
    }

    final boolean quadrantsChanged() {
        return quadrantsChanged;
    }

    final boolean hasSelectedTriangles() {
        return triangles != null;
    }

    private boolean isWholeTile() {
        return quadrants.size() == 1 && quadrants.get(0).isWholeTile();
    }

    /** Replaces the triangles by those of the quadrants, for this selection of vertices. */
    final void selectTriangles(DiamondSquareTraversal traversal) {
        quadrantsChanged = false;
        uploaded = false;
        if (traversal == ALL_TRIANGLES && isWholeTile()) {
            triangles = null;
            quadrantStarts = null;
            return;
        }
        int[] count = new int[1];
        for (GridQuadrant quadrant : quadrants) {
            traversal.traverseQuadrant(quadrant, (r0, c0, r1, c1, r2, c2) -> {
                if (valid(r0, c0) && valid(r1, c1) && valid(r2, c2)) {
                    count[0]++;
                }
            });
        }
        int[] result = new int[count[0] * 3];
        int[] starts = new int[quadrants.size() + 1];
        int[] out = new int[1];
        for (int i = 0; i < quadrants.size(); i++) {
            traversal.traverseQuadrant(quadrants.get(i), (r0, c0, r1, c1, r2, c2) -> {
                if (valid(r0, c0) && valid(r1, c1) && valid(r2, c2)) {
                    result[out[0]++] = TerrainVertexTree.vertex(r0, c0);
                    result[out[0]++] = TerrainVertexTree.vertex(r1, c1);
                    result[out[0]++] = TerrainVertexTree.vertex(r2, c2);
                }
            });
            starts[i + 1] = out[0];
        }
        triangles = result;
        quadrantStarts = starts;
    }

    private boolean valid(int row, int column) {
        return validVertices[TerrainVertexTree.vertex(row, column)];
    }

    /** The first index of a quadrant in {@link #getTriangleIndexes()}. */
    public final int firstIndex(int quadrant) {
        return quadrantStarts == null ? 0 : quadrantStarts[quadrant];
    }

    public final int indexCount(int quadrant) {
        return quadrantStarts == null ? getTriangleIndexes().length
            : quadrantStarts[quadrant + 1] - quadrantStarts[quadrant];
    }

    /** Returns the triangles a backend must upload, or null when it already has them. */
    public final int[] takeTrianglesToUpload() {
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

    /** The triangles of the whole tile, before any selection. */
    public final int fullTriangleCount() {
        return super.getTriangleIndexes().length / 3;
    }
}
