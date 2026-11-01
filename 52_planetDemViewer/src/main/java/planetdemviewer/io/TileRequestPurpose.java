package planetdemviewer.io;

/** Why a tile is needed; schedulers prioritize visible work ahead of prefetch. */
public enum TileRequestPurpose {
    RENDER,
    PICKING,
    PREFETCH,
    OFFLINE
}

