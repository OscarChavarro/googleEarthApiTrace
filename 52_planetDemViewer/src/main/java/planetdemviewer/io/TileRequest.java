package planetdemviewer.io;

import java.util.Objects;
import planetdemviewer.spatial.TileAddress;

/**
 * Immutable request published to an I/O scheduler. A larger priority wins;
 * requestedAtNanos provides deterministic recency ordering for equal priority.
 */
public record TileRequest(
    TileAddress address,
    TileRequestPurpose purpose,
    int priority,
    long requestedAtNanos
) {
    public TileRequest {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(purpose, "purpose");
        if (requestedAtNanos < 0L) {
            throw new IllegalArgumentException("Request time must be non-negative");
        }
    }

    public static TileRequest render(TileAddress address, int priority) {
        return new TileRequest(address, TileRequestPurpose.RENDER, priority, System.nanoTime());
    }
}

