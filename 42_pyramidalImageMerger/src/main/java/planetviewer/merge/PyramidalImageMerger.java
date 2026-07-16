package planetviewer.merge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import planetviewer.model.PyramidalImage;
import planetviewer.model.QuadtreeNode;

public final class PyramidalImageMerger {
    public MergeResult mergeTiles(PyramidalImage destination, PyramidalImage delta, Set<String> replacementNodeIds) throws IOException {
        if (destination == null || delta == null) {
            return new MergeResult(0, 0);
        }
        Path destinationRoot = Path.of(destination.getSourceFolder());
        FolderLayout destinationLayout = detectDestinationLayout(destinationRoot);
        return mergeRecursive(destinationRoot, destinationLayout, destination.getRoot(), delta.getRoot(), replacementNodeIds);
    }

    private MergeResult mergeRecursive(
        Path destinationRoot,
        FolderLayout destinationLayout,
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
            Path destinationTilePath = tilePathFor(destinationRoot, deltaNode.getId(), destinationLayout);
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
            MergeResult childResult = mergeRecursive(
                destinationRoot,
                destinationLayout,
                destinationChild,
                deltaChildren[digit],
                replacementNodeIds
            );
            copied += childResult.copiedTiles();
            replaced += childResult.replacedTiles();
        }
        return new MergeResult(copied, replaced);
    }

    private Path tilePathFor(Path destinationRoot, String nodeId, FolderLayout layout) {
        Path directory = destinationRoot;
        for (int index = 1; index < nodeId.length(); index++) {
            String segment = layout == FolderLayout.PER_DIGIT
                ? String.valueOf(nodeId.charAt(index))
                : nodeId.substring(0, index + 1);
            directory = directory.resolve(segment);
        }
        return directory.resolve(nodeId + ".png");
    }

    private FolderLayout detectDestinationLayout(Path destinationRoot) {
        for (int digit = 0; digit < 4; digit++) {
            if (Files.isDirectory(destinationRoot.resolve(Integer.toString(digit)))) {
                return FolderLayout.PER_DIGIT;
            }
        }
        for (int digit = 0; digit < 4; digit++) {
            if (Files.isDirectory(destinationRoot.resolve("0" + digit))) {
                return FolderLayout.CUMULATIVE;
            }
        }
        return FolderLayout.PER_DIGIT;
    }

    /**
     * Checks the structural merge postcondition after the destination has been
     * rescanned: every delta tile must be visible at the same quadtree id.
     */
    public List<String> findMissingDeltaTileIds(PyramidalImage destination, PyramidalImage delta) {
        List<String> missingIds = new ArrayList<>();
        findMissingDeltaTileIds(
            destination == null ? null : destination.getRoot(),
            delta == null ? null : delta.getRoot(),
            missingIds
        );
        return List.copyOf(missingIds);
    }

    private void findMissingDeltaTileIds(
        QuadtreeNode destinationNode,
        QuadtreeNode deltaNode,
        List<String> missingIds
    ) {
        if (deltaNode == null) {
            return;
        }
        if (deltaNode.getTileFile() != null
            && (destinationNode == null || destinationNode.getTileFile() == null)) {
            missingIds.add(deltaNode.getId());
        }
        if (!deltaNode.hasChildren()) {
            return;
        }
        QuadtreeNode[] deltaChildren = deltaNode.getChildren();
        QuadtreeNode[] destinationChildren = destinationNode == null ? null : destinationNode.getChildren();
        for (int digit = 0; digit < 4; digit++) {
            QuadtreeNode destinationChild = destinationChildren == null ? null : destinationChildren[digit];
            findMissingDeltaTileIds(destinationChild, deltaChildren[digit], missingIds);
        }
    }

    private enum FolderLayout {
        PER_DIGIT,
        CUMULATIVE
    }

    public record MergeResult(int copiedTiles, int replacedTiles) {
    }
}
