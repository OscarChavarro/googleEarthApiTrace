package planetdemviewer.render.terrain;

import java.util.ArrayList;
import java.util.List;
import planetdemviewer.io.imagery.ImageryBlockLink;
import planetdemviewer.io.imagery.ImageryBlockLinks;
import planetdemviewer.model.QuadtreeNode;
import planetdemviewer.processing.QuadtreeDrawPlanner;
import planetdemviewer.spatial.TileAddress;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.camera.Camera;

/**
 * Links an elevation block of the frame to the blocks of the independent imagery tree that
 * color it.
 *
 * <p>References:</p>
 * <ul>
 * <li>[LERB2010] Lerbour, R., Marvie, J.-E. and Gautron, P. "Adaptive Real-Time Rendering of
 * Planetary Terrains", WSCG 2010.</li>
 * </ul>
 *
 * <p>Both directions of the mismatch between the trees are treated. An imagery tree shallower
 * than the elevation tree uses the recursive query of section [LERB2010].4.1, here over the
 * ancestors of the imagery block. An imagery tree deeper than the elevation tree loosens the two
 * constraints of that section as section [LERB2010].4.2 does, with several texture blocks on
 * one geometry block.</p>
 *
 * <p>Departures from the paper:</p>
 * <ul>
 * <li>The paper binds the texture blocks in one fragment shader. Here the triangles of the
 * geometry block are partitioned by {@link QuadrantTerrainMesh}, and each part is one draw with
 * one texture. Its parts must be squares of the grid, which limits the imagery to
 * {@link #SMALLEST_QUADRANT_SIDE} grid intervals: 7 levels below the elevation block.</li>
 * <li>Links are not stored in the blocks: the shared quadkey is the link, and only whether the
 * imagery block exists is looked up.</li>
 * <li>Importance is reused as section [LERB2010].4.2 says, with the coverage test and thresholds
 * of the elevation blocks. A tile whose elevation block can still split is refined by
 * splitting it, so its imagery only splits at the elevation leaves.</li>
 * <li>The split and merge queues of section [LERB2010].4.1 do not apply, because the pyramid
 * is local and a block is requested by the frame that wants it. The mipmaps and filtering of
 * sections [LERB2010].4.2 and 4.3 are not implemented.</li>
 * </ul>
 */
final class LinkedImageryBlocks {
    /** A square of two intervals is the smallest with a center: see {@link QuadrantTerrainMesh}. */
    static final int SMALLEST_QUADRANT_SIDE = 2;

    private final QuadtreeDrawPlanner.LodState lodState = new QuadtreeDrawPlanner.LodState();

    /**
     * The blocks that color the visible part of a tile, which partition that part.
     *
     * @param minZ the lowest elevation of the tile, in mesh units
     * @param maxZ the highest elevation of the tile, in mesh units
     */
    List<ImageryBlock> select(QuadtreeNode tile, ImageryBlockLinks links, Camera camera,
                              TerrainMeshPlacement placement, double minZ, double maxZ) {
        List<ImageryBlock> blocks = new ArrayList<>();
        TileAddress root = TileAddress.fabdem(tile.getId());
        if (tile.hasChildren()) {
            blocks.add(new ImageryBlock(GridQuadrant.WHOLE_TILE, root));
        }
        else {
            descend(root, GridQuadrant.WHOLE_TILE, links, camera, placement, minZ, maxZ, blocks);
        }
        return blocks;
    }

    private void descend(TileAddress block, GridQuadrant quadrant, ImageryBlockLinks links, Camera camera,
                         TerrainMeshPlacement placement, double minZ, double maxZ, List<ImageryBlock> out) {
        Vector3Dd[] corners = corners(block, placement, minZ, maxZ);
        // The tile was already selected as visible, with its own bounds.
        if (!quadrant.isWholeTile() && !camera.boundingConvexPolyhedraIsVisible(corners)) {
            return;
        }
        ImageryBlockLink link = links.link(block);
        if (quadrant.side() > SMALLEST_QUADRANT_SIDE && link != null && link.hasChildren()
            && lodState.shouldRefine(block, QuadtreeDrawPlanner.projectedCoverage(camera, corners))) {
            for (int digit = 0; digit < 4; digit++) {
                descend(block.child(digit), quadrant.child(digit), links, camera, placement, minZ, maxZ, out);
            }
            return;
        }
        out.add(new ImageryBlock(quadrant, block));
    }

    private static Vector3Dd[] corners(TileAddress block, TerrainMeshPlacement placement, double minZ, double maxZ) {
        double x0 = placement.worldX(block.x0() - 0.5);
        double x1 = placement.worldX(block.x0() + block.size() - 0.5);
        double y0 = placement.worldY(block.y0() - 0.5);
        double y1 = placement.worldY(block.y0() + block.size() - 0.5);
        double z0 = placement.worldZ(minZ);
        double z1 = placement.worldZ(maxZ);
        return new Vector3Dd[] {
            new Vector3Dd(x0, y0, z0), new Vector3Dd(x1, y0, z0), new Vector3Dd(x1, y1, z0), new Vector3Dd(x0, y1, z0),
            new Vector3Dd(x0, y0, z1), new Vector3Dd(x1, y0, z1), new Vector3Dd(x1, y1, z1), new Vector3Dd(x0, y1, z1)
        };
    }

    /**
     * Section [LERB2010].4.1: the block itself when it has an image, or else the nearest ancestor
     * that has one. Returns null when none is known yet.
     */
    static TileAddress nearestLinkedImage(TileAddress block, ImageryBlockLinks links) {
        for (TileAddress candidate = block; candidate != null; candidate = candidate.parent()) {
            ImageryBlockLink link = links.link(candidate);
            if (link != null && link.hasImage()) {
                return candidate;
            }
        }
        return null;
    }
}
