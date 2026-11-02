package planetdemviewer.render;

import planetdemviewer.terrain.AdaptiveTerrainMesh;

/**
 * What the view-dependent subdivision did in a frame, with the quantities of figure
 * [LIND1996].6: n1, n2 and the number of evaluations of Equation [LIND1996](2).
 *
 * <p>References:</p>
 * <ul>
 * <li>[LIND1996] Lindstrom, P. et al. "Real-Time, Continuous Level of Detail
 * Rendering of Height Fields", SIGGRAPH 1996.</li>
 * </ul>
 */
public final class TerrainSimplificationStatistics {
    private int blocks;
    private long trianglesBeforeSimplification;
    private long trianglesRendered;
    private long deltaProjections;
    private int blocksThatCouldCoarsen;
    private volatile Frame lastFrame = new Frame(0, 0L, 0L, 0L, 0);

    /**
     * @param trianglesBeforeSimplification n1: the triangles of the selected blocks
     * @param trianglesRendered n2: the triangles after vertex-based simplification
     * @param blocksThatCouldCoarsen how many pass the test of line 4 of MAIN in section
     *        [LIND1996].5, which the area-based tile selection does not use
     */
    public record Frame(int blocks, long trianglesBeforeSimplification, long trianglesRendered,
                        long deltaProjections, int blocksThatCouldCoarsen) {
        String json() {
            return "{\"blocks\":" + blocks + ",\"trianglesBeforeSimplification\":" + trianglesBeforeSimplification
                + ",\"trianglesRendered\":" + trianglesRendered + ",\"deltaProjections\":" + deltaProjections
                + ",\"blocksThatCouldCoarsen\":" + blocksThatCouldCoarsen + "}";
        }
    }

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

    Frame currentFrame() {
        return new Frame(blocks, trianglesBeforeSimplification, trianglesRendered,
            deltaProjections, blocksThatCouldCoarsen);
    }

    /** Safe to read from any thread. */
    Frame lastFrame() {
        return lastFrame;
    }
}
