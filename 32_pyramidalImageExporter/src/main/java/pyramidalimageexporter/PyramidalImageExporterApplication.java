package pyramidalimageexporter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import pyramidalimageexporter.config.Configuration;
import pyramidalimageexporter.io.MatrixLayerJsonReader;
import pyramidalimageexporter.io.TopLevelTilesJsonReader;
import pyramidalimageexporter.logger.AppLogger;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.TopLevelTilesCatalog;
import pyramidalimageexporter.model.state.PyramidalImageExporterState;
import pyramidalimageexporter.processing.SessionPyramidalImageExportService;
import pyramidalimageexporter.processing.toplevels.TopLevelMatrixRebuilder;
import pyramidalimageexporter.render.Jogl4PyramidalImageExporterRenderer;
import vsdk.toolkit.render.jogl.Jogl4Renderer;

public final class PyramidalImageExporterApplication {
    private static final int DEFAULT_OFFLINE_WIDTH = 1024;
    private static final int DEFAULT_OFFLINE_HEIGHT = 1024;
    private static final String DEFAULT_OFFLINE_OUTPUT = "/tmp/pyramidalImageExporter_offline.png";
    private static final String SESSION_PYRAMID_SUBFOLDER = "pyramidalImage";
    private static final String[] VALUE_FLAGS = {"--layer", "--width", "--height", "--output"};

    public void run(String[] args) {
        boolean offline = hasArg(args, "--ofline") || hasArg(args, "--offline");
        List<String> positionalArgs = parsePositionalArgs(args);
        if (positionalArgs.isEmpty()) {
            AppLogger.error("Missing required <inputFolder> argument: no default paths are assumed.");
            AppLogger.error(
                "It must point to the folder exported by 31_matrixMerger (the one containing the matrix_<n> subfolders)."
            );
            printUsage();
            System.exit(1);
        }
        if (positionalArgs.size() > 1) {
            AppLogger.error("Unexpected extra positional argument(s): " + positionalArgs.subList(1, positionalArgs.size()));
            AppLogger.error(
                "This tool only processes <inputFolder> and writes the session's pyramidal image inside it "
                    + "(<inputFolder>/" + SESSION_PYRAMID_SUBFOLDER + ")."
            );
            printUsage();
            System.exit(1);
        }

        Path inputPath = Path.of(positionalArgs.get(0)).toAbsolutePath().normalize();
        if (!Files.isDirectory(inputPath) || !Files.isReadable(inputPath)) {
            AppLogger.error("Input directory is not accessible: " + inputPath);
            System.exit(1);
        }

        PyramidalImageExporterState model = createState(inputPath);
        model.setSessionPyramidalImageExportPath(inputPath.resolve(SESSION_PYRAMID_SUBFOLDER).toString());

        if (hasArg(args, "--export")) {
            AppLogger.info("Export mode loaded " + model.getMatrixLayerCount() + " matrix layers.");
            new SessionPyramidalImageExportService().export(model);
            return;
        }
        if (offline) {
            AppLogger.info("Offline mode loaded " + model.getMatrixLayerCount() + " matrix layers.");
            int layerIndex = intArgValue(args, "--layer", 0);
            if (!model.selectLayerIndex(layerIndex) && model.getMatrixLayerCount() > 0) {
                AppLogger.warn(
                    "Layer index " + layerIndex + " is out of range [0, "
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
            AppLogger.warn("Can not start OpenGL/JOGL.");
            return;
        }
        Jogl4PyramidalImageExporterRenderer renderer = new Jogl4PyramidalImageExporterRenderer(model);
        InteractiveDebugger interactiveDebugger = new InteractiveDebugger(model, renderer);
        interactiveDebugger.launchDesktop();
    }

    private static void renderOffline(PyramidalImageExporterState model, String outputPath, int width, int height) {
        try {
            Jogl4PyramidalImageExporterRenderer renderer = new Jogl4PyramidalImageExporterRenderer(model);
            renderer.startOffscreen(outputPath, width, height);
        }
        catch (Throwable t) {
            AppLogger.warn("Offline image export is not available because there is no access to a graphics system.");
        }
    }

    private static PyramidalImageExporterState createState(Path inputPath) {
        PyramidalImageExporterState model = new PyramidalImageExporterState();
        model.setInputFolder(inputPath.toString());
        List<MatrixLayer> importedLayers = new MatrixLayerJsonReader().readAllFromInput(inputPath);
        List<MatrixLayer> layers = new ArrayList<>();

        Path outputDirectory = Path.of(Configuration.outputDirectory()).toAbsolutePath().normalize();
        TopLevelTilesJsonReader topLevelTilesReader = new TopLevelTilesJsonReader();
        TopLevelMatrixRebuilder topLevelMatrixRebuilder = new TopLevelMatrixRebuilder();
        TopLevelTilesCatalog topLevelTiles = topLevelTilesReader.read(outputDirectory).orElse(null);
        layers.addAll(topLevelMatrixRebuilder.importLayers(topLevelTiles));
        layers.addAll(importedLayers);

        model.setMatrixLayers(layers);
        model.setCataloguedQuadPathsByImagePath(topLevelMatrixRebuilder.catalogedQuadPathsByImagePath(topLevelTiles));
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
            if ("--offline".equals(arg) || "--ofline".equals(arg) || "--wires".equals(arg) || "--export".equals(arg)) {
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
            "Usage: gradle run --args=\"<inputFolder> "
                + "[--export] [--offline] [--layer <i>] [--width <px>] [--height <px>] [--output <path>] [--wires]\""
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
