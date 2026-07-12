package planetviewer.animation;

import javax.swing.Timer;

/**
 * Coalesces bursty repaint requests (e.g. several background tile loads
 * completing within a few milliseconds of each other) into at most one
 * repaint per COALESCE_MILLIS, so the image sharpens progressively without
 * flooding the GL thread with redundant frames. This is not a free-running
 * animator: with no pending requestRepaint() calls, nothing fires.
 */
public final class RepaintScheduler {
    private static final int COALESCE_MILLIS = 50;

    private final Timer timer;

    public RepaintScheduler(Runnable repaintAction) {
        this.timer = new Timer(COALESCE_MILLIS, e -> repaintAction.run());
        this.timer.setRepeats(false);
    }

    public void requestRepaint() {
        if (!timer.isRunning()) {
            timer.start();
        }
    }
}
