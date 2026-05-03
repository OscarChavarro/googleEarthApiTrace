package matrixmerger;

import com.jogamp.opengl.awt.GLCanvas;
import java.applet.Applet;
import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.JFrame;
import javax.swing.WindowConstants;
import matrixmerger.gui.KeyboardInteractionTechniques;
import matrixmerger.gui.MouseInteractionTechnique;
import matrixmerger.model.MatrixMergerModel;
import matrixmerger.render.Jogl4MatrixMergerRenderer;
import vsdk.toolkit.gui.CameraControllerOrbiter;

@SuppressWarnings("removal")
public class InteractiveDebugger extends Applet {
    private final MatrixMergerModel model;
    private final CameraControllerOrbiter cameraController;
    private final Jogl4MatrixMergerRenderer renderer;

    private boolean closing;
    private KeyboardInteractionTechniques keyboardInteraction;
    private MouseInteractionTechnique mouseInteraction;
    private GLCanvas canvas;
    private JFrame frame;

    public InteractiveDebugger(
        MatrixMergerModel model,
        CameraControllerOrbiter cameraController,
        Jogl4MatrixMergerRenderer renderer
    ) {
        this.model = model;
        this.cameraController = cameraController;
        this.renderer = renderer;
    }

    public void launchDesktop() {
        fillGuiWithCanvas();

        frame = new JFrame("matrixMerger - Interactive Viewer");
        frame.add(canvas, BorderLayout.CENTER);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        Dimension size = new Dimension(960, 600);
        frame.setMinimumSize(size);
        frame.setSize(new Dimension(1280, 720));
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                requestClose();
            }
        });
        frame.setVisible(true);
        canvas.requestFocusInWindow();
    }

    @Override
    public void init() {
        fillGuiWithCanvas();
        setLayout(new BorderLayout());
        add("Center", canvas);
    }

    private void fillGuiWithCanvas() {
        canvas = renderer.createCanvas();
        mouseInteraction = new MouseInteractionTechnique(
            model,
            canvas::repaint,
            canvas::requestFocusInWindow
        );
        canvas.addMouseListener(mouseInteraction);
        canvas.addMouseMotionListener(mouseInteraction);
        canvas.addMouseWheelListener(mouseInteraction);
        keyboardInteraction = new KeyboardInteractionTechniques(
            this::requestClose,
            cameraController,
            canvas::repaint
        );
        canvas.addKeyListener(keyboardInteraction);
        canvas.setFocusable(true);
    }

    private void requestClose() {
        if (closing) {
            return;
        }
        closing = true;

        if (canvas != null) {
            canvas.destroy();
        }
        if (frame != null) {
            frame.dispose();
        }

        System.exit(0);
    }
}
