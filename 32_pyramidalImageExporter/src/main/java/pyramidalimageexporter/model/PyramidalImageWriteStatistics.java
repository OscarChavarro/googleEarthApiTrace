package pyramidalimageexporter.model;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Accumulates outcome counters for a session-local pyramidal image export:
 * each placed tile is written to the session's own pyramid folder, either
 * filling a new slot or rewriting a slot left by a previous run of the same
 * session (no existing image is ever read back or preserved — the export is
 * a plain regeneration of this session's pyramid).
 */
public final class PyramidalImageWriteStatistics {
    private final AtomicInteger newImages = new AtomicInteger();
    private final AtomicInteger rewrittenImages = new AtomicInteger();

    public void incrementNewImages() {
        newImages.incrementAndGet();
    }

    public void incrementRewrittenImages() {
        rewrittenImages.incrementAndGet();
    }

    public int getNewImages() {
        return newImages.get();
    }

    public int getRewrittenImages() {
        return rewrittenImages.get();
    }

    @Override
    public String toString() {
        return "PyramidalImageWriteStatistics{"
            + "new=" + newImages.get()
            + ", rewritten=" + rewrittenImages.get()
            + '}';
    }
}
