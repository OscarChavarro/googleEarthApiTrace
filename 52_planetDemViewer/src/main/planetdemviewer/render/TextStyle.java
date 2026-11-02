package planetdemviewer.render;

/** The fonts of the text written over the canvas. */
public enum TextStyle {
    STATUS("SansSerif", true, 16),
    SELECTION("Arial", false, 32),
    PERFORMANCE("Arial", true, 32),
    TILE_LABEL("Arial", false, 12);

    private final String fontName;
    private final boolean bold;
    private final int size;

    TextStyle(String fontName, boolean bold, int size) {
        this.fontName = fontName;
        this.bold = bold;
        this.size = size;
    }

    public String fontName() {
        return fontName;
    }

    public boolean isBold() {
        return bold;
    }

    public int size() {
        return size;
    }
}
