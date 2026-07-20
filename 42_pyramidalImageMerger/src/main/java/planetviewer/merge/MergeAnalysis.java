package planetviewer.merge;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MergeAnalysis {
    private final int comparedTiles;
    private final int mergeableTiles;
    private final int copiedTiles;
    private final Set<String> conflictingNodeIds;
    private final Set<Integer> conflictingLevels;
    private final List<String> copiedNodeIds;
    private final Set<String> resolutionEquivalentNodeIds;
    private final Set<String> higherResolutionDeltaNodeIds;
    private final int replacedTiles;
    private final Map<String, String> conflictDetails;
    private final Map<String, Double> imageDistances;

    public MergeAnalysis(
        int comparedTiles,
        int mergeableTiles,
        int copiedTiles,
        Set<String> conflictingNodeIds,
        Set<Integer> conflictingLevels,
        List<String> copiedNodeIds,
        Set<String> resolutionEquivalentNodeIds,
        Set<String> higherResolutionDeltaNodeIds,
        int replacedTiles,
        Map<String, String> conflictDetails,
        Map<String, Double> imageDistances
    ) {
        this.comparedTiles = comparedTiles;
        this.mergeableTiles = mergeableTiles;
        this.copiedTiles = copiedTiles;
        this.conflictingNodeIds = Collections.unmodifiableSet(conflictingNodeIds);
        this.conflictingLevels = Collections.unmodifiableSet(conflictingLevels);
        this.copiedNodeIds = Collections.unmodifiableList(copiedNodeIds);
        this.resolutionEquivalentNodeIds = Collections.unmodifiableSet(resolutionEquivalentNodeIds);
        this.higherResolutionDeltaNodeIds = Collections.unmodifiableSet(higherResolutionDeltaNodeIds);
        this.replacedTiles = replacedTiles;
        this.conflictDetails = Collections.unmodifiableMap(conflictDetails);
        this.imageDistances = Collections.unmodifiableMap(imageDistances);
    }

    public int getComparedTiles() {
        return comparedTiles;
    }

    public int getMergeableTiles() {
        return mergeableTiles;
    }

    public Set<String> getConflictingNodeIds() {
        return conflictingNodeIds;
    }

    public Set<Integer> getConflictingLevels() {
        return conflictingLevels;
    }

    public int getCopiedTiles() {
        return copiedTiles;
    }

    public List<String> getCopiedNodeIds() {
        return copiedNodeIds;
    }

    public int getConflictCount() {
        return conflictingNodeIds.size();
    }

    public Set<String> getResolutionEquivalentNodeIds() {
        return resolutionEquivalentNodeIds;
    }

    public Set<String> getHigherResolutionDeltaNodeIds() {
        return higherResolutionDeltaNodeIds;
    }

    public int getReplacedTiles() {
        return replacedTiles;
    }

    public Map<String, String> getConflictDetails() {
        return conflictDetails;
    }

    public Map<String, Double> getImageDistances() {
        return imageDistances;
    }

    public boolean isMergePossible() {
        return conflictingNodeIds.isEmpty();
    }

    public String summary() {
        if (isMergePossible()) {
            return "Merge ready: green. Compared " + comparedTiles + " overlapping tile(s), delta contributes "
                + mergeableTiles + " mergeable tile(s), resolution matches: "
                + resolutionEquivalentNodeIds.size() + ", conflicts: 0.";
        }
        return "Merge blocked: red. Conflict levels " + conflictingLevels + ", conflict tile(s) "
            + conflictingNodeIds + ".";
    }

    public String mergeCompletedSummary() {
        return "Merge completed. Copied " + copiedTiles + " new tile(s) and replaced " + replacedTiles
            + " lower-resolution tile(s) in destination.";
    }
}
