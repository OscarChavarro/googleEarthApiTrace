package pyramidalimageexporter.common.statistics;

/**
 * Accumulates outcome counters for a session-local pyramidal image export:
 * each placed tile is written to the session's own pyramid folder, either
 * filling a new slot or rewriting a slot left by a previous run of the same
 * session (no existing image is ever read back or preserved — the export is
 * a plain regeneration of this session's pyramid).
 */
public final class PyramidalImageAdditionStatistics {
    private int newImages = 0;
    private int rewrittenImages = 0;

    public void incrementNewImages() {
        newImages++;
    }

    public void incrementRewrittenImages() {
        rewrittenImages++;
    }

    public int getNewImages() {
        return newImages;
    }

    public int getRewrittenImages() {
        return rewrittenImages;
    }

    @Override
    public String toString() {
        return "PyramidalImageAdditionStatistics{"
            + "new=" + newImages
            + ", rewritten=" + rewrittenImages
            + '}';
    }
}
