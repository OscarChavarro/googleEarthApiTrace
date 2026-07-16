package matrixmerger.processing;

import java.util.LinkedHashSet;
import java.util.Set;
import matrixmerger.model.contract.FrameMatrixSet;
import matrixmerger.model.contract.FrameTileMatrix;
import matrixmerger.model.state.MatrixMergerState;

public final class AutomaticMatrixGroupingPipeline {
    public void run(MatrixMergerState model) {
        if (model == null) {
            return;
        }

        Set<String> inputTileIds = tileIds(model);
        model.selectFrameIndex(0);
        while (true) {
            int before = model.getFrameCount();
            boolean countChanged = false;
            countChanged |= runRetryMergeSweep(model);
            countChanged |= runCutSweep(model);
            if (!countChanged && model.getFrameCount() == before) {
                model.sortFramesByUncleHierarchy();
                new VisualHierarchyRelationshipInferrer().inferMissingParents(model);
                assertTileSetConserved(inputTileIds, tileIds(model));
                return;
            }
        }
    }

    private static Set<String> tileIds(MatrixMergerState model) {
        Set<String> ids = new LinkedHashSet<>();
        for (FrameMatrixSet frame : model.getFrameMatrices()) {
            if (frame == null || frame.getMatrices() == null) {
                continue;
            }
            for (FrameTileMatrix matrix : frame.getMatrices()) {
                if (matrix == null || matrix.getTiles() == null) {
                    continue;
                }
                for (FrameTileMatrix.TileCoord tile : matrix.getTiles()) {
                    if (tile != null && tile.getId() != null && !tile.getId().isBlank()) {
                        ids.add(tile.getId());
                    }
                }
            }
        }
        return ids;
    }

    private static void assertTileSetConserved(Set<String> before, Set<String> after) {
        Set<String> missing = new LinkedHashSet<>(before);
        missing.removeAll(after);
        Set<String> added = new LinkedHashSet<>(after);
        added.removeAll(before);
        if (!missing.isEmpty() || !added.isEmpty()) {
            throw new IllegalStateException(
                "Automatic grouping changed the native tile-id set: missing="
                    + sample(missing) + ", added=" + sample(added)
            );
        }
        System.out.println(
            "AutomaticMatrixGroupingPipeline: tile-set conservation OK ("
                + after.size() + " unique native tile ids)."
        );
    }

    private static Set<String> sample(Set<String> values) {
        return values.stream().limit(20).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean runRetryMergeSweep(MatrixMergerState model) {
        System.out.print("Running automatic retry-merge sweep... ");
        boolean countChanged = false;
        int index = 0;
        while (index < model.getFrameCount()) {
            model.selectFrameIndex(index);
            int selectedFrameId = model.getSelectedFrameId();
            int before = model.getFrameCount();
            boolean opChanged = model.retryMergeSelectedMatrixWithNextFrames();
            if (opChanged) {
                if (model.getFrameCount() != before) {
                    countChanged = true;
                }
                System.out.println(
                    "merge: selected frame "
                        + model.getSelectedFrameLabel()
                        + " (started at "
                        + formatFrameId(selectedFrameId)
                        + "), frames "
                        + before
                        + " -> "
                        + model.getFrameCount()
                );
            }
            index++;
        }
        model.selectFrameIndex(0);
        System.out.println("OK");
        return countChanged;
    }

    private boolean runCutSweep(MatrixMergerState model) {
        System.out.print("Running automatic west-cutter sweep... ");
        boolean countChanged = false;
        int index = 0;
        while (index < model.getFrameCount()) {
            model.selectFrameIndex(index);
            String selectedFrameLabel = model.getSelectedFrameLabel();
            int before = model.getFrameCount();
            boolean opChanged = model.splitSelectedFrameByWestCutters();
            if (opChanged) {
                if (model.getFrameCount() != before) {
                    countChanged = true;
                }
                System.out.println(
                    "cut: selected frame "
                        + selectedFrameLabel
                        + ", frames "
                        + before
                        + " -> "
                        + model.getFrameCount()
                );
            }
            index++;
        }
        model.selectFrameIndex(0);
        System.out.println("OK");
        return countChanged;
    }

    private static String formatFrameId(int frameId) {
        return frameId < 0 ? "transient" : Integer.toString(frameId);
    }
}
