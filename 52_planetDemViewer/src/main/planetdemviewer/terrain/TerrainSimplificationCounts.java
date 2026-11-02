package planetdemviewer.terrain;

/**
 * What the view-dependent subdivision did in a frame, with the quantities of figure
 * [LIND1996].6: n1, n2 and the number of evaluations of Equation [LIND1996](2).
 *
 * <p>References:</p>
 * <ul>
 * <li>[LIND1996] Lindstrom, P. et al. "Real-Time, Continuous Level of Detail
 * Rendering of Height Fields", SIGGRAPH 1996.</li>
 * </ul>
 *
 * @param trianglesBeforeSimplification n1: the triangles of the selected blocks
 * @param trianglesRendered n2: the triangles after vertex-based simplification
 * @param blocksThatCouldCoarsen how many pass the test of line 4 of MAIN in section
 *        [LIND1996].5, which the area-based tile selection does not use
 */
public record TerrainSimplificationCounts(int blocks, long trianglesBeforeSimplification, long trianglesRendered,
                                          long deltaProjections, int blocksThatCouldCoarsen) {
    public String json() {
        return "{\"blocks\":" + blocks + ",\"trianglesBeforeSimplification\":" + trianglesBeforeSimplification
            + ",\"trianglesRendered\":" + trianglesRendered + ",\"deltaProjections\":" + deltaProjections
            + ",\"blocksThatCouldCoarsen\":" + blocksThatCouldCoarsen + "}";
    }
}
