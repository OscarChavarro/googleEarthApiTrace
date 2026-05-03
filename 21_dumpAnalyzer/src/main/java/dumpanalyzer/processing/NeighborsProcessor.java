package dumpanalyzer.processing;

import dumpanalyzer.config.Configuration;
import dumpanalyzer.io.FrameWriter;
import dumpanalyzer.model.DumpAnalyzerModel;
import dumpanalyzer.model.Frame;
import dumpanalyzer.model.TileInstance;
import java.util.List;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;
import vsdk.toolkit.gui.feedback.ProgressMonitor;
import vsdk.toolkit.gui.feedback.ProgressMonitorConsoleLongFormat;

public final class NeighborsProcessor {
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
            "\n[3/5] Assigning texture file paths to tiles:",
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
            "\n[4/5] Detecting neighbors per frame:",
            frames.size(),
            i -> {
                Frame frame = frames.get(i);
                Matrix4x4 projection = matrixFromColumnMajor(frame == null ? null : frame.getProjectionMatrix());
                if (projection == null) {
                    projection = Matrix4x4.identityMatrix();
                }
                double[] frameModelView = frame == null ? null : frame.getModelViewMatrix();
                NeighborDetector.populateNeighbors(frame, projection, width, height, frameModelView, true);
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

    private static Matrix4x4 matrixFromColumnMajor(double[] m) {
        if (m == null || m.length != 16) {
            return null;
        }
        Matrix4x4 out = new Matrix4x4();
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                out = out.withVal(row, col, m[col * 4 + row]);
            }
        }
        return out;
    }
}

