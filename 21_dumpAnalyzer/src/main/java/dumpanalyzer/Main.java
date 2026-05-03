package dumpanalyzer;

import com.jogamp.opengl.awt.GLCanvas;
import dumpanalyzer.config.Configuration;
import dumpanalyzer.gui.KeyboardInteractionTechnique;
import dumpanalyzer.io.FrameWriter;
import dumpanalyzer.gui.MouseInteractionTechnique;
import dumpanalyzer.io.TracedModelReader;
import dumpanalyzer.model.DumpAnalyzerModel;
import dumpanalyzer.model.Frame;
import dumpanalyzer.model.TileInstance;
import dumpanalyzer.options.CommandLineOptions;
import dumpanalyzer.processing.NeighborDetector;
import dumpanalyzer.render.Jogl4DumpAnalyzerRenderer;
import java.util.List;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;
import vsdk.toolkit.gui.CameraControllerOrbiter;
import vsdk.toolkit.gui.feedback.ProgressMonitor;
import vsdk.toolkit.gui.feedback.ProgressMonitorConsoleLongFormat;

public class Main {
    public static void main(String[] args) {
        CommandLineOptions config = CommandLineOptions.parseArgs(args);

        DumpAnalyzerModel model = new DumpAnalyzerModel();
        model.setSelectedFrameIndex(config.startFrame());
        TracedModelReader tracedModelReader = new TracedModelReader(Configuration.OUTPUT_ROOT, Configuration.MAX_FRAME);
        tracedModelReader.importInto(model);
        System.out.print("\nPreprocessing neighbors:\n");
        System.out.flush();
        preprocessNeighborsAndExport(model, model.snapshotFrames(), config.width(), config.height());
        System.out.println("OK");

        Thread rendererThread = null;
        if (!config.offline()) {
            rendererThread = createRendererThread(model);
            rendererThread.start();
        }

        if (config.offline()) {
            model.setSelectedFrameById(config.startFrame());
            renderOffline(model, config);
        }
    }

    private static Thread createRendererThread(DumpAnalyzerModel model) {
        return new Thread(() -> {
            Jogl4DumpAnalyzerRenderer renderer = new Jogl4DumpAnalyzerRenderer(model, Main::shutdownNow);
            renderer.start((canvas, cameraController, closeAction, repaintAction) ->
                installInteractionTechniques(model, canvas, cameraController, closeAction, repaintAction)
            );
        }, "jogl4-renderer");
    }

    private static void renderOffline(DumpAnalyzerModel model, CommandLineOptions config) {
        try {
            Jogl4DumpAnalyzerRenderer renderer = new Jogl4DumpAnalyzerRenderer(model, () -> {});
            renderer.startOffscreen(config.outputPath(), config.width(), config.height());
        }
        catch (Throwable t) {
            System.out.println(
                "Warning: Offline image export is not available because there is no access to a graphics system."
            );
        }
    }

    private static void installInteractionTechniques(
        DumpAnalyzerModel model,
        GLCanvas canvas,
        CameraControllerOrbiter cameraController,
        Runnable closeAction,
        Runnable repaintAction
    ) {
        KeyboardInteractionTechnique keyboard = new KeyboardInteractionTechnique(
            model,
            closeAction,
            cameraController,
            repaintAction
        );
        MouseInteractionTechnique mouse = new MouseInteractionTechnique(
            cameraController,
            repaintAction,
            canvas::requestFocusInWindow
        );
        canvas.addKeyListener(keyboard);
        canvas.addMouseListener(mouse);
        canvas.addMouseMotionListener(mouse);
        canvas.addMouseWheelListener(mouse);
    }

    private static void shutdownNow() {
        System.exit(0);
    }

    private static void preprocessNeighborsAndExport(
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
            "Stage 1/3 - Assigning texture file paths to tiles",
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
            "Stage 2/3 - Detecting neighbors per frame",
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
        runStageWithProgress(
            "Stage 3/3 - Writing processed frames to disk",
            1,
            i -> FrameWriter.writeFrames(Configuration.OUTPUT_ROOT, frames)
        );
    }

    private static void runStageWithProgress(String stageTitle, int totalWorkUnits, IndexedTask task) {
        int total = Math.max(1, totalWorkUnits);
        System.out.println(stageTitle + " - started");
        ProgressMonitor progressMonitor = new ProgressMonitorConsoleLongFormat();
        progressMonitor.begin();
        for (int i = 0; i < totalWorkUnits; i++) {
            task.run(i);
            progressMonitor.update(0, total, i + 1);
        }
        progressMonitor.end();
        System.out.println(stageTitle + " - finished");
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
