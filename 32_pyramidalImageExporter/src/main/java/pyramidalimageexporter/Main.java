package pyramidalimageexporter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import pyramidalimageexporter.config.Configuration;
import pyramidalimageexporter.io.MatrixLayerReader;
import pyramidalimageexporter.io.TopLevelTilesReader;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.PyramidalImageExporterModel;
import pyramidalimageexporter.model.TopLevelTiles;
import pyramidalimageexporter.processing.toplevels.TopLevelsMatricesImporter;
import pyramidalimageexporter.render.Jogl4PyramidalImageExporterRenderer;
import vsdk.toolkit.render.jogl.Jogl4Renderer;

public class Main {
    public static void main(String[] args) {
        boolean offline = hasArg(args, "--ofline") || hasArg(args, "--offline");
        String inputFolder = parseInputFolder(args);
        if (inputFolder == null) {
            System.err.println("ERROR: Missing input directory parameter.");
            System.exit(1);
        }

        Path inputPath = Path.of(inputFolder).toAbsolutePath().normalize();
        if (!Files.isDirectory(inputPath) || !Files.isReadable(inputPath)) {
            System.err.println("ERROR: Input directory is not accessible: " + inputPath);
            System.exit(1);
        }

        PyramidalImageExporterModel model = createModel(inputPath);
        if (offline) {
            System.out.println("Offline mode loaded " + model.getMatrixLayerCount() + " matrix layers.");
            return;
        }

        if (!Jogl4Renderer.verifyOpenGLAvailability()) {
            System.out.println("Can not start OpenGL/JOGL.");
            return;
        }

        Jogl4PyramidalImageExporterRenderer renderer = new Jogl4PyramidalImageExporterRenderer(model);
        InteractiveDebugger interactiveDebugger = new InteractiveDebugger(model, renderer);
        interactiveDebugger.launchDesktop();
    }

    private static PyramidalImageExporterModel createModel(Path inputPath) {
        PyramidalImageExporterModel model = new PyramidalImageExporterModel();
        model.setInputFolder(inputPath.toString());
        List<MatrixLayer> importedLayers = new MatrixLayerReader().readAllFromInput(inputPath);
        List<MatrixLayer> layers = new ArrayList<>();

        Path outputDirectory = Path.of(Configuration.outputDirectory()).toAbsolutePath().normalize();
        TopLevelTilesReader topLevelTilesReader = new TopLevelTilesReader();
        TopLevelsMatricesImporter topLevelsImporter = new TopLevelsMatricesImporter();
        TopLevelTiles topLevelTiles = topLevelTilesReader.read(outputDirectory).orElse(null);
        layers.addAll(topLevelsImporter.importLayers(topLevelTiles));
        layers.addAll(importedLayers);

        model.setMatrixLayers(layers);
        return model;
    }

    private static boolean hasArg(String[] args, String flag) {
        if (args == null || flag == null || flag.isBlank()) {
            return false;
        }
        for (String arg : args) {
            if (flag.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static String parseInputFolder(String[] args) {
        if (args == null) {
            return null;
        }
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            if ("--offline".equals(arg) || "--ofline".equals(arg)) {
                continue;
            }
            if (!arg.startsWith("--")) {
                return arg;
            }
        }
        return null;
    }
}
