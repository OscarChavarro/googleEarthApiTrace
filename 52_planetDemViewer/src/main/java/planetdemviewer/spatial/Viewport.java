package planetdemviewer.spatial;

/** Pixel dimensions of a viewport. Viewport points use a top-left origin. */
public record Viewport(int width, int height) {
    public Viewport {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Viewport dimensions must be positive");
        }
    }
}

