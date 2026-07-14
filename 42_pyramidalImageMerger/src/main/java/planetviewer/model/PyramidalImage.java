package planetviewer.model;

/**
 * A whole pyramidal image (quadtree) read from one on-disk root folder.
 */
public final class PyramidalImage {
    private final String sourceFolder;
    private final QuadtreeNode root;
    private final int tileCount;
    private final int height;

    public PyramidalImage(String sourceFolder, QuadtreeNode root, int tileCount, int height) {
        this.sourceFolder = sourceFolder;
        this.root = root;
        this.tileCount = tileCount;
        this.height = height;
    }

    public String getSourceFolder() {
        return sourceFolder;
    }

    public QuadtreeNode getRoot() {
        return root;
    }

    public int getTileCount() {
        return tileCount;
    }

    public int getHeight() {
        return height;
    }
}
