package planetdemviewer.render;

/** Frames per second, measured over windows of at least one second. */
public final class FrameRateCounter {
    private static final long WINDOW_NANOS = 1_000_000_000L;

    private long windowStartedNanos;
    private int framesInWindow;
    private int framesPerSecond;

    public FrameRateCounter(long nowNanos) {
        windowStartedNanos = nowNanos;
    }

    public void frameRendered(long nowNanos) {
        framesInWindow++;
        long elapsed = nowNanos - windowStartedNanos;
        if (elapsed < WINDOW_NANOS) {
            return;
        }
        framesPerSecond = (int) Math.round(framesInWindow * (double) WINDOW_NANOS / elapsed);
        framesInWindow = 0;
        windowStartedNanos = nowNanos;
    }

    public int framesPerSecond() {
        return framesPerSecond;
    }
}
