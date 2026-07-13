package matrixmerger;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import matrixmerger.io.FrameMatrixReader;
import matrixmerger.io.MatrixLayerExportWriter;
import matrixmerger.io.WestCuttersJsonReader;
import matrixmerger.io.WestCuttersJsonWriter;
import matrixmerger.logger.AppLogger;
import matrixmerger.model.contract.FrameMatrixSet;
import matrixmerger.model.state.MatrixMergerState;
import matrixmerger.processing.AutomaticMatrixGroupingPipeline;
import matrixmerger.processing.FrameValidationSummary;
import matrixmerger.processing.WestCutterColumnAlignmentPropagator;
import matrixmerger.processing.WestCutterFrameSetValidator;
import matrixmerger.render.Jogl4MatrixMergerRenderer;
import vsdk.toolkit.render.jogl.Jogl4Renderer;

public final class MatrixMergerApplication {
    private static final String OUTPUT_DIRECTORY = loadOutputDirectory();
    private static final int DEFAULT_OFFLINE_WIDTH = 1024;
    private static final int DEFAULT_OFFLINE_HEIGHT = 1024;

    private enum Mode {
        MANUAL,
        AUTO
    }

    public void run(String[] args) {
        boolean offline = hasArg(args, "--offline");
        boolean renderAllLevels = hasArg(args, "--all-levels");
        boolean renderLevel = argValue(args, "--level") != null || renderAllLevels;
        Mode mode = parseMode(args);
        if (!offline && !Jogl4Renderer.verifyOpenGLAvailability()) {
            AppLogger.warn("Can not start OpenGL/JOGL.");
            return;
        }

        MatrixMergerState model = createModel();
        model.setOutputFolder(parseOutputFolder(args));
        Path outputPath = Path.of(OUTPUT_DIRECTORY);
        printMissingOutputFolderWarning(model);
        if (offline && !renderLevel) {
            processOfflineWithoutRendering(model, mode);
            finishProcessing(model, outputPath, args);
            return;
        }
        if (mode == Mode.AUTO) {
            new AutomaticMatrixGroupingPipeline().run(model);
        }
        finishProcessing(model, outputPath, args);
        if (offline) {
            if (!Jogl4Renderer.verifyOpenGLAvailability()) {
                AppLogger.warn("Can not start OpenGL/JOGL.");
                return;
            }
            if (renderAllLevels) {
                renderAllOfflineLevels(model, args);
            }
            else {
                renderOfflineLevel(model, args);
            }
            return;
        }
        Jogl4MatrixMergerRenderer renderer = new Jogl4MatrixMergerRenderer(model);
        InteractiveDebugger interactiveDebugger = new InteractiveDebugger(
            model,
            renderer,
            () -> finishProcessing(model, outputPath, args)
        );
        interactiveDebugger.launchDesktop();
    }

    private static void processOfflineWithoutRendering(MatrixMergerState model, Mode mode) {
        if (mode == Mode.AUTO) {
            new AutomaticMatrixGroupingPipeline().run(model);
            return;
        }
        int before = model.getMatrixCount();
        model.mergeFullSet();
        int after = model.getMatrixCount();
        AppLogger.info("Offline full-set merge done. Matrices: " + before + " -> " + after);
    }

    /**
     * Runs the non-graphical finalization shared by interactive and offline
     * execution. A positional output folder therefore has the same export
     * contract in both modes.
     */
    private static void finishProcessing(MatrixMergerState model, Path outputPath, String[] args) {
        if (hasArg(args, "--diagnose-order")) {
            printHierarchyOrderDiagnostics(model);
        }
        printMissingTopLevelUncles(model, outputPath);
        if (model.getOutputFolder() != null) {
            new MatrixLayerExportWriter(model, outputPath).export(model.getOutputFolder());
        }
    }

    private static void renderOfflineLevel(MatrixMergerState model, String[] args) {
        int level = intArgValue(args, "--level", 0);
        if (!model.selectFrameIndex(level)) {
            AppLogger.warn(
                "Level " + level + " is out of range [0, " + Math.max(0, model.getMatrixCount() - 1) + "]."
            );
            return;
        }
        String output = argValue(args, "--output");
        if (output == null || output.isBlank()) {
            output = "/tmp/frame" + level + ".png";
        }
        AppLogger.info("Rendering offline level " + level + " of " + model.getMatrixCount() + ".");
        new Jogl4MatrixMergerRenderer(model).startOffscreen(
            output,
            intArgValue(args, "--width", DEFAULT_OFFLINE_WIDTH),
            intArgValue(args, "--height", DEFAULT_OFFLINE_HEIGHT)
        );
    }

    private static void renderAllOfflineLevels(MatrixMergerState model, String[] args) {
        int width = intArgValue(args, "--width", DEFAULT_OFFLINE_WIDTH);
        int height = intArgValue(args, "--height", DEFAULT_OFFLINE_HEIGHT);
        for (int level = 0; level < model.getMatrixCount(); level++) {
            model.selectFrameIndex(level);
            String output = String.format("/tmp/frame%02d.png", level);
            AppLogger.info("Rendering offline level " + level + " of " + model.getMatrixCount() + ".");
            new Jogl4MatrixMergerRenderer(model).startOffscreen(output, width, height);
        }
    }

