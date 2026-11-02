package planetdemviewer.render;

/** The outline of a view, as xyz line segments in normalized device coordinates, with its title. */
public record ViewBorder(float[] positions, float[] colors, float lineWidth, TextLabel title) {
}
