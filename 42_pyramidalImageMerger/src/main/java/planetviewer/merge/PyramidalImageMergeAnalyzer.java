package planetviewer.merge;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import planetviewer.model.PyramidalImage;
import planetviewer.model.QuadtreeNode;

public final class PyramidalImageMergeAnalyzer {
    private int comparedTiles;
    private int mergeableTiles;
    private int copiedTiles;
    private final Set<String> conflictingNodeIds = new LinkedHashSet<>();
    private final Set<Integer> conflictingLevels = new LinkedHashSet<>();
    private final List<String> missingDestinationNodeIds = new ArrayList<>();

    public MergeAnalysis analyze(PyramidalImage destination, PyramidalImage delta) {
        comparedTiles = 0;
        mergeableTiles = 0;
        copiedTiles = 0;
        conflictingNodeIds.clear();
        conflictingLevels.clear();
        missingDestinationNodeIds.clear();
        visit(destination == null ? null : destination.getRoot(), delta == null ? null : delta.getRoot());
        return new MergeAnalysis(
            comparedTiles,
            mergeableTiles,
            copiedTiles,
            new LinkedHashSet<>(conflictingNodeIds),
            new LinkedHashSet<>(conflictingLevels),
            List.copyOf(missingDestinationNodeIds)
        );
    }

    private void visit(QuadtreeNode destinationNode, QuadtreeNode deltaNode) {
        if (deltaNode == null) {
            return;
        }

        if (deltaNode.getTileFile() != null) {
            if (destinationNode == null || destinationNode.getTileFile() == null) {
                mergeableTiles++;
                missingDestinationNodeIds.add(deltaNode.getId());
            }
            else {
                comparedTiles++;
                if (filesAreIdentical(destinationNode, deltaNode)) {
                    mergeableTiles++;
                }
                else {
                    conflictingNodeIds.add(deltaNode.getId());
                    conflictingLevels.add(deltaNode.getDepth());
                }
            }
        }

        if (!deltaNode.hasChildren()) {
            return;
        }
        QuadtreeNode[] deltaChildren = deltaNode.getChildren();
        QuadtreeNode[] destinationChildren = destinationNode != null ? destinationNode.getChildren() : null;
        for (int digit = 0; digit < 4; digit++) {
            QuadtreeNode destinationChild = destinationChildren == null ? null : destinationChildren[digit];
            visit(destinationChild, deltaChildren[digit]);
        }
    }

    private boolean filesAreIdentical(QuadtreeNode destinationNode, QuadtreeNode deltaNode) {
        try {
            return Files.mismatch(
                destinationNode.getTileFile().toPath(),
                deltaNode.getTileFile().toPath()
            ) == -1L;
        }
        catch (IOException ex) {
            conflictingNodeIds.add(deltaNode.getId());
            conflictingLevels.add(deltaNode.getDepth());
            return false;
        }
    }

    public MergeAnalysis markCopied(MergeAnalysis baseAnalysis, int copiedTiles) {
        this.copiedTiles = copiedTiles;
        return new MergeAnalysis(
            baseAnalysis.getComparedTiles(),
            baseAnalysis.getMergeableTiles(),
            copiedTiles,
            baseAnalysis.getConflictingNodeIds(),
            baseAnalysis.getConflictingLevels(),
            baseAnalysis.getCopiedNodeIds()
        );
    }
}
