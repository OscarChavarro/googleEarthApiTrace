package planetdemviewer.terrain;

import java.util.Objects;

/** Immutable state consumed by a future geomorph/fade draw packet. */
public record LodTransition(
    LodReplacement replacement,
    long startNanos,
    double durationSeconds,
    TransitionMode mode
) {
    public LodTransition {
        Objects.requireNonNull(replacement, "replacement");
        Objects.requireNonNull(mode, "mode");
        if (startNanos < 0L || !Double.isFinite(durationSeconds) || durationSeconds < 0.0) {
            throw new IllegalArgumentException("Invalid LOD transition timing");
        }
    }
}
