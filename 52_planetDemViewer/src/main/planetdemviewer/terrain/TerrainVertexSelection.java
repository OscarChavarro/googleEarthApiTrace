package planetdemviewer.terrain;

/**
 * Decides whether a base vertex (the midpoint of a triangle's hypotenuse) is in
 * the mesh, which is Lindstrom et al.'s (1996) "enabled" vertex attribute. An
 * adaptive implementation must also enable the vertices each enabled vertex
 * depends on (their section 4.3), or the resulting mesh will have T-junctions.
 */
@FunctionalInterface
public interface TerrainVertexSelection {
    TerrainVertexSelection ALL = (row, column) -> true;

    boolean isEnabled(int row, int column);
}
