package pyramidalimagebuilder;

import java.util.List;

import pyramidalimagebuilder.io.FrameReader;
import pyramidalimagebuilder.io.TraceSessionReader;
import pyramidalimagebuilder.model.PyramidalImageModel;
import pyramidalimagebuilder.processing.DuplicatedTextureFilenameMapper;
import pyramidalimagebuilder.processing.Sha256SignatureGenerator;
import pyramidalimagebuilder.processing.TileFiltererByGeometricNullNeighbors;
import pyramidalimagebuilder.processing.matrix.TileMatrixProcessor;
import pyramidalimagebuilder.processing.TileTextureNormalizer;

public class Main {
    public static void main(String[] args) {
        boolean offline = hasArg(args, "--offline");
        boolean debugMatrix = hasArg(args, "--debug-matrix");
        String debugFrame = getArgValue(args, "--debug-frame=");
        if (debugMatrix) {
            System.setProperty("pib.debug.matrix", "true");
        }
        if (debugFrame != null && !debugFrame.isBlank()) {
            System.setProperty("pib.debug.matrix.frame", debugFrame.trim());
        }

        PyramidalImageModel model = new PyramidalImageModel();

        TraceSessionReader traceSessionReader = new TraceSessionReader();
        TileFiltererByGeometricNullNeighbors tileFilterer = new TileFiltererByGeometricNullNeighbors();

        System.out.print("Loading traced frames... ");
        Runnable reloadTileMatrices = FrameReader.loadTracedFrames(traceSessionReader, tileFilterer, model);
        System.out.println("OK");

        System.out.print("SHA signature validation... ");
        Sha256SignatureGenerator.verifyTextureFilesHasSignatureFile(model.getFrames());
        System.out.println("OK");

        System.out.print("Duplicated texture filename mapping... ");
        List<List<String>> duplicatedTextureGroups = DuplicatedTextureFilenameMapper.loadOrCreate(model.getFrames());
        System.out.println("OK");

        System.out.print("Tile texture normalization and matrix conversion... ");
        TileMatrixProcessor tileMatrixProcessor = new TileMatrixProcessor();
        model.setFrames(tileMatrixProcessor.convertAndExportTileMatrices(
            TileTextureNormalizer.normalize(model.getFrames(), duplicatedTextureGroups)
        ));
        System.out.println("OK");

        if (offline) {
            System.out.println("Offline mode enabled: skipping interactive GUI.");
            return;
        }

        InteractiveDebugger.runDesktopGui(model, reloadTileMatrices);
    }

    private static boolean hasArg(String[] args, String flag) {
        if (args == null || args.length == 0) {
            return false;
        }
        for (String a : args) {
            if (flag.equals(a)) {
                return true;
            }
        }
        return false;
    }

    private static String getArgValue(String[] args, String prefix) {
        if (args == null || args.length == 0) {
            return null;
        }
        for (String a : args) {
            if (a != null && a.startsWith(prefix)) {
                return a.substring(prefix.length());
            }
        }
        return null;
    }
}
