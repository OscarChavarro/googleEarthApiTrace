package planetdemviewer.render.terrain;

/** Adds up which levels of the elevation and imagery trees each frame used. */
public final class TerrainImageryStatistics {
    private int blocks;
    private int minGeometryLevel = -1;
    private int maxGeometryLevel = -1;
    private int minColorLevel = -1;
    private int maxColorLevel = -1;
    private int coarserBlocks;
    private int finerBlocks;
    private int blocksWithoutColor;
    private volatile TerrainImageryCounts lastFrame = new TerrainImageryCounts(0, -1, -1, -1, -1, 0, 0, 0);

    public void beginFrame() {
        lastFrame = new TerrainImageryCounts(blocks, minGeometryLevel, maxGeometryLevel, minColorLevel, maxColorLevel,
            coarserBlocks, finerBlocks, blocksWithoutColor);
        blocks = 0;
        minGeometryLevel = -1;
        maxGeometryLevel = -1;
        minColorLevel = -1;
        maxColorLevel = -1;
        coarserBlocks = 0;
        finerBlocks = 0;
        blocksWithoutColor = 0;
    }

    /** @param colorLevel the level the image comes from, or -1 for none */
    void blockDrawn(int geometryLevel, int colorLevel) {
        blocks++;
        minGeometryLevel = minGeometryLevel < 0 ? geometryLevel : Math.min(minGeometryLevel, geometryLevel);
        maxGeometryLevel = Math.max(maxGeometryLevel, geometryLevel);
        if (colorLevel < 0) {
            blocksWithoutColor++;
            return;
        }
        minColorLevel = minColorLevel < 0 ? colorLevel : Math.min(minColorLevel, colorLevel);
        maxColorLevel = Math.max(maxColorLevel, colorLevel);
        if (colorLevel < geometryLevel) {
            coarserBlocks++;
        }
        else if (colorLevel > geometryLevel) {
            finerBlocks++;
        }
    }

    /** Safe to read from any thread. */
    public TerrainImageryCounts lastFrame() {
        return lastFrame;
    }
}
