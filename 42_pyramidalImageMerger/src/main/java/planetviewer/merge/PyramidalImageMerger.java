package planetviewer.merge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import planetviewer.model.PyramidalImage;
import planetviewer.model.QuadtreeNode;

public final class PyramidalImageMerger {
    public MergeResult mergeTiles(PyramidalImage destination, PyramidalImage delta, Set<String> replacementNodeIds) throws IOException {
        if (destination == null || delta == null) {
            return new MergeResult(0, 0);
        }
        Path destinationRoot = Path.of(destination.getSourceFolder());
        return mergeRecursive(destinationRoot, destination.getRoot(), delta.getRoot(), replacementNodeIds);
    }

    private MergeResult mergeRecursive(
        Path destinationRoot,
        QuadtreeNode destinationNode,
        QuadtreeNode deltaNode,
        Set<String> replacementNodeIds
    ) throws IOException {
        if (deltaNode == null) {
            return new MergeResult(0, 0);
        }

        int copied = 0;
        int replaced = 0;
        if (deltaNode.getTileFile() != null && (destinationNode == null || destinationNode.getTileFile() == null)) {
            Path destinationTilePath = tilePathFor(destinationRoot, deltaNode.getId());
            Files.createDirectories(destinationTilePath.getParent());
            Files.copy(deltaNode.getTileFile().toPath(), destinationTilePath);
            copied++;
        }
        else if (deltaNode.getTileFile() != null && replacementNodeIds.contains(deltaNode.getId())) {
            Files.copy(
                deltaNode.getTileFile().toPath(),
                destinationNode.getTileFile().toPath(),
                StandardCopyOption.REPLACE_EXISTING
            );
            replaced++;
        }

        if (!deltaNode.hasChildren()) {
            return new MergeResult(copied, replaced);
        }

        QuadtreeNode[] deltaChildren = deltaNode.getChildren();
        QuadtreeNode[] destinationChildren = destinationNode == null ? null : destinationNode.getChildren();
        for (int digit = 0; digit < 4; digit++) {
            QuadtreeNode destinationChild = destinationChildren == null ? null : destinationChildren[digit];
            MergeResult childResult = mergeRecursive(destinationRoot, destinationChild, deltaChildren[digit], replacementNodeIds);
            copied += childResult.copiedTiles();
            replaced += childResult.replacedTiles();
        }
        return new MergeResult(copied, replaced);
    }

    private Path tilePathFor(Path destinationRoot, String nodeId) {
        Path directory = destinationRoot;
        for (int level = 1; level < nodeId.length(); level++) {
            directory = directory.resolve(nodeId.substring(0, level + 1));
        }
        return directory.resolve(nodeId + ".png");
    }

    public record MergeResult(int copiedTiles, int replacedTiles) {
    }
}
