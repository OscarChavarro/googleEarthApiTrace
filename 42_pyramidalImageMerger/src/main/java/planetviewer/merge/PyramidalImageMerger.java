package planetviewer.merge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import planetviewer.model.PyramidalImage;
import planetviewer.model.QuadtreeNode;

public final class PyramidalImageMerger {
    public int copyMissingTiles(PyramidalImage destination, PyramidalImage delta) throws IOException {
        if (destination == null || delta == null) {
            return 0;
        }
        Path destinationRoot = Path.of(destination.getSourceFolder());
        return copyMissingRecursive(destinationRoot, destination.getRoot(), delta.getRoot());
    }

    private int copyMissingRecursive(Path destinationRoot, QuadtreeNode destinationNode, QuadtreeNode deltaNode) throws IOException {
        if (deltaNode == null) {
            return 0;
        }

        int copied = 0;
        if (deltaNode.getTileFile() != null && (destinationNode == null || destinationNode.getTileFile() == null)) {
            Path destinationTilePath = tilePathFor(destinationRoot, deltaNode.getId());
            Files.createDirectories(destinationTilePath.getParent());
            Files.copy(deltaNode.getTileFile().toPath(), destinationTilePath);
            copied++;
        }

        if (!deltaNode.hasChildren()) {
            return copied;
        }

        QuadtreeNode[] deltaChildren = deltaNode.getChildren();
        QuadtreeNode[] destinationChildren = destinationNode == null ? null : destinationNode.getChildren();
        for (int digit = 0; digit < 4; digit++) {
            QuadtreeNode destinationChild = destinationChildren == null ? null : destinationChildren[digit];
            copied += copyMissingRecursive(destinationRoot, destinationChild, deltaChildren[digit]);
        }
        return copied;
    }

    private Path tilePathFor(Path destinationRoot, String nodeId) {
        Path directory = destinationRoot;
        for (int level = 1; level < nodeId.length(); level++) {
            directory = directory.resolve(nodeId.substring(0, level + 1));
        }
        return directory.resolve(nodeId + ".png");
    }
}
