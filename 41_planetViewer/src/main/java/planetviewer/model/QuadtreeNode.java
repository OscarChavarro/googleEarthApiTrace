package planetviewer.model;

import java.io.File;

/**
 * One node of a pyramidal image quadtree, as scanned from the folder-based
 * on-disk format (root "0.png", children "00/01/02/03", recursively).
 * Quadrant digit convention: 0 = south-west, 1 = south-east, 2 = north-east,
 * 3 = north-west child, matching 32_pyramidalImageExporter's PyramidalImageExporter.
 *
 * The node's rectangle (x0, y0, x1, y1) lives in the pyramidal image's own
 * local [0,1]x[0,1] space and doubles as its texture sub-rectangle within
 * the image's root tile (v = 0 at the south/bottom edge, GL convention).
 */
public final class QuadtreeNode {
    private final String id;
    private final QuadtreeNode parent;
    private final File tileFile;
    private final int depth;
    private final double x0;
    private final double y0;
    private final double x1;
    private final double y1;
    private QuadtreeNode[] children;

    public QuadtreeNode(String id, QuadtreeNode parent, File tileFile) {
        this.id = id;
        this.parent = parent;
        this.tileFile = tileFile;
        this.depth = id.length() - 1;
        if (parent == null) {
            this.x0 = 0.0;
            this.y0 = 0.0;
            this.x1 = 1.0;
            this.y1 = 1.0;
        }
        else {
            int quadrant = id.charAt(id.length() - 1) - '0';
            double midX = (parent.x0 + parent.x1) * 0.5;
            double midY = (parent.y0 + parent.y1) * 0.5;
            switch (quadrant) {
                case 0 -> { this.x0 = parent.x0; this.y0 = parent.y0; this.x1 = midX; this.y1 = midY; }
                case 1 -> { this.x0 = midX; this.y0 = parent.y0; this.x1 = parent.x1; this.y1 = midY; }
                case 2 -> { this.x0 = midX; this.y0 = midY; this.x1 = parent.x1; this.y1 = parent.y1; }
                case 3 -> { this.x0 = parent.x0; this.y0 = midY; this.x1 = midX; this.y1 = parent.y1; }
                default -> throw new IllegalArgumentException("Invalid quadkey suffix in id: " + id);
            }
        }
    }

    public String getId() {
        return id;
    }

    public QuadtreeNode getParent() {
        return parent;
    }

    public File getTileFile() {
        return tileFile;
    }

    public int getDepth() {
        return depth;
    }

    public double getX0() {
        return x0;
    }

    public double getY0() {
        return y0;
    }

    public double getX1() {
        return x1;
    }

    public double getY1() {
        return y1;
    }

    public QuadtreeNode[] getChildren() {
        return children;
    }

    public void setChildren(QuadtreeNode[] children) {
        this.children = children;
    }

    public boolean hasChildren() {
        return children != null;
    }

    /**
     * Finds the nearest ancestor (possibly this node itself) that has a
     * tile image on disk, walking up via parent links.
     */
    public QuadtreeNode nearestSelfOrAncestorWithTile() {
        QuadtreeNode node = this;
        while (node != null && node.tileFile == null) {
            node = node.parent;
        }
        return node;
    }

    /**
     * Texture sub-rectangle of this node's own rectangle expressed relative
     * to an ancestor's local [0,1]x[0,1] tile space (used when this node has
     * no tile of its own and must borrow the ancestor's texture).
     */
    public double[] subRectRelativeTo(QuadtreeNode ancestor) {
        double ax0 = ancestor.x0;
        double ay0 = ancestor.y0;
        double aSpan = ancestor.x1 - ancestor.x0;
        double u0 = (x0 - ax0) / aSpan;
        double u1 = (x1 - ax0) / aSpan;
        double v0 = (y0 - ay0) / aSpan;
        double v1 = (y1 - ay0) / aSpan;
        return new double[] {u0, v0, u1, v1};
    }
}
