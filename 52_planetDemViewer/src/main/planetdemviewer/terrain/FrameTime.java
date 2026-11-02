package planetdemviewer.terrain;

/** Monotonic animation time supplied by the animation thread. */
public record FrameTime(long monotonicNanos) {
    public FrameTime {
        if (monotonicNanos < 0L) {
            throw new IllegalArgumentException("Frame time must be non-negative");
        }
    }

    public static FrameTime now() {
        return new FrameTime(System.nanoTime());
    }
}
