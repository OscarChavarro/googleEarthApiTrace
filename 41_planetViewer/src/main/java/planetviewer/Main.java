package planetviewer;

import java.nio.file.Path;
import java.util.Optional;
import planetviewer.io.PyramidalImageFolderReader;
import planetviewer.logger.Logger;
import planetviewer.model.PlanetViewerModel;
import planetviewer.model.PyramidalImage;
import planetviewer.options.ArgumentParser;
import planetviewer.options.CliArguments;
import planetviewer.render.Jogl4PlanetViewerRenderer;
import vsdk.toolkit.render.jogl.Jogl4Renderer;

public class Main {
    public static void main(String[] args) {
        CliArguments cliArguments = ArgumentParser.parse(args);

        PlanetViewerModel model = new PlanetViewerModel();
        int loadedCount = loadInitialImages(model, cliArguments);
        if (loadedCount == 0 && cliArguments.getPyramidalImageFolders().isEmpty()) {
            Logger.info("starting with an empty scene. Press 'l' to load a pyramidal image, or pass folders as arguments.");
        }

        if (cliArguments.isOffline()) {
            model.getRenderingConfiguration().setWires(cliArguments.isWires());
            renderOffline(model, cliArguments);
            return;
        }

        if (!Jogl4Renderer.verifyOpenGLAvailability()) {
            System.out.println("Can not start OpenGL/JOGL.");
            return;
        }

        Jogl4PlanetViewerRenderer renderer = new Jogl4PlanetViewerRenderer(model);
        InteractiveViewer interactiveViewer = new InteractiveViewer(model, renderer);
        interactiveViewer.launchDesktop();
    }

    private static int loadInitialImages(PlanetViewerModel model, CliArguments cliArguments) {
        int loaded = 0;
        PyramidalImageFolderReader reader = new PyramidalImageFolderReader();
        for (String folder : cliArguments.getPyramidalImageFolders()) {
            Path path = Path.of(folder).toAbsolutePath().normalize();
            Optional<PyramidalImage> image = reader.read(path);
            if (image.isEmpty()) {
                Logger.error("Not a valid pyramidal image folder (missing 0.png or unreadable tile tree): " + path);
                continue;
            }
            model.addImage(image.get());
            Logger.info("loaded pyramidal image " + image.get().getSourceFolder()
                + ": " + image.get().getTileCount() + " tiles, height " + image.get().getHeight());
            loaded++;
        }
        return loaded;
    }

    private static void renderOffline(PlanetViewerModel model, CliArguments cliArguments) {
        try {
            Jogl4PlanetViewerRenderer renderer = new Jogl4PlanetViewerRenderer(model);
            renderer.startOffscreen(cliArguments.getOutput(), cliArguments.getWidth(), cliArguments.getHeight());
        }
        catch (Throwable t) {
            System.out.println(
                "Warning: Offline image export is not available because there is no access to a graphics system."
            );
        }
    }
}
