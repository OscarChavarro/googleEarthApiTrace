package planetviewer.processing;

import planetviewer.model.QuadtreeNode;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;

/**
 * One quadtree node selected for drawing, with its already-scaled and
 * placed world-space corners (south-west, south-east, north-east,
 * north-west order, matching the tile's own UV corners).
 */
public record DrawCommand(QuadtreeNode node, Vector3Dd[] corners) {
}
