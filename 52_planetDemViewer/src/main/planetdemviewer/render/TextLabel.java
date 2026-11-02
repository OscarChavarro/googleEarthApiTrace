package planetdemviewer.render;

import java.util.List;
import vsdk.toolkit.common.color.ColorRgb;

/** A line of text placed on the canvas; x and baselineY are window pixels with a bottom-left origin. */
public record TextLabel(String text, TextStyle style, Alignment alignment, int x, int baselineY, ColorRgb color) {
    public static final ColorRgb WHITE = new ColorRgb(1.0, 1.0, 1.0);
    public static final ColorRgb BLACK = new ColorRgb(0.0, 0.0, 0.0);
    public static final ColorRgb YELLOW = new ColorRgb(1.0, 1.0, 0.0);

    public enum Alignment { LEFT, CENTER, RIGHT }

    public static TextLabel left(String text, TextStyle style, int x, int baselineY, ColorRgb color) {
        return new TextLabel(text, style, Alignment.LEFT, x, baselineY, color);
    }

    public static TextLabel centered(String text, TextStyle style, int x, int baselineY, ColorRgb color) {
        return new TextLabel(text, style, Alignment.CENTER, x, baselineY, color);
    }

    public static TextLabel rightAligned(String text, TextStyle style, int x, int baselineY, ColorRgb color) {
        return new TextLabel(text, style, Alignment.RIGHT, x, baselineY, color);
    }

    /**
     * Writes the text twice, so that it reads over any terrain: in black where it goes, and then
     * in its color one pixel to the left and one up, which leaves the shadow down and to the right.
     */
    public List<TextLabel> withShadow() {
        return List.of(new TextLabel(text, style, alignment, x, baselineY, BLACK),
            new TextLabel(text, style, alignment, x - 1, baselineY + 1, color));
    }

    int leftEdge(int width) {
        return switch (alignment) {
            case LEFT -> x;
            case CENTER -> x - width / 2;
            case RIGHT -> x - width;
        };
    }
}
