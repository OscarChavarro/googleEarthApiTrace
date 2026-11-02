package planetdemviewer.render;

/** What the HUD reports about the renderers and loaders during one frame. */
public record HudFrameStats(int residentTextures, long rasterGpuBytes, int residentMeshes, long terrainGpuBytes,
                            long tileRamBytes, int pendingTreeDiscoveries, boolean uniformLod,
                            TerrainSimplificationStatistics.Frame simplification) {
    public long totalGpuBytes() {
        return rasterGpuBytes + terrainGpuBytes;
    }
}
