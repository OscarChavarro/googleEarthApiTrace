package planetdemviewer.terrain;

import java.util.List;
import planetdemviewer.model.QuadtreeNode;
import planetdemviewer.processing.DrawCommand;

/** One post-culling tile and the coarse neighbours that constrain its edges. */
public record TerrainMeshPlan(
    DrawCommand command,
    List<Neighbour> coarseNeighbours,
    TerrainSubdivisionScheme scheme,
    String variantKey
) {
    public TerrainMeshPlan {
        coarseNeighbours = List.copyOf(coarseNeighbours);
    }

    public record Neighbour(TerrainEdge fineEdge, QuadtreeNode coarseNode) {
    }
}
