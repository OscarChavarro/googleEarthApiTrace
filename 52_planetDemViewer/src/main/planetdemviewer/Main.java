package planetdemviewer;

import java.nio.file.Path;
import java.util.Optional;
import planetdemviewer.gui.awt.AwtTextRasterizerHelper;
import planetdemviewer.io.PyramidalImageFolderReader;
import planetdemviewer.io.ViewerSnapshotWriter;
import planetdemviewer.logger.Logger;
import planetdemviewer.model.OperationModes;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.model.PyramidalImage;
import planetdemviewer.mcp.ViewerMcpServer;
import planetdemviewer.options.ArgumentParser;
import planetdemviewer.options.CliArguments;
import planetdemviewer.render.PlanetViewerSession;
import planetdemviewer.render.jogl4.Jogl4PlanetViewerRenderer;
import planetdemviewer.state.ApplicationStateStore;
import vsdk.toolkit.media.RGBImageUncompressed;
import vsdk.toolkit.render.jogl.Jogl4Renderer;

public class Main {
    private static final int EXIT_INVALID_ARGUMENTS = 2;
    private static final int EXIT_INVALID_DATASET = 1;

    public static void main(String[] args) {
        CliArguments cliArguments;
        try {
            cliArguments = ArgumentParser.parse(args);
        }
        catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            ArgumentParser.printUsage();
            System.exit(EXIT_INVALID_ARGUMENTS);
            return;
        }

        PlanetViewerModel model = new PlanetViewerModel(cliArguments.getStorageProfile());
        if (!loadDem(model, cliArguments.getDemFolder())) {
            System.exit(EXIT_INVALID_DATASET);
            return;
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
        PlanetViewerSession session = new PlanetViewerSession(model, Path.of(cliArguments.getImageryFolder()));
        restoredRendererState.ifPresent(state -> {
            session.setTerrainTileBoundsVisible(state.tileBounds());
            session.setUniformLodVisible(state.uniformLod());
            Logger.info("restored state.json");
        });
        InteractiveViewer interactiveViewer = new InteractiveViewer(session,
            new Jogl4PlanetViewerRenderer(session, new AwtTextRasterizerHelper()));
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

    private static boolean loadDem(PlanetViewerModel model, String folder) {
        Path path = Path.of(folder).toAbsolutePath().normalize();
        Optional<PyramidalImage> image = new PyramidalImageFolderReader().read(path);
        if (image.isEmpty()) {
            Logger.error("Not a valid DEM pyramid (missing or invalid 0.bin): " + path);
            return false;
        }
        model.addImage(image.get());
        Logger.info("opened DEM pyramid " + image.get().getSourceFolder()
            + ": root tile ready; lower levels will be indexed lazily");
        return true;
    }

    private static void renderOffline(PlanetViewerModel model, CliArguments cliArguments) {
        try {
            Jogl4PlanetViewerRenderer renderer = new Jogl4PlanetViewerRenderer(
                new PlanetViewerSession(model), new AwtTextRasterizerHelper());
            RGBImageUncompressed image = renderer.renderOffscreen(cliArguments.getWidth(), cliArguments.getHeight());
            if (image == null) {
                System.out.println("Can not start OpenGL/JOGL renderer.");
                return;
            }
            ViewerSnapshotWriter.writePng(image, Path.of(cliArguments.getOutput()));
        }
        catch (Throwable t) {
            System.out.println(
                "Warning: Offline image export is not available because there is no access to a graphics system."
            );
        }
    }
}
