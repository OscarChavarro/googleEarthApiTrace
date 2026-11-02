package planetdemviewer.scene;

/**
 * Backend-neutral scene-layer contract.  Existing DEM layers implement this
 * during the incremental migration; imagery and vectors will use it later.
 */
public interface TerrainLayer {
    LayerId layerId();

    LayerDescriptor descriptor();

    boolean isVisible();
}

