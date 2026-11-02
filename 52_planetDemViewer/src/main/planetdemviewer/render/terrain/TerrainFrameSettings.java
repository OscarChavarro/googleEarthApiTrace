package planetdemviewer.render.terrain;

import planetdemviewer.spatial.TileAddress;
import vsdk.toolkit.environment.camera.Camera;

/** The viewer state that decides how an image's terrain is planned for one view. */
public record TerrainFrameSettings(Camera cullingCamera, double relativeScale, double heightExaggeration,
                                   boolean uniformLod, TerrainSubdivisionScheme subdivisionScheme,
                                   boolean tileBoundsVisible, TileAddress selectedTile, boolean imageryVisible,
                                   double screenErrorThresholdPixels, boolean dependenciesCrossBlocks,
                                   boolean independentImageryLevels) {
}
