package planetdemviewer;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import planetdemviewer.io.PyramidalImageFolderReader;
import planetdemviewer.logger.Logger;
import planetdemviewer.model.OperationModes;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.model.PyramidalImage;
import planetdemviewer.mcp.ViewerMcpServer;
import planetdemviewer.options.ArgumentParser;
import planetdemviewer.options.CliArguments;
import planetdemviewer.render.Jogl4PlanetViewerRenderer;
import planetdemviewer.state.ApplicationStateStore;
import vsdk.toolkit.render.jogl.Jogl4Renderer;

public class Main {
    public static void main(String[] args) {
        CliArguments cliArguments = ArgumentParser.parse(args);

        PlanetViewerModel model = new PlanetViewerModel(cliArguments.getStorageProfile());
        List<String> folders = cliArguments.getPyramidalImageFolders().isEmpty()
            ? ApplicationStateStore.sourceFolders(ApplicationStateStore.DEFAULT_PATH)
            : cliArguments.getPyramidalImageFolders();
        List<String> demFolders = cliArguments.getImageryFolder() == null ? folders : folders.subList(0, 1);
        int loadedCount = loadInitialImages(model, demFolders);
        if (loadedCount == 0 && folders.isEmpty()) {
            Logger.info("starting with an empty scene. Press 'l' to load a DEM pyramid, or pass folders as arguments.");
        }

        if (cliArguments.isOffline()) {
            model.setOperationMode(OperationModes.PALETTE_BASED_IMAGE);
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

        var restoredRendererState = ApplicationStateStore.restoreModel(ApplicationStateStore.DEFAULT_PATH, model);
        Jogl4PlanetViewerRenderer renderer = new Jogl4PlanetViewerRenderer(model,
            cliArguments.getImageryFolder() == null ? null : Path.of(cliArguments.getImageryFolder()));
        restoredRendererState.ifPresent(state -> {
            renderer.setTerrainTileBoundsVisible(state.tileBounds());
            renderer.setUniformLodVisible(state.uniformLod());
            Logger.info("restored state.json");
        });
        InteractiveViewer interactiveViewer = new InteractiveViewer(model, renderer);
        interactiveViewer.launchDesktop();
        if (cliArguments.getMcpPort() > 0) {
            try {
                ViewerMcpServer server = new ViewerMcpServer(interactiveViewer, cliArguments.getMcpPort());
                server.start();
                interactiveViewer.setMcpServer(server);
            }
            catch (Exception e) {
                System.out.println("Could not start PlanetDemViewer MCP: " + e.getMessage());
            }
        }
    }

    private static int loadInitialImages(PlanetViewerModel model, List<String> folders) {
        int loaded = 0;
        PyramidalImageFolderReader reader = new PyramidalImageFolderReader();
        for (String folder : folders) {
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
