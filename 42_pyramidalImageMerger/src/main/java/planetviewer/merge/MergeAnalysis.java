package planetviewer.merge;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class MergeAnalysis {
    private final int comparedTiles;
    private final int mergeableTiles;
    private final int copiedTiles;
    private final Set<String> conflictingNodeIds;
    private final Set<Integer> conflictingLevels;
    private final List<String> copiedNodeIds;

    public MergeAnalysis(
        int comparedTiles,
        int mergeableTiles,
        int copiedTiles,
        Set<String> conflictingNodeIds,
        Set<Integer> conflictingLevels,
        List<String> copiedNodeIds
    ) {
        this.comparedTiles = comparedTiles;
        this.mergeableTiles = mergeableTiles;
        this.copiedTiles = copiedTiles;
        this.conflictingNodeIds = Collections.unmodifiableSet(conflictingNodeIds);
        this.conflictingLevels = Collections.unmodifiableSet(conflictingLevels);
        this.copiedNodeIds = Collections.unmodifiableList(copiedNodeIds);
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

    public boolean isMergePossible() {
        return conflictingNodeIds.isEmpty();
    }

    public String summary() {
        if (isMergePossible()) {
            return "Merge ready: green. Compared " + comparedTiles + " overlapping tile(s), delta contributes "
                + mergeableTiles + " mergeable tile(s), conflicts: 0.";
        }
        return "Merge blocked: red. Conflict levels " + conflictingLevels + ", conflict tile(s) "
            + conflictingNodeIds + ".";
    }

    public String mergeCompletedSummary() {
        return "Merge completed. Copied " + copiedTiles + " new tile(s) from delta into destination.";
    }
}
