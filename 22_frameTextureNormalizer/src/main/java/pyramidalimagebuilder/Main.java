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
import pyramidalimagebuilder.model.FrameData;
import pyramidalimagebuilder.model.PyramidalImageModel;
import pyramidalimagebuilder.processing.Sha256SignatureGenerator;
import pyramidalimagebuilder.processing.TileFiltererByGeometricNullNeighbors;
import pyramidalimagebuilder.render.Jogl4PyramidalImageBuilderRenderer;
import vsdk.toolkit.gui.CameraControllerOrbiter;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        PyramidalImageModel model = new PyramidalImageModel();

        TraceSessionReader traceSessionReader = new TraceSessionReader();
        TileFiltererByGeometricNullNeighbors tileFilterer = new TileFiltererByGeometricNullNeighbors();
        Runnable reloadTileMatrices = () -> {
            List<FrameData> loaded = traceSessionReader.readSession(Path.of(Configuration.INPUT_PATH));
            List<FrameData> filtered = loaded.stream()
                .map(frame -> new FrameData(
                    frame.getId(),
                    tileFilterer.filter(frame.getTiles(), model.getViewingCamera()),
                    frame.getCameraState()
                ))
                .toList();
            model.setFrames(filtered);
            System.out.println("Loaded frames: " + model.getFrames().size());
        };
        reloadTileMatrices.run();

        System.out.println("Starting SHA signature validation");
        Sha256SignatureGenerator.verifyTextureFilesHasSignatureFile(model.getFrames());
        System.out.println("SHA signatures validated");

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
