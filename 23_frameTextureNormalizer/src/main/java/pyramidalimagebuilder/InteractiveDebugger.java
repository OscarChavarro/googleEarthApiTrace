package pyramidalimagebuilder;

import com.jogamp.opengl.awt.GLCanvas;
import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.JFrame;
import javax.swing.WindowConstants;
import pyramidalimagebuilder.gui.KeyboardInteractionTechniques;
import pyramidalimagebuilder.gui.MouseOrbiterInteraction;
import pyramidalimagebuilder.model.PyramidalImageModel;
import pyramidalimagebuilder.render.Jogl4PyramidalImageBuilderRenderer;
import vsdk.toolkit.gui.CameraControllerOrbiter;

public final class InteractiveDebugger {
    private InteractiveDebugger() {
    }

    public static void runDesktopGui(PyramidalImageModel model, Runnable reloadTileMatrices) {
        Jogl4PyramidalImageBuilderRenderer renderer = new Jogl4PyramidalImageBuilderRenderer(model);
        GLCanvas canvas = renderer.createCanvas();

        JFrame frame = new JFrame("pyramidalImageBuilder - Orbiter Skeleton");
        frame.setLayout(new BorderLayout());
        frame.add(canvas, BorderLayout.CENTER);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(960, 600));
        frame.setSize(new Dimension(1280, 720));

        CameraControllerOrbiter cameraController = renderer.getCameraController();
        MouseOrbiterInteraction mouse = new MouseOrbiterInteraction(
            cameraController,
            canvas::display,
            canvas::requestFocusInWindow,
            (x, y) -> {
                final boolean[] changed = new boolean[] {false};
                canvas.invoke(false, drawable -> {
                    changed[0] = renderer.toggleTileSelectionAt(drawable, x, y);
                    return true;
                });
                return changed[0];
            }
        );
        KeyboardInteractionTechniques keyboard = new KeyboardInteractionTechniques(
            model,
            frame::dispose,
            cameraController,
            canvas::display,
            reloadTileMatrices
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
