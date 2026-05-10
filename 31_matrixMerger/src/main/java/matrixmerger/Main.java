package matrixmerger;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import matrixmerger.io.MatrixReader;
import matrixmerger.io.WestCutterReader;
import matrixmerger.io.WestCutterWriter;
import matrixmerger.io.FrameMatrices;
import matrixmerger.model.MatrixMergerModel;
import matrixmerger.processing.AutomaticGrouper;
import matrixmerger.processing.WestCutterColumnPropagator;
import matrixmerger.processing.WestCutterFrameValidator;
import matrixmerger.processing.WestCutterValidationResult;
import matrixmerger.render.Jogl4MatrixMergerRenderer;
import vsdk.toolkit.render.jogl.Jogl4Renderer;

public class Main {
    private static final String OUTPUT_DIRECTORY = loadOutputDirectory();
    private enum Mode {
        MANUAL,
        AUTO
    }

    public static void main(String[] args) {
        boolean offline = hasArg(args, "--ofline") || hasArg(args, "--offline");
        Mode mode = parseMode(args);
        if (!Jogl4Renderer.verifyOpenGLAvailability()) {
            System.out.println("Can not start OpenGL/JOGL.");
            return;
        }

        MatrixMergerModel model = createModel();
        Path outputPath = Path.of(OUTPUT_DIRECTORY);
        if (offline) {
            int before = model.getMatrixCount();
            model.mergeFullSet();
            int after = model.getMatrixCount();
            System.out.println("Offline full-set merge done. Matrices: " + before + " -> " + after);
            return;
        }
        if (mode == Mode.AUTO) {
            new AutomaticGrouper().run(model);
        }
        printMissingTopLevelUncles(model, outputPath);
        Jogl4MatrixMergerRenderer renderer = new Jogl4MatrixMergerRenderer(model);
        InteractiveDebugger interactiveDebugger = new InteractiveDebugger(model, renderer);
        interactiveDebugger.launchDesktop();
    }

    private static MatrixMergerModel createModel() {
        MatrixMergerModel model = new MatrixMergerModel();
        Path outputPath = Path.of(OUTPUT_DIRECTORY);
        List<FrameMatrices> frames = new MatrixReader().readAllFromOutput(outputPath);
        Set<String> westCutterIds = new WestCutterReader().readFromOutput(outputPath);
        Set<String> propagatedWestCutterIds = new WestCutterColumnPropagator().propagate(frames, westCutterIds);
        if (!propagatedWestCutterIds.equals(westCutterIds)) {
            new WestCutterWriter().writeToOutput(outputPath, propagatedWestCutterIds);
        }
        westCutterIds = propagatedWestCutterIds;
        WestCutterValidationResult validation = new WestCutterFrameValidator().validate(frames, westCutterIds);
        model.setFrameMatrices(frames);
        model.setWestCutterTileIds(westCutterIds);
        model.setInvalidFrames(validation.getInvalidReasonByFrameId());
        return model;
    }

    private static void printMissingTopLevelUncles(MatrixMergerModel model, Path outputPath) {
        if (model == null || outputPath == null) {
            return;
        }
        for (String tileId : model.getMissingTopLevelUncleTileIds()) {
            String path = toAbsoluteTilePath(outputPath, tileId);
            if (path != null && !path.isBlank()) {
                System.out.println(path);
            }
        }
    }

    private static String toAbsoluteTilePath(Path outputPath, String scopedTileId) {
        String normalized = WestCutterReader.normalizeScopedTileId(scopedTileId);
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
            System.err.println("[WARN] Could not load application.properties: " + e.getMessage());
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
}
