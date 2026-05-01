package dumpanalyzer;

import com.jogamp.opengl.awt.GLCanvas;
import dumpanalyzer.config.Configuration;
import dumpanalyzer.gui.KeyboardInteractionTechnique;
import dumpanalyzer.io.FrameWriter;
import dumpanalyzer.gui.MouseInteractionTechnique;
import dumpanalyzer.io.TracedModelReader;
import dumpanalyzer.model.DumpAnalyzerModel;
import dumpanalyzer.options.CommandLineOptions;
import dumpanalyzer.render.Jogl4DumpAnalyzerRenderer;
import vsdk.toolkit.gui.CameraControllerOrbiter;

public class Main {
    public static void main(String[] args) {
        CommandLineOptions config = CommandLineOptions.parseArgs(args);
        int workerCount = Runtime.getRuntime().availableProcessors();

        DumpAnalyzerModel model = new DumpAnalyzerModel();
        model.setSelectedFrameIndex(config.startFrame());
        TracedModelReader tracedModelReader = new TracedModelReader(Configuration.OUTPUT_ROOT, Configuration.MAX_FRAME);
        tracedModelReader.importInto(model, workerCount);

        Thread rendererThread = null;
        if (!config.offline()) {
            rendererThread = createRendererThread(model);
            rendererThread.start();
        }
        FrameWriter.writeFrames(Configuration.OUTPUT_ROOT, model.snapshotFrames());

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
        Jogl4DumpAnalyzerRenderer renderer = new Jogl4DumpAnalyzerRenderer(model, () -> {});
        renderer.startOffscreen(config.outputPath(), config.width(), config.height());
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

}
