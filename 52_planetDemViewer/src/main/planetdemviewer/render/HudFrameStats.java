package planetdemviewer.render;

import planetdemviewer.terrain.TerrainSimplificationCounts;

/** What the HUD reports about the renderers and loaders during one frame. */
public record HudFrameStats(int residentTextures, long rasterGpuBytes, int residentMeshes, long terrainGpuBytes,
                            long tileRamBytes, int pendingTreeDiscoveries, boolean uniformLod,
                            TerrainSimplificationCounts simplification) {
    public long totalGpuBytes() {
        return rasterGpuBytes + terrainGpuBytes;
    }
}