    private static void printHierarchyOrderDiagnostics(MatrixMergerState model) {
        for (MatrixMergerState.HierarchyOrderDiagnostic item : model.getHierarchyOrderDiagnostics()) {
            AppLogger.info(
                "ORDER index=" + item.index()
                    + " level=" + item.level()
                    + " lastCaptureFrame=" + item.lastCaptureFrameId()
                    + " parents=" + item.resolvedParentIndexes()
                    + " uncles=" + item.uncleCount()
                    + " unresolvedUncles=" + item.unresolvedUncleCount()
                    + " tiles=" + item.tileCount()
            );
        }
    }

    private static MatrixMergerState createModel() {
        MatrixMergerState model = new MatrixMergerState();
        Path outputPath = Path.of(OUTPUT_DIRECTORY);
        List<FrameMatrixSet> frames = new FrameMatrixReader().readAllFromOutput(outputPath);
        Set<String> westCutterIds = new WestCuttersJsonReader().readFromOutput(outputPath);
        Set<String> propagatedWestCutterIds = new WestCutterColumnAlignmentPropagator().propagate(frames, westCutterIds);
        if (!propagatedWestCutterIds.equals(westCutterIds)) {
            new WestCuttersJsonWriter().writeToOutput(outputPath, propagatedWestCutterIds);
        }
        westCutterIds = propagatedWestCutterIds;
        FrameValidationSummary validation = new WestCutterFrameSetValidator().validate(frames, westCutterIds);
        model.setFrameMatrices(frames);
        model.setWestCutterTileIds(westCutterIds);
        model.setInvalidFrames(validation.getInvalidReasonByFrameId());
        return model;
    }

    private static void printMissingTopLevelUncles(MatrixMergerState model, Path outputPath) {
        if (model == null || outputPath == null) {
            return;
        }
        for (String tileId : model.getMissingTopLevelUncleTileIds()) {
            String path = toAbsoluteTilePath(outputPath, tileId);
            if (path != null && !path.isBlank()) {
                AppLogger.info(path);
            }
        }
    }

    private static String toAbsoluteTilePath(Path outputPath, String scopedTileId) {
        String normalized = WestCuttersJsonReader.normalizeScopedTileId(scopedTileId);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        int separator = normalized.indexOf('_');
        if (separator <= 0 || separator >= normalized.length() - 1) {
            return normalized;
        }
        try {
            int frameId = Integer.parseInt(normalized.substring(0, separator));
            int tileId = Integer.parseInt(normalized.substring(separator + 1));
            return outputPath.resolve(String.format("%05d", frameId))
                .resolve("256x256_" + tileId + ".png")
                .toAbsolutePath()
                .toString();
        }
        catch (NumberFormatException ex) {
            return normalized;
        }
    }

    private static String loadOutputDirectory() {
        Properties properties = new Properties();
        try (InputStream input = Main.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) {
                properties.load(input);
            }
        }
        catch (Exception e) {
            AppLogger.warn("Could not load application.properties: " + e.getMessage());
        }
        return properties.getProperty("output.directory", "/media/ramdisk/output");
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

    private static void printMissingOutputFolderWarning(MatrixMergerState model) {
        if (model == null || model.getOutputFolder() != null) {
            return;
        }
        AppLogger.warn("No output folder was provided. Results will not be exported.");
    }

    private static Mode parseMode(String[] args) {
        String value = argValue(args, "--mode");
        if (value == null || value.isBlank()) {
            return Mode.MANUAL;
        }
        return "auto".equalsIgnoreCase(value.trim()) ? Mode.AUTO : Mode.MANUAL;
    }

    private static String argValue(String[] args, String flag) {
        if (args == null || flag == null || flag.isBlank()) {
            return null;
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null) {
                continue;
            }
            if (arg.equals(flag)) {
                if (i + 1 < args.length) {
                    return args[i + 1];
                }
                return null;
            }
            String prefix = flag + "=";
            if (arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }
        return null;
    }

    private static int intArgValue(String[] args, String flag, int fallback) {
        String value = argValue(args, flag);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        }
        catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String parseOutputFolder(String[] args) {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null || arg.isBlank()) {
                continue;
            }
            if (arg.startsWith("--mode=") || arg.startsWith("--level=")
                || arg.startsWith("--output=") || arg.startsWith("--width=")
                || arg.startsWith("--height=") || "--offline".equals(arg)
                || "--diagnose-order".equals(arg) || "--all-levels".equals(arg)) {
                continue;
            }
            if ("--mode".equals(arg) || "--level".equals(arg) || "--output".equals(arg)
                || "--width".equals(arg) || "--height".equals(arg)) {
                i++;
                continue;
            }
            if (!arg.startsWith("--")) {
                return arg;
            }
        }
        return null;
    }
}
