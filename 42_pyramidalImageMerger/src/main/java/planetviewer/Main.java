package planetviewer;

import java.nio.file.Path;
import java.util.Optional;
import planetviewer.io.PyramidalImageFolderReader;
import planetviewer.model.PlanetViewerModel;
import planetviewer.model.PyramidalImage;
import planetviewer.model.PyramidalImageInstance;
import planetviewer.options.ArgumentParser;
import planetviewer.render.Jogl4PyramidalImageMergerRenderer;
import vsdk.toolkit.render.jogl.Jogl4Renderer;

public class Main {
    public static void main(String[] args) {
        if (args == null || args.length != 2) {
            ArgumentParser.printUsage();
            return;
        }

        if (!Jogl4Renderer.verifyOpenGLAvailability()) {
            System.out.println("Can not start OpenGL/JOGL.");
            return;
        }

        PlanetViewerModel model = new PlanetViewerModel();
        PyramidalImage destination = loadRequiredImage(args[0], "destination");
        PyramidalImage delta = loadRequiredImage(args[1], "delta");
        if (destination == null || delta == null) {
            return;
        }

        PyramidalImageInstance destinationInstance = model.addImage(destination);
        PyramidalImageInstance deltaInstance = model.addImage(delta);
        Jogl4PyramidalImageMergerRenderer renderer = new Jogl4PyramidalImageMergerRenderer(
            model,
            destinationInstance,
            deltaInstance
        );
        InteractiveViewer interactiveViewer = new InteractiveViewer(model, renderer);
        interactiveViewer.launchDesktop();
    }

    private static PyramidalImage loadRequiredImage(String folder, String role) {
        PyramidalImageFolderReader reader = new PyramidalImageFolderReader();
        Path path = Path.of(folder).toAbsolutePath().normalize();
        Optional<PyramidalImage> image = reader.read(path);
        if (image.isEmpty()) {
            System.err.println("Invalid " + role + " pyramidal image folder (missing 0.png or unreadable tile tree): " + path);
            ArgumentParser.printUsage();
            return null;
        }
        return image.get();
    }
}
