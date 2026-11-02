package planetdemviewer.render;

import java.util.List;

/** The outline of a view, as xyz line segments in normalized device coordinates, with its title. */
public record ViewBorder(float[] positions, float[] colors, float lineWidth, List<TextLabel> title) {
}
