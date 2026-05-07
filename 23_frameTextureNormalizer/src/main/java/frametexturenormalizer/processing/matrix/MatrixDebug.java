package frametexturenormalizer.processing.matrix;

final class MatrixDebug {
    private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("pib.debug.matrix", "false"));
    private static final Integer DEBUG_FRAME = parseDebugFrame();
    private static final ThreadLocal<Integer> CURRENT_FRAME = new ThreadLocal<>();

    private MatrixDebug() {
    }

    static void setCurrentFrame(int frameId) {
        CURRENT_FRAME.set(frameId);
    }

    static void clearCurrentFrame() {
        CURRENT_FRAME.remove();
    }

    static void debug(Integer frameId, String message, Object... args) {
        if (!isDebugEnabled(frameId)) {
            return;
        }
        System.out.println("[TileSetToMatrixConverter] " + String.format(message, args));
    }

    private static boolean isDebugEnabled(Integer frameId) {
        if (!DEBUG) {
            return false;
        }
        Integer effectiveFrame = frameId == null ? CURRENT_FRAME.get() : frameId;
        if (DEBUG_FRAME == null) {
            return true;
        }
        return effectiveFrame != null && effectiveFrame.intValue() == DEBUG_FRAME.intValue();
    }

    private static Integer parseDebugFrame() {
        String raw = System.getProperty("pib.debug.matrix.frame");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }
}
