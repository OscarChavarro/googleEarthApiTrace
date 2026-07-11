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
    private static final int DEFAULT_OFFLINE_WIDTH = 1024;
    private static final int DEFAULT_OFFLINE_HEIGHT = 1024;
    private static final String DEFAULT_OFFLINE_OUTPUT = "/tmp/pyramidalImageExporter_offline.png";

    public static void main(String[] args) {
        boolean offline = hasArg(args, "--ofline") || hasArg(args, "--offline");
        List<String> positionalArgs = parsePositionalArgs(args);
        if (positionalArgs.size() < 2) {
            printUsage();
            System.exit(1);
        }
        String inputFolder = positionalArgs.get(0);
        String sessionPyramidalImageExportPath = positionalArgs.get(1);

        Path inputPath = Path.of(inputFolder).toAbsolutePath().normalize();
        if (!Files.isDirectory(inputPath) || !Files.isReadable(inputPath)) {
            System.err.println("ERROR: Input directory is not accessible: " + inputPath);
            System.exit(1);
        }

        PyramidalImageExporterModel model = createModel(inputPath);
        model.setSessionPyramidalImageExportPath(
            Path.of(sessionPyramidalImageExportPath).toAbsolutePath().normalize().toString()
        );
        if (offline) {
            System.out.println("Offline mode loaded " + model.getMatrixLayerCount() + " matrix layers.");
            int layerIndex = intArgValue(args, "--layer", 0);
            if (!model.selectLayerIndex(layerIndex) && model.getMatrixLayerCount() > 0) {
                System.out.println(
                    "Offline warning: layer index " + layerIndex + " is out of range [0, "
                        + (model.getMatrixLayerCount() - 1) + "]; using layer 0."
                );
            }
            if (hasArg(args, "--wires")) {
                model.getRenderingConfiguration().setWires(true);
            }
            renderOffline(
                model,
                stringArgValue(args, "--output", DEFAULT_OFFLINE_OUTPUT),
                intArgValue(args, "--width", DEFAULT_OFFLINE_WIDTH),
                intArgValue(args, "--height", DEFAULT_OFFLINE_HEIGHT)
            );
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

    private static void renderOffline(PyramidalImageExporterModel model, String outputPath, int width, int height) {
        try {
            Jogl4PyramidalImageExporterRenderer renderer = new Jogl4PyramidalImageExporterRenderer(model);
            renderer.startOffscreen(outputPath, width, height);
        }
        catch (Throwable t) {
            System.out.println(
                "Warning: Offline image export is not available because there is no access to a graphics system."
            );
        }
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

    private static final String[] VALUE_FLAGS = {"--layer", "--width", "--height", "--output"};

    private static String stringArgValue(String[] args, String flag, String fallback) {
        if (args == null || flag == null) {
            return fallback;
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null) {
                continue;
            }
            if (arg.equals(flag) && i + 1 < args.length) {
                return args[i + 1];
            }
            if (arg.startsWith(flag + "=")) {
                return arg.substring(flag.length() + 1);
            }
        }
        return fallback;
    }

    private static int intArgValue(String[] args, String flag, int fallback) {
        String raw = stringArgValue(args, flag, null);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        }
        catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static List<String> parsePositionalArgs(String[] args) {
        List<String> positional = new ArrayList<>();
        if (args == null) {
            return positional;
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null || arg.isBlank()) {
                continue;
            }
            if ("--offline".equals(arg) || "--ofline".equals(arg) || "--wires".equals(arg)) {
                continue;
            }
            if (isValueFlag(arg)) {
                if (!arg.contains("=")) {
                    i++;
                }
                continue;
            }
            if (!arg.startsWith("--")) {
                positional.add(arg);
            }
        }
        return positional;
    }

    private static void printUsage() {
        System.err.println(
            "Usage: gradle run --args=\"<inputFolder> <sessionPyramidalImageExportPath> "
                + "[--offline] [--layer <i>] [--width <px>] [--height <px>] [--output <path>] [--wires]\""
        );
        System.err.println("  <inputFolder>: directory with the matrix_<n> folders exported by 31_matrixMerger.");
        System.err.println(
            "  <sessionPyramidalImageExportPath>: destination directory for the pyramidal image quadtree "
                + "export triggered with the 'e' key in the interactive viewer."
        );
    }

    private static boolean isValueFlag(String arg) {
        for (String flag : VALUE_FLAGS) {
            if (arg.equals(flag) || arg.startsWith(flag + "=")) {
                return true;
            }
        }
        return false;
    }
}
