package planetdemviewer.config;

/**
 * I/O policy for the DEM store. SLOW is deliberately conservative: one tile
 * reader, one metadata explorer, and only one speculative topology level.
 */
public enum StorageProfile {
    SLOW(1, 1, 1),
    FAST(2, 2, 2);

    private final int tileLoaderThreads;
    private final int discoveryThreads;
    private final int discoveryLookAhead;

    StorageProfile(int tileLoaderThreads, int discoveryThreads, int discoveryLookAhead) {
        this.tileLoaderThreads = tileLoaderThreads;
        this.discoveryThreads = discoveryThreads;
        this.discoveryLookAhead = discoveryLookAhead;
    }

    public int tileLoaderThreads() {
        return tileLoaderThreads;
    }

    public int discoveryThreads() {
        return discoveryThreads;
    }

    public int discoveryLookAhead() {
        return discoveryLookAhead;
    }

    public static StorageProfile parse(String value) {
        if (value == null || value.isBlank()) {
            return SLOW;
        }
        for (StorageProfile profile : values()) {
            if (profile.name().equalsIgnoreCase(value.trim())) {
                return profile;
            }
        }
        return SLOW;
    }
}
