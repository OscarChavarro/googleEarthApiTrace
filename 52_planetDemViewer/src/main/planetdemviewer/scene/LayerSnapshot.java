package planetdemviewer.scene;

/** Immutable placement and display state of a scene layer. */
public record LayerSnapshot(
    LayerId id,
    LayerDescriptor descriptor,
    String sourceIdentity,
    boolean visible,
    double offsetX,
    double offsetY,
    int powerScaleCoordinate,
    double zOffset,
    double opacity
) {
    public LayerSnapshot {
        if (id == null || descriptor == null || sourceIdentity == null || sourceIdentity.isBlank()
            || !Double.isFinite(offsetX) || !Double.isFinite(offsetY)
            || !Double.isFinite(zOffset) || !Double.isFinite(opacity)) {
            throw new IllegalArgumentException("Invalid layer snapshot");
        }
    }
}

