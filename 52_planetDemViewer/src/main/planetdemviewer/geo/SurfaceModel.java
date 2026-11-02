package planetdemviewer.geo;

/** Maps a normalized geographic position to its presentation surface. */
public interface SurfaceModel {
    SurfacePoint locate(GeographicPosition position);

    SurfaceFrame localFrame(GeographicPosition position);
}
