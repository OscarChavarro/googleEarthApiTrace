package planetdemviewer.scene;

/** Immutable metadata exposed by a scene layer without exposing its resources. */
public record LayerDescriptor(String displayName, String sourceType, boolean elevation,
                              String spatialReferenceId, String sourceVerticalDatumId,
                              String canonicalVerticalDatumId) {
    public LayerDescriptor(String displayName, String sourceType, boolean elevation) {
        this(displayName, sourceType, elevation, "unspecified", "unspecified", "WGS84-ellipsoidal");
    }

    public LayerDescriptor {
        if (displayName == null || displayName.isBlank() || sourceType == null || sourceType.isBlank()
            || spatialReferenceId == null || spatialReferenceId.isBlank()
            || sourceVerticalDatumId == null || sourceVerticalDatumId.isBlank()
            || canonicalVerticalDatumId == null || canonicalVerticalDatumId.isBlank()) {
            throw new IllegalArgumentException("Layer descriptor values are required");
        }
    }
}

