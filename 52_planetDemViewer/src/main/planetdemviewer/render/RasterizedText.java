package planetdemviewer.render;

import vsdk.toolkit.media.RGBAImageUncompressed;

/** A line of text as an image, and where it goes: a triangle strip of xy+uv in normalized device coordinates. */
public record RasterizedText(RGBAImageUncompressed image, float[] quadVertices) {
    public static RasterizedText of(RGBAImageUncompressed image, TextLabel label, int descent,
                             int canvasWidth, int canvasHeight) {
        int width = image.getXSize();
        int height = image.getYSize();
        int x = label.leftEdge(width);
        int baseline = label.baselineY() - descent - 2;
        float left = 2.0f * x / canvasWidth - 1.0f;
        float right = 2.0f * (x + width) / canvasWidth - 1.0f;
        float bottom = 2.0f * baseline / canvasHeight - 1.0f;
        float top = 2.0f * (baseline + height) / canvasHeight - 1.0f;
        return new RasterizedText(image, new float[] {
            left, bottom, 0, 0,
            right, bottom, 1, 0,
            left, top, 0, 1,
            right, top, 1, 1
        });
    }
}
