package planetdemviewer.io.imagery;

import planetdemviewer.spatial.TileAddress;

/** The part of the color quadtree that is known so far, which is independent of the elevation quadtree. */
@FunctionalInterface
public interface ImageryBlockLinks {
    /** Returns null while it is unknown, and never blocks the caller on storage. */
    ImageryBlockLink link(TileAddress block);
}
