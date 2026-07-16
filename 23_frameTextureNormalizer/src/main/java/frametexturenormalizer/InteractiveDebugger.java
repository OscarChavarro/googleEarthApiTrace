package frametexturenormalizer;

import com.jogamp.opengl.awt.GLCanvas;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JFrame;
import javax.swing.WindowConstants;
import frametexturenormalizer.animation.AnimationController;
import frametexturenormalizer.gui.KeyboardInteractionTechniques;
import frametexturenormalizer.gui.MouseOrbiterInteraction;
import frametexturenormalizer.model.state.FrameTextureNormalizerState;
import frametexturenormalizer.render.Jogl4FrameTextureNormalizerRenderer;
import vsdk.toolkit.gui.CameraControllerOrbiter;

public final class InteractiveDebugger {
    private InteractiveDebugger() {
    }

    public static void runDesktopGui(FrameTextureNormalizerState model) {
        Jogl4FrameTextureNormalizerRenderer renderer = new Jogl4FrameTextureNormalizerRenderer(model);
        GLCanvas canvas = renderer.createCanvas();

        JFrame frame = new JFrame("Frame texture normalizer - exports matrix.json");
        frame.setLayout(new BorderLayout());
        frame.add(canvas, BorderLayout.CENTER);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(960, 600));
        frame.setSize(new Dimension(1280, 720));
        AtomicBoolean closing = new AtomicBoolean(false);
        Runnable closeAction = () -> {
            if (!closing.compareAndSet(false, true)) {
                return;
            }
            frame.setTitle("Frame texture normalizer - saving edits...");
            frame.setEnabled(false);
            Thread shutdownThread = new Thread(() -> {
                if (model != null) {
                    model.flushPendingFrameJsonChanges();
                }
                frame.dispose();
                System.exit(0);
            }, "frame-json-save-on-exit");
            shutdownThread.start();
        };
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeAction.run();
            }
        });

        CameraControllerOrbiter cameraController = renderer.getCameraController();
        AnimationController animationController = new AnimationController(model, canvas::display);
        MouseOrbiterInteraction mouse = new MouseOrbiterInteraction(
            cameraController,
            canvas::display,
            canvas::requestFocusInWindow,
            (x, y, expandConnected) -> {
                final boolean[] changed = new boolean[] {false};
                canvas.invoke(false, drawable -> {
                    changed[0] = renderer.selectTileAt(drawable, x, y, expandConnected);
                    return true;
                });
                return changed[0];
            }
        );
        KeyboardInteractionTechniques keyboard = new KeyboardInteractionTechniques(
            model,
            closeAction,
            cameraController,
            canvas::display,
            animationController
        );
        canvas.addMouseListener(mouse);
        canvas.addMouseMotionListener(mouse);
        canvas.addMouseWheelListener(mouse);
        canvas.addKeyListener(keyboard);
        canvas.setFocusable(true);

        frame.setVisible(true);
        canvas.requestFocusInWindow();
        canvas.display();
    }
}
