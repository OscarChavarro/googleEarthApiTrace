package planetdemviewer.render;

/** Turns a text label into an image that a graphics backend can show. */
@FunctionalInterface
public interface TextRasterizer {
    /** Returns null when the label has nothing to show. */
    RasterizedText rasterize(TextLabel label, int canvasWidth, int canvasHeight);
}
