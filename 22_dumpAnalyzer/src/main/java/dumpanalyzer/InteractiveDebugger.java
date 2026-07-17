package dumpanalyzer;

import com.jogamp.opengl.awt.GLCanvas;
import dumpanalyzer.gui.KeyboardInteractionTechnique;
import dumpanalyzer.gui.MouseInteractionTechnique;
import dumpanalyzer.model.state.DumpAnalyzerState;
import dumpanalyzer.render.Jogl4DumpAnalyzerRenderer;
import vsdk.toolkit.gui.CameraControllerOrbiter;

public final class InteractiveDebugger {
    private InteractiveDebugger() {
    }

    public static void start(DumpAnalyzerState model) {
        Thread rendererThread = createRendererThread(model);
        rendererThread.start();
    }

    private static Thread createRendererThread(DumpAnalyzerState model) {
        return new Thread(() -> {
            try {
                Jogl4DumpAnalyzerRenderer renderer = new Jogl4DumpAnalyzerRenderer(model, InteractiveDebugger::shutdownNow);
                renderer.start((canvas, cameraController, closeAction, repaintAction) ->
                    installInteractionTechniques(model, canvas, cameraController, closeAction, repaintAction)
                );
            }
            catch (Throwable t) {
                System.err.println("Interactive renderer failed to start: " + t.getClass().getName() + ": " + t.getMessage());
                t.printStackTrace(System.err);
            }
        }, "jogl4-renderer");
    }

    private static void installInteractionTechniques(
        DumpAnalyzerState model,
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
        // Closing the Swing frame and GLCanvas is enough for the desktop JVM to exit.
    }
}
