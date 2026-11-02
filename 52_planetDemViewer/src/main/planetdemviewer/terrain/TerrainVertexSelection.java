package planetdemviewer.terrain;

/**
 * Decides whether a base vertex (the midpoint of a triangle's hypotenuse) is in
 * the mesh, which is the "enabled" vertex attribute of [LIND1996]. An adaptive
 * implementation must also enable the vertices each enabled vertex depends on
 * (section [LIND1996].4.3), or the resulting mesh will have T-junctions.
 *
 * <p>References:</p>
 * <ul>
 * <li>[LIND1996] Lindstrom, P. et al. "Real-Time, Continuous Level of Detail
 * Rendering of Height Fields", SIGGRAPH 1996.</li>
 * </ul>
 */
@FunctionalInterface
public interface TerrainVertexSelection {
    TerrainVertexSelection ALL = (row, column) -> true;

    boolean isEnabled(int row, int column);
}
