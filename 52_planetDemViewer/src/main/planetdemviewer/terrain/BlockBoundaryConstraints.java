package planetdemviewer.terrain;

import java.util.List;
import planetdemviewer.model.QuadtreeNode;

/**
 * Decides, for the blocks of a frame, how each edge of each block is simplified.
 *
 * <p>References:</p>
 * <ul>
 * <li>[LIND1996] Lindstrom, P. et al. "Real-Time, Continuous Level of Detail
 * Rendering of Height Fields", SIGGRAPH 1996.</li>
 * </ul>
 *
 * <p>Section [LIND1996].4.3 requires dependencies that cross block boundaries. When they are
 * allowed here, blocks of the same level share the vertices of their common edge. There is no
 * single level for that edge: each vertex is enabled if either block needs it. An edge with
 * no block on the other side has only the dependencies inside its block, which is the
 * exception of footnote 3 in that section for the edges of the dataset, and here also applies
 * to a neighbour that is not drawn. An edge against another level stays locked, because seam
 * stitching relies on all its samples.</p>
 */
public final class BlockBoundaryConstraints {
    /** A block drawn in this frame. */
    public record Block(QuadtreeNode node, AdaptiveTerrainMesh mesh) {
    }

    private BlockBoundaryConstraints() {
    }

    /**
     * @param dependenciesCrossBlocks false locks the boundary of every block, so each one is
     * independent of the others, at the cost of keeping all the samples of its edges
     */
    public static void apply(List<Block> blocks, boolean dependenciesCrossBlocks) {
        TerrainEdge[] edges = TerrainEdge.values();
        for (Block block : blocks) {
            boolean[] locked = new boolean[edges.length];
            AdaptiveTerrainMesh[] adjacent = new AdaptiveTerrainMesh[edges.length];
            if (dependenciesCrossBlocks) {
                for (Block other : blocks) {
                    TerrainEdge edge = other == block ? null : TerrainSeamStitcher.touchingEdge(block.node(), other.node());
                    if (edge != null) {
                        if (other.node().getDepth() == block.node().getDepth()) {
                            adjacent[edge.ordinal()] = other.mesh();
                        }
                        else {
                            locked[edge.ordinal()] = true;
                        }
                    }
                }
            }
            else {
                java.util.Arrays.fill(locked, true);
            }
            for (TerrainEdge edge : edges) {
                block.mesh().constrainEdge(edge, locked[edge.ordinal()], adjacent[edge.ordinal()]);
            }
        }
    }
}
