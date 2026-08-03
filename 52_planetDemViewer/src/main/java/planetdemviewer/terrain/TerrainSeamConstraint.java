package planetdemviewer.terrain;

import planetdemviewer.model.DemTile;
import planetdemviewer.model.QuadtreeNode;

/** Coarse height field to which one edge of a finer tile must be snapped. */
public record TerrainSeamConstraint(
    TerrainEdge fineEdge,
    QuadtreeNode coarseNode,
    DemTile coarseElevations
) {
    public TerrainSeamConstraint {
        if (fineEdge == null || coarseNode == null || coarseElevations == null) {
            throw new IllegalArgumentException("A complete terrain seam constraint is required");
        }
    }
}
