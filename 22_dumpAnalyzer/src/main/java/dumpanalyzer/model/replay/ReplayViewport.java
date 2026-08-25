package dumpanalyzer.model.replay;

public record ReplayViewport(int x, int y, int width, int height) {
    public static final ReplayViewport EMPTY = new ReplayViewport(0, 0, 0, 0);
}
