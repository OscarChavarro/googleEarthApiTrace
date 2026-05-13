package pyramidalimageexporter;

import com.jogamp.opengl.awt.GLCanvas;
import java.applet.Applet;
import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import pyramidalimageexporter.gui.KeyboardInteractionTechniques;
import pyramidalimageexporter.gui.MouseInteractionTechnique;
import pyramidalimageexporter.model.PyramidalImageExporterModel;
import pyramidalimageexporter.render.Jogl4PyramidalImageExporterRenderer;

@SuppressWarnings("removal")
public class InteractiveDebugger extends Applet {
    private final PyramidalImageExporterModel model;
    private final Jogl4PyramidalImageExporterRenderer renderer;

    private boolean closing;
    private KeyboardInteractionTechniques keyboardInteraction;
    private MouseInteractionTechnique mouseInteraction;
    private GLCanvas canvas;
    private JFrame frame;

    public InteractiveDebugger(PyramidalImageExporterModel model, Jogl4PyramidalImageExporterRenderer renderer) {
        this.model = model;
        this.renderer = renderer;
    }

    public void launchDesktop() {
        fillGuiWithCanvas();

        frame = new JFrame("pyramidalImageExporter - Interactive Viewer");
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
            renderer.getCameraController(),
            canvas::repaint,
            canvas::requestFocusInWindow
        );
        canvas.addMouseListener(mouseInteraction);
        canvas.addMouseMotionListener(mouseInteraction);
        canvas.addMouseWheelListener(mouseInteraction);
        keyboardInteraction = new KeyboardInteractionTechniques(
            model,
            this::requestClose,
            renderer.getCameraController(),
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
                System.out.println("InteractiveDebugger: error while destroying canvas: " + t.getMessage());
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
