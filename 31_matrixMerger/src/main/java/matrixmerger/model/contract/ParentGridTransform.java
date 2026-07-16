package matrixmerger.model.contract;

/**
 * Rigid placement of a child matrix in the parent's grid refined by one
 * quadtree level.  For a child cell (i,j), (i + rowOffset, j + colOffset)
 * is its coordinate in that refined parent-local grid.
 */
public record ParentGridTransform(int rowOffset, int colOffset) {
}
