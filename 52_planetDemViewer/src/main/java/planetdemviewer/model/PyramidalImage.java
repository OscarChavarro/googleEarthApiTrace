package planetdemviewer.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A whole pyramidal image (quadtree) read from one on-disk root folder.
 */
public final class PyramidalImage {
    private final String sourceFolder;
    private final QuadtreeNode root;
    private final AtomicInteger tileCount = new AtomicInteger(1);
    private final AtomicInteger height = new AtomicInteger();

    public PyramidalImage(String sourceFolder, QuadtreeNode root) {
        this.sourceFolder = sourceFolder;
        this.root = root;
    }

    public String getSourceFolder() {
        return sourceFolder;
    }

    public QuadtreeNode getRoot() {
        return root;
    }

    public int getTileCount() {
        return tileCount.get();
    }

    public int getHeight() {
        return height.get();
    }

    /** Performs exactly one topology step and never reads tile contents. */
    public void discoverChildren(QuadtreeNode node) {
        if (node == null || node.getContainerDirectory() == null || node.isDiscoveryComplete()) {
            return;
        }
        QuadtreeNode[] children = new QuadtreeNode[4];
        try {
            for (int digit = 0; digit < 4; digit++) {
                String childId = node.getId() + digit;
                Path childDirectory = childDirectoryFor(node.getContainerDirectory(), childId, digit);
                if (childDirectory == null) {
                    continue;
                }
                Path tilePath = childDirectory.resolve(childId + ".bin");
                BasicFileAttributes tileAttributes = attributes(tilePath);
                boolean validTile = tileAttributes != null
                    && tileAttributes.isRegularFile()
                    && tileAttributes.size() == DemTile.BYTE_COUNT;
                children[digit] = new QuadtreeNode(
                    childId,
                    node,
                    validTile ? tilePath.toFile() : null,
                    childDirectory
                );
                if (validTile) {
                    tileCount.incrementAndGet();
                }
                height.accumulateAndGet(children[digit].getDepth(), Math::max);
            }
        }
        finally {
            node.completeDiscovery(children);
        }
    }

    /** Explicit full scan for offline export; interactive startup never calls this. */
    public void discoverAll() {
        discoverAll(root);
    }

    private void discoverAll(QuadtreeNode node) {
        if (!node.isDiscoveryComplete()) {
            if (node.queueDiscovery() && node.beginDiscovery()) {
                discoverChildren(node);
            }
        }
        QuadtreeNode[] children = node.getChildren();
        if (children == null) {
            return;
        }
        for (QuadtreeNode child : children) {
            if (child != null) {
                discoverAll(child);
            }
        }
    }

    private static Path childDirectoryFor(Path parentDirectory, String childId, int digit) {
        Path perDigit = parentDirectory.resolve(Integer.toString(digit));
        BasicFileAttributes attributes = attributes(perDigit);
        if (attributes != null && attributes.isDirectory()) {
            return perDigit;
        }
        Path cumulative = parentDirectory.resolve(childId);
        attributes = attributes(cumulative);
        return attributes != null && attributes.isDirectory() ? cumulative : null;
    }

    private static BasicFileAttributes attributes(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }
        catch (IOException | SecurityException ex) {
            return null;
        }
    }
}
