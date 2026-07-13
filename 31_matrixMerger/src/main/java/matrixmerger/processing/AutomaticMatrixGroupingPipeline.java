package matrixmerger.processing;

import matrixmerger.model.state.MatrixMergerState;

public final class AutomaticMatrixGroupingPipeline {
    public void run(MatrixMergerState model) {
        if (model == null) {
            return;
        }

        model.selectFrameIndex(0);
        while (true) {
            int before = model.getFrameCount();
            boolean countChanged = false;
            countChanged |= runRetryMergeSweep(model);
            countChanged |= runCutSweep(model);
            if (!countChanged && model.getFrameCount() == before) {
                model.sortFramesByUncleHierarchy();
                new VisualHierarchyRelationshipInferrer().inferMissingParents(model);
                return;
            }
        }
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
