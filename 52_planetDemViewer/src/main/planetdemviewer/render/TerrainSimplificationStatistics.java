package planetdemviewer.render;

import planetdemviewer.terrain.AdaptiveTerrainMesh;
import planetdemviewer.terrain.TerrainSimplificationCounts;

/**
 * Adds up what the view-dependent subdivision does in each frame.
 */
public final class TerrainSimplificationStatistics {
    private int blocks;
    private long trianglesBeforeSimplification;
    private long trianglesRendered;
    private long deltaProjections;
    private int blocksThatCouldCoarsen;
    private volatile TerrainSimplificationCounts lastFrame = new TerrainSimplificationCounts(0, 0L, 0L, 0L, 0);

    void beginFrame() {
        lastFrame = currentFrame();
        blocks = 0;
        trianglesBeforeSimplification = 0L;
        trianglesRendered = 0L;
        deltaProjections = 0L;
        blocksThatCouldCoarsen = 0;
    }

    void blockDrawn(AdaptiveTerrainMesh mesh) {
        blocks++;
        trianglesBeforeSimplification += mesh.fullTriangleCount();
        trianglesRendered += mesh.getNumTriangles();
        deltaProjections += mesh.deltaProjections();
        if (mesh.couldUseLowerResolutionBlock()) {
            blocksThatCouldCoarsen++;
        }
    }

    TerrainSimplificationCounts currentFrame() {
        return new TerrainSimplificationCounts(blocks, trianglesBeforeSimplification, trianglesRendered,
            deltaProjections, blocksThatCouldCoarsen);
    }

    /** Safe to read from any thread. */
    TerrainSimplificationCounts lastFrame() {
        return lastFrame;
    }
}
