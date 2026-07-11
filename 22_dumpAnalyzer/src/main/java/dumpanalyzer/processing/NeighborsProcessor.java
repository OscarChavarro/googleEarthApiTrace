package dumpanalyzer.processing;

import dumpanalyzer.config.Configuration;
import dumpanalyzer.io.FrameWriter;
import dumpanalyzer.model.DumpAnalyzerModel;
import dumpanalyzer.model.Frame;
import dumpanalyzer.model.TileInstance;
import dumpanalyzer.processing.uncles.UncleDetector;
import java.util.List;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.gui.feedback.ProgressMonitor;
import vsdk.toolkit.gui.feedback.ProgressMonitorConsoleLongFormat;

public final class NeighborsProcessor {
    private static final UncleDetector UNCLE_DETECTOR = new UncleDetector();
    private static final int DEBUG_FRAME_ID = 50;

    private NeighborsProcessor() {
    }

    public static void preprocessNeighbors(
        DumpAnalyzerModel model,
        List<Frame> frames,
        int viewportWidth,
        int viewportHeight
    ) {
        if (frames == null || frames.isEmpty()) {
            return;
        }
        int width = Math.max(1, viewportWidth);
        int height = Math.max(1, viewportHeight);
        runStageWithProgress(
            "\n[2/5] Assigning texture file paths to tiles:",
            frames.size(),
            i -> {
                Frame frame = frames.get(i);
                if (frame == null) {
                    return;
                }
                for (TileInstance tile : frame.getTiles()) {
                    if (tile == null) {
                        continue;
                    }
                    String texturePath = model.getTexturePath(frame.getId(), tile.getContentId());
                    tile.setTextureFile(texturePath);
                }
            }
        );
        runStageWithProgress(
            "\n[3/5] Detecting neighbors per frame:",
            frames.size(),
            i -> {
                Frame frame = frames.get(i);
                if (frame != null) {
                    frame = VisualTilePositioner.reorderFrame(frame, width, height);
                    frames.set(i, frame);
                    model.replaceFrame(frame);
                }
                Matrix4x4d projection = matrixFromColumnMajor(frame == null ? null : frame.getProjectionMatrix());
                if (projection == null) {
                    projection = Matrix4x4d.identityMatrix();
                }
                double[] frameModelView = frame == null ? null : frame.getModelViewMatrix();
                TriangleStripNeighborDetector.populateNeighbors(frame, projection, width, height, frameModelView, true);
                populateUncles(frame);
                debugFrame(frame);
            }
        );
        FrameWriter.writeFramesParallelWithProgress(Configuration.OUTPUT_ROOT, frames);
    }

    private static void runStageWithProgress(String stageTitle, int totalWorkUnits, IndexedTask task) {
        int total = Math.max(1, totalWorkUnits);
        System.out.println(stageTitle);
        ProgressMonitor progressMonitor = new ProgressMonitorConsoleLongFormat();
        progressMonitor.begin();
        for (int i = 0; i < totalWorkUnits; i++) {
            task.run(i);
            progressMonitor.update(0, total, i + 1);
        }
        progressMonitor.end();
    }

    @FunctionalInterface
    private interface IndexedTask {
        void run(int index);
    }

    private static Matrix4x4d matrixFromColumnMajor(double[] m) {
        if (m == null || m.length != 16) {
            return null;
        }
        Matrix4x4d out = new Matrix4x4d();
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                out = out.withVal(row, col, m[col * 4 + row]);
            }
        }
        return out;
    }

    private static void populateUncles(Frame frame) {
        if (frame == null) {
            return;
        }
        for (TileInstance tile : frame.getTiles()) {
            if (tile == null) {
                continue;
            }
            if (hasAnyMissingCardinalNeighbor(tile)) {
                tile.setUncles(UNCLE_DETECTOR.detect(frame, tile));
            }
            else {
                tile.setUncles(List.of());
            }
        }
    }

    private static boolean hasAnyMissingCardinalNeighbor(TileInstance tile) {
        return tile.getSouthNeighbor() == null
            || tile.getNorthNeighbor() == null
            || tile.getEastNeighbor() == null
            || tile.getWestNeighbor() == null;
    }

    private static void debugFrame(Frame frame) {
        if (frame == null || frame.getId() != DEBUG_FRAME_ID) {
            return;
        }
        for (TileInstance tile : frame.getTiles()) {
            if (tile == null || !hasAnyMissingCardinalNeighbor(tile)) {
                continue;
            }
        }
    }
}
