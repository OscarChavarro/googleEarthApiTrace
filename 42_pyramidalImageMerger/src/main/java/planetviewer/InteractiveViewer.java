package planetviewer;

import com.jogamp.opengl.awt.GLCanvas;
import java.applet.Applet;
import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import planetviewer.animation.RepaintScheduler;
import planetviewer.gui.MergerKeyboardInteractionTechniques;
import planetviewer.gui.MouseInteractionTechnique;
import planetviewer.model.PlanetViewerModel;
import planetviewer.render.Jogl4PyramidalImageMergerRenderer;

@SuppressWarnings("removal")
public class InteractiveViewer extends Applet {
    private final PlanetViewerModel model;
    private final Jogl4PyramidalImageMergerRenderer renderer;

    private boolean closing;
    private MergerKeyboardInteractionTechniques keyboardInteraction;
    private MouseInteractionTechnique mouseInteraction;
    private GLCanvas canvas;
    private JFrame frame;

    public InteractiveViewer(PlanetViewerModel model, Jogl4PyramidalImageMergerRenderer renderer) {
        this.model = model;
        this.renderer = renderer;
    }

    public void launchDesktop() {
        fillGuiWithCanvas();

        frame = new JFrame("planetViewer - Interactive Viewer");
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
        RepaintScheduler repaintScheduler = new RepaintScheduler(canvas::repaint);
        renderer.setRepaintOnTileReady(repaintScheduler::requestRepaint);
        mouseInteraction = new MouseInteractionTechnique(
            renderer.getCameraController(),
            (x, y, width, height) -> { },
            canvas::repaint,
            canvas::requestFocusInWindow
        );
        canvas.addMouseListener(mouseInteraction);
        canvas.addMouseMotionListener(mouseInteraction);
        canvas.addMouseWheelListener(mouseInteraction);
        keyboardInteraction = new MergerKeyboardInteractionTechniques(
            model,
            this::requestClose,
            renderer.getCameraController(),
            canvas::repaint,
            renderer::analyzeMerge
        );
        canvas.addKeyListener(keyboardInteraction);
        canvas.setFocusable(true);
    }

    private void requestClose() {
        if (closing) {
            return;
        }
        closing = true;
        Runnable closeTask = () -> {
            try {
                if (canvas != null) {
                    canvas.setVisible(false);
                    canvas.removeMouseListener(mouseInteraction);
                    canvas.removeMouseMotionListener(mouseInteraction);
                    canvas.removeMouseWheelListener(mouseInteraction);
                    canvas.removeKeyListener(keyboardInteraction);
                    canvas.destroy();
                }
            }
            catch (Throwable t) {
                System.out.println("InteractiveViewer: error while destroying canvas: " + t.getMessage());
            }
            finally {
                if (frame != null) {
                    frame.dispose();
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            closeTask.run();
        }
        else {
            SwingUtilities.invokeLater(closeTask);
        }
    }
}
