package planetdemviewer.render.terrain;

/**
 * How the two quadtrees met in one frame. A level is -1 when no block had it. A block is coarser
 * or finer than its tile when its image comes from a shallower or deeper level.
 */
public record TerrainImageryCounts(int blocks, int minGeometryLevel, int maxGeometryLevel,
                                   int minColorLevel, int maxColorLevel, int coarserBlocks,
                                   int finerBlocks, int blocksWithoutColor) {
    public String json() {
        return "{\"blocks\":" + blocks
            + ",\"minGeometryLevel\":" + minGeometryLevel + ",\"maxGeometryLevel\":" + maxGeometryLevel
            + ",\"minColorLevel\":" + minColorLevel + ",\"maxColorLevel\":" + maxColorLevel
            + ",\"coarserBlocks\":" + coarserBlocks + ",\"finerBlocks\":" + finerBlocks
            + ",\"blocksWithoutColor\":" + blocksWithoutColor + "}";
    }
}
