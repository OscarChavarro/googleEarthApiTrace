package planetdemviewer.spatial;

import java.util.Objects;

/**
 * Spatial identity of a quadtree tile.  It deliberately contains no path or
 * source-specific file layout; adapters resolve an address to their storage.
 */
public record TileAddress(String scheme, String quadkey) {
    public static final String FABDEM_QUADKEY_SCHEME = "fabdem-quadkey-v1";

    public TileAddress {
        if (scheme == null || scheme.isBlank()) {
            throw new IllegalArgumentException("A tile scheme is required");
        }
        if (quadkey == null || quadkey.isBlank() || quadkey.charAt(0) != '0') {
            throw new IllegalArgumentException("Quadkey must start with root digit 0");
        }
        for (int index = 1; index < quadkey.length(); index++) {
            char digit = quadkey.charAt(index);
            if (digit < '0' || digit > '3') {
                throw new IllegalArgumentException("Invalid quadkey digit: " + digit);
            }
        }
    }

    public static TileAddress fabdem(String quadkey) {
        return new TileAddress(FABDEM_QUADKEY_SCHEME, quadkey);
    }

    public int level() {
        return quadkey.length() - 1;
    }

    public TileAddress child(int quadrant) {
        if (quadrant < 0 || quadrant > 3) {
            throw new IllegalArgumentException("Quadrant must be in [0, 3]");
        }
        return new TileAddress(scheme, quadkey + quadrant);
    }

    public TileAddress parent() {
        return level() == 0 ? null : new TileAddress(scheme, quadkey.substring(0, quadkey.length() - 1));
    }
}

