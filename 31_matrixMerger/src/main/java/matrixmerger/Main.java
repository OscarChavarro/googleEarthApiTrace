package matrixmerger;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;
import matrixmerger.io.MatrixReader;
import matrixmerger.model.MatrixMergerModel;
import matrixmerger.render.Jogl4MatrixMergerRenderer;
import vsdk.toolkit.render.jogl.Jogl4Renderer;

public class Main {
    private static final String OUTPUT_DIRECTORY = loadOutputDirectory();
    public static void main(String[] args) {
        boolean offline = hasArg(args, "--ofline") || hasArg(args, "--offline");
        if (!Jogl4Renderer.verifyOpenGLAvailability()) {
            System.out.println("Can not start OpenGL/JOGL.");
            return;
        }

        MatrixMergerModel model = createModel();
        if (offline) {
            int before = model.getMatrixCount();
            model.mergeFullSet();
            int after = model.getMatrixCount();
            System.out.println("Offline full-set merge done. Matrices: " + before + " -> " + after);
            return;
        }
        Jogl4MatrixMergerRenderer renderer = new Jogl4MatrixMergerRenderer(model);
        InteractiveDebugger interactiveDebugger = new InteractiveDebugger(model, renderer);
        interactiveDebugger.launchDesktop();
    }

    private static MatrixMergerModel createModel() {
        MatrixMergerModel model = new MatrixMergerModel();
        model.setTileMatrices(new MatrixReader().readAllFromOutput(Path.of(OUTPUT_DIRECTORY)));
        System.out.println("Loaded matrices: " + model.getTileMatrices().size());
        return model;
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
}
