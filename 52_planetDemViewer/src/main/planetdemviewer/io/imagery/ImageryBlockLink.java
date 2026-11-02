package planetdemviewer.io.imagery;

/** What the color pyramid has for one block: its own image, and folders with deeper blocks. */
public record ImageryBlockLink(boolean hasImage, boolean hasChildren) {
    public static final ImageryBlockLink NONE = new ImageryBlockLink(false, false);
}
