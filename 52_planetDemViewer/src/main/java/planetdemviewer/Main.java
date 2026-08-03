package planetdemviewer;

import java.nio.file.Path;
import java.util.Optional;
import planetdemviewer.io.PyramidalImageFolderReader;
import planetdemviewer.logger.Logger;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.model.PyramidalImage;
import planetdemviewer.options.ArgumentParser;
import planetdemviewer.options.CliArguments;
import planetdemviewer.render.Jogl4PlanetViewerRenderer;
import vsdk.toolkit.render.jogl.Jogl4Renderer;

public class Main {
    public static void main(String[] args) {
        CliArguments cliArguments = ArgumentParser.parse(args);

        PlanetViewerModel model = new PlanetViewerModel(cliArguments.getStorageProfile());
        int loadedCount = loadInitialImages(model, cliArguments);
        if (loadedCount == 0 && cliArguments.getPyramidalImageFolders().isEmpty()) {
            Logger.info("starting with an empty scene. Press 'l' to load a DEM pyramid, or pass folders as arguments.");
        }

        if (cliArguments.isOffline()) {
            model.getRenderingConfiguration().setWires(cliArguments.isWires());
            for (var instance : model.getStack()) {
                instance.getImage().discoverAll();
            }
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
                Logger.error("Not a valid DEM pyramid (missing or invalid 0.bin): " + path);
                continue;
            }
            model.addImage(image.get());
            Logger.info("opened DEM pyramid " + image.get().getSourceFolder()
                + ": root tile ready; lower levels will be indexed lazily");
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
