package planetdemviewer.scene;

import planetdemviewer.spatial.TileScheme;

/** Layer semantics specific to normalized elevation data, independent of storage layout. */
public interface ElevationLayer extends TerrainLayer {
    TileScheme tileScheme();

    String sourceVerticalDatumId();

    String canonicalVerticalDatumId();
}
