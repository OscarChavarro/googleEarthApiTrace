package planetdemviewer.spatial;

import planetdemviewer.geo.GeographicBounds;

/** Maps a source-neutral tile address to geographic coverage and hierarchy. */
public interface TileScheme {
    String id();

    TileAddress root();

    GeographicBounds geographicBounds(TileAddress address);
}
