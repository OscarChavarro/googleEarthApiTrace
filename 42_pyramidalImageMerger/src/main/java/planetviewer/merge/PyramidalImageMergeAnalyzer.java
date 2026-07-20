package planetviewer.merge;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private final Set<String> resolutionEquivalentNodeIds = new LinkedHashSet<>();
    private final Set<String> higherResolutionDeltaNodeIds = new LinkedHashSet<>();
    private final Map<String, String> conflictDetails = new LinkedHashMap<>();
    private final Map<String, Double> imageDistances = new LinkedHashMap<>();
    private final ImageMagickImageComparator imageComparator = new ImageMagickImageComparator();

    public MergeAnalysis analyze(PyramidalImage destination, PyramidalImage delta) {
        comparedTiles = 0;
        mergeableTiles = 0;
        copiedTiles = 0;
        conflictingNodeIds.clear();
        conflictingLevels.clear();
        missingDestinationNodeIds.clear();
        resolutionEquivalentNodeIds.clear();
        higherResolutionDeltaNodeIds.clear();
        conflictDetails.clear();
        imageDistances.clear();
        visit(destination == null ? null : destination.getRoot(), delta == null ? null : delta.getRoot());
        return new MergeAnalysis(
            comparedTiles,
            mergeableTiles,
            copiedTiles,
            new LinkedHashSet<>(conflictingNodeIds),
            new LinkedHashSet<>(conflictingLevels),
            List.copyOf(missingDestinationNodeIds),
            new LinkedHashSet<>(resolutionEquivalentNodeIds),
            new LinkedHashSet<>(higherResolutionDeltaNodeIds),
            0,
            new LinkedHashMap<>(conflictDetails),
            new LinkedHashMap<>(imageDistances)
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
                    imageDistances.put(deltaNode.getId(), 0.0);
                    mergeableTiles++;
                }
                else if (imagesAreEquivalentAtDifferentResolution(destinationNode, deltaNode)) {
                    mergeableTiles++;
                    resolutionEquivalentNodeIds.add(deltaNode.getId());
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

    private boolean imagesAreEquivalentAtDifferentResolution(QuadtreeNode destinationNode, QuadtreeNode deltaNode) {
        try {
            ImageMagickImageComparator.Comparison comparison = imageComparator.compare(
                destinationNode.getTileFile().toPath(),
                deltaNode.getTileFile().toPath()
            );
            imageDistances.put(deltaNode.getId(), comparison.normalizedRmse());
            if (comparison.visuallyEquivalent() && comparison.deltaIsHigherResolution()) {
                higherResolutionDeltaNodeIds.add(deltaNode.getId());
            }
            if (!comparison.visuallyEquivalent()) {
                conflictDetails.put(
                    deltaNode.getId(),
                    "normalized RMSE " + comparison.normalizedRmse()
                        + " exceeds 0.03; delta higher resolution: " + comparison.deltaIsHigherResolution()
                );
            }
            return comparison.visuallyEquivalent();
        }
        catch (IOException ex) {
            conflictDetails.put(deltaNode.getId(), "ImageMagick comparison failed: " + ex.getMessage());
            return false;
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            conflictDetails.put(deltaNode.getId(), "ImageMagick comparison interrupted");
            return false;
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
            return false;
        }
    }

    public MergeAnalysis markMerged(MergeAnalysis baseAnalysis, int copiedTiles, int replacedTiles) {
        this.copiedTiles = copiedTiles;
        return new MergeAnalysis(
            baseAnalysis.getComparedTiles(),
            baseAnalysis.getMergeableTiles(),
            copiedTiles,
            baseAnalysis.getConflictingNodeIds(),
            baseAnalysis.getConflictingLevels(),
            baseAnalysis.getCopiedNodeIds(),
            baseAnalysis.getResolutionEquivalentNodeIds(),
            baseAnalysis.getHigherResolutionDeltaNodeIds(),
            replacedTiles,
            baseAnalysis.getConflictDetails(),
            baseAnalysis.getImageDistances()
        );
    }
}
