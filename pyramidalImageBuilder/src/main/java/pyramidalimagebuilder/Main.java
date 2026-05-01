package pyramidalimagebuilder;

import com.jogamp.opengl.awt.GLCanvas;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.nio.file.Path;
import javax.swing.JFrame;
import javax.swing.WindowConstants;
import pyramidalimagebuilder.config.Configuration;
import pyramidalimagebuilder.gui.KeyboardInteractionTechniques;
import pyramidalimagebuilder.gui.MouseOrbiterInteraction;
import pyramidalimagebuilder.io.TraceSessionReader;
import pyramidalimagebuilder.model.PyramidalImageModel;
import pyramidalimagebuilder.processing.TileInstancesMerger;
import pyramidalimagebuilder.render.Jogl4PyramidalImageBuilderRenderer;
import vsdk.toolkit.gui.CameraControllerOrbiter;

public class Main {
    public static void main(String[] args) {
        PyramidalImageModel model = new PyramidalImageModel();

        // Stage 1: read tile instances from trace session.
        TraceSessionReader traceSessionReader = new TraceSessionReader();
        model.setTileInstances(traceSessionReader.readSession(Path.of(Configuration.INPUT_PATH)));

        // Stage 2: merge tile instances using neighbor hints consistency.
        TileInstancesMerger tileInstancesMerger = new TileInstancesMerger();
        tileInstancesMerger.execute(model);
        System.out.println("TileMatrix size (columns x rows): " + model.selectedTileMatrixSizeText());

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
            canvas::requestFocusInWindow
        );
        KeyboardInteractionTechniques keyboard = new KeyboardInteractionTechniques(
            model,
            frame::dispose,
            cameraController,
            canvas::display
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
