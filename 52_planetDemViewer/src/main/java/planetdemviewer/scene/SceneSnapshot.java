package planetdemviewer.scene;

import java.util.List;
import planetdemviewer.model.OperationModes;

/**
 * Consistent immutable scene state. Long-running I/O, LOD, and CPU mesh work
 * consume this snapshot without retaining PlanetViewerModel's critical lock.
 */
public record SceneSnapshot(
    CameraSnapshot primaryCamera,
    List<LayerSnapshot> layers,
    int selectedLayerIndex,
    int currentPowerScaleCoordinate,
    OperationModes operationMode,
    double heightExaggerationFactor,
    String hudStatus,
    TerrainSurfaceSelection terrainSurfaceSelection
) {
    public SceneSnapshot {
        if (primaryCamera == null || layers == null || operationMode == null
            || !Double.isFinite(heightExaggerationFactor) || hudStatus == null) {
            throw new IllegalArgumentException("Invalid scene snapshot");
        }
        layers = List.copyOf(layers);
    }
}

