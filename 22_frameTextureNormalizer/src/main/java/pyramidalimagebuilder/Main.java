package pyramidalimagebuilder;

import java.util.List;

import pyramidalimagebuilder.io.FrameReader;
import pyramidalimagebuilder.io.TileMatrixExporter;
import pyramidalimagebuilder.io.TraceSessionReader;
import pyramidalimagebuilder.model.PyramidalImageModel;
import pyramidalimagebuilder.model.TileMatrix;
import pyramidalimagebuilder.options.CommandLineOptions;
import pyramidalimagebuilder.processing.FrameFiltererByTileCount;
import pyramidalimagebuilder.processing.TileFiltererByConnectedComponents;
import pyramidalimagebuilder.processing.DuplicatedTextureFilenameMapper;
import pyramidalimagebuilder.processing.Sha256SignatureGenerator;
import pyramidalimagebuilder.processing.TileFiltererByGeometricNullNeighbors;
import pyramidalimagebuilder.processing.TileFilteringByErrored;
import pyramidalimagebuilder.processing.matrix.TileMatrixFiltererByConsistency;
import pyramidalimagebuilder.processing.matrix.TileMatrixProcessingResult;
import pyramidalimagebuilder.processing.matrix.TileMatrixProcessor;
import pyramidalimagebuilder.processing.TileTextureNormalizer;

public class Main {
    public static void main(String[] args) {
        boolean offline = CommandLineOptions.hasArg(args, "--offline");
        boolean debugMatrix = CommandLineOptions.hasArg(args, "--debug-matrix");
        String debugFrame = CommandLineOptions.getArgValue(args, "--debug-frame=");
        if (debugMatrix) {
            System.setProperty("pib.debug.matrix", "true");
        }
        if (debugFrame != null && !debugFrame.isBlank()) {
            System.setProperty("pib.debug.matrix.frame", debugFrame.trim());
        }

        PyramidalImageModel model = new PyramidalImageModel();

        TraceSessionReader traceSessionReader = new TraceSessionReader();
        TileFiltererByConnectedComponents connectedComponentsFilterer = new TileFiltererByConnectedComponents();
        TileFiltererByGeometricNullNeighbors tileFilterer = new TileFiltererByGeometricNullNeighbors();
        FrameFiltererByTileCount frameFiltererByTileCount = new FrameFiltererByTileCount();
        TileFilteringByErrored tileFilteringByErrored = new TileFilteringByErrored();
        TileMatrixExporter tileMatrixExporter = new TileMatrixExporter();
        TileMatrixFiltererByConsistency tileMatrixFiltererByConsistency = new TileMatrixFiltererByConsistency();

        System.out.print("Loading traced frames... ");
        Runnable reloadTileMatrices = FrameReader.loadTracedFrames(
            traceSessionReader,
            connectedComponentsFilterer,
            tileFilterer,
            model
        );
        model.setFrames(frameFiltererByTileCount.keepFramesWithMoreThanTiles(model.getFrames(), 1));
        System.out.println("OK");

        System.out.print("SHA signature validation... ");
        Sha256SignatureGenerator.verifyTextureFilesHasSignatureFile(model.getFrames());
        System.out.println("OK");

        System.out.print("Duplicated texture filename mapping... ");
        List<List<String>> duplicatedTextureGroups = DuplicatedTextureFilenameMapper.loadOrCreate(model.getFrames());
        System.out.println("OK");

        System.out.print("Tile texture normalization and matrix conversion... ");
        TileMatrixProcessor tileMatrixProcessor = new TileMatrixProcessor();
        TileMatrixProcessingResult matrixResult = tileMatrixProcessor.convertTileMatrices(
            TileTextureNormalizer.normalize(model.getFrames(), duplicatedTextureGroups)
        );
        System.out.println("OK");

        System.out.print("Filtering matrices by consistency... ");
        List<TileMatrix> consistentMatrices =
            tileMatrixFiltererByConsistency.filter(matrixResult.matrices());
        System.out.println("OK");

        System.out.print("Exporting matrices... ");
        tileMatrixExporter.export(consistentMatrices);
        model.setFrames(matrixResult.frames());
        model.setFrames(tileFilteringByErrored.removeErroredFrames(model.getFrames()));
        System.out.println("OK");

        if (offline) {
            System.out.println("Offline mode enabled: skipping interactive GUI.");
            return;
        }

        InteractiveDebugger.runDesktopGui(model, reloadTileMatrices);
    }
}
