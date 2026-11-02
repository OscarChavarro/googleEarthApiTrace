package planetdemviewer.geo;

import planetdemviewer.spatial.SpatialPosition;

/**
 * Bidirectional CRS contract. Implementations document their identifier and
 * native x/y units; height is always WGS84 ellipsoidal metres at this boundary.
 */
public interface SpatialReference {
    String id();

    SpatialPosition fromGeographic(GeographicPosition geographic);

    GeographicPosition toGeographic(SpatialPosition position);
}
