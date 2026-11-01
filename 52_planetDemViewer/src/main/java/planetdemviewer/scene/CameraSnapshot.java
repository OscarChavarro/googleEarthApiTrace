package planetdemviewer.scene;

import planetdemviewer.spatial.WorldPosition;

/**
 * Immutable camera data for planning, coordinate conversion, persistence, and
 * diagnostics. It intentionally contains no mutable Vitral or JOGL object.
 */
public record CameraSnapshot(
    WorldPosition position,
    WorldPosition focus,
    WorldPosition up,
    double fieldOfViewDegrees,
    double nearPlaneMetres,
    double farPlaneMetres,
    ProjectionType projectionType,
    double orthogonalZoom,
    double viewportWidth,
    double viewportHeight,
    long modificationVersion
) {
    public CameraSnapshot {
        if (position == null || focus == null || up == null || projectionType == null
            || !Double.isFinite(fieldOfViewDegrees) || !Double.isFinite(nearPlaneMetres)
            || !Double.isFinite(farPlaneMetres) || !Double.isFinite(orthogonalZoom)
            || viewportWidth < 0.0 || viewportHeight < 0.0) {
            throw new IllegalArgumentException("Invalid camera snapshot");
        }
    }
}

