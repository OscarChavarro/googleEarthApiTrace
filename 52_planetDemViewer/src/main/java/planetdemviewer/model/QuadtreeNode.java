package planetdemviewer.model;

import java.io.File;
import java.nio.file.Path;

/**
 * One lazily discovered node of a folder-based DEM quadtree (root "0.bin",
 * then one directory per post-root quadrant digit).
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
    private final Path containerDirectory;
    private final int depth;
    private final double x0;
    private final double y0;
    private final double x1;
    private final double y1;
    private volatile QuadtreeNode[] children;
    private DiscoveryState discoveryState;

    private enum DiscoveryState {
        UNDISCOVERED,
        QUEUED,
        DISCOVERING,
        COMPLETE
    }

    public QuadtreeNode(String id, QuadtreeNode parent, File tileFile) {
        this(id, parent, tileFile, null);
    }

    public QuadtreeNode(String id, QuadtreeNode parent, File tileFile, Path containerDirectory) {
        this.id = id;
        this.parent = parent;
        this.tileFile = tileFile;
        this.containerDirectory = containerDirectory;
        this.discoveryState = containerDirectory == null ? DiscoveryState.COMPLETE : DiscoveryState.UNDISCOVERED;
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

    public Path getContainerDirectory() {
        return containerDirectory;
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

    public synchronized boolean queueDiscovery() {
        if (discoveryState != DiscoveryState.UNDISCOVERED) {
            return false;
        }
        discoveryState = DiscoveryState.QUEUED;
        return true;
    }

    public synchronized boolean beginDiscovery() {
        if (discoveryState != DiscoveryState.QUEUED) {
            return false;
        }
        discoveryState = DiscoveryState.DISCOVERING;
        return true;
    }

    public synchronized void completeDiscovery(QuadtreeNode[] discoveredChildren) {
        this.children = discoveredChildren;
        discoveryState = DiscoveryState.COMPLETE;
        notifyAll();
    }

    public synchronized boolean isDiscoveryComplete() {
        return discoveryState == DiscoveryState.COMPLETE;
    }

    public synchronized boolean isDiscoverable() {
        return containerDirectory != null && discoveryState != DiscoveryState.COMPLETE;
    }

    public boolean hasChildren() {
        QuadtreeNode[] snapshot = children;
        if (snapshot == null) {
            return false;
        }
        for (QuadtreeNode child : snapshot) {
            if (child != null) {
                return true;
            }
        }
        return false;
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
