package frametexturenormalizer;

import java.util.List;

import frametexturenormalizer.io.FrameReader;
import frametexturenormalizer.io.TileMatrixExporter;
import frametexturenormalizer.io.TraceSessionReader;
import frametexturenormalizer.io.WestCacheReader;
import frametexturenormalizer.model.PyramidalImageModel;
import frametexturenormalizer.model.TileMatrix;
import frametexturenormalizer.options.CommandLineOptions;
import frametexturenormalizer.processing.FrameFiltererByTileCount;
import frametexturenormalizer.processing.TileFiltererByConnectedComponents;
import frametexturenormalizer.processing.DuplicatedTextureFilenameMapper;
import frametexturenormalizer.processing.Sha256SignatureGenerator;
import frametexturenormalizer.processing.TileFiltererByGeometricNullNeighbors;
import frametexturenormalizer.processing.TileFilteringByErrored;
import frametexturenormalizer.processing.matrix.TileMatrixFiltererByConsistency;
import frametexturenormalizer.processing.matrix.TileMatrixProcessingResult;
import frametexturenormalizer.processing.matrix.TileMatrixProcessor;
import frametexturenormalizer.processing.TileTextureNormalizer;

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
        System.out.println("[main] frames after loadTracedFrames: " + model.getFrames().size());
        model.setFrames(frameFiltererByTileCount.keepFramesWithMoreThanTiles(model.getFrames(), 1));
        System.out.println("[main] frames after keepFramesWithMoreThanTiles(>1): " + model.getFrames().size());
        System.out.println("OK");

        System.out.print("SHA signature validation... ");
        Sha256SignatureGenerator.verifyTextureFilesHasSignatureFile(model.getFrames());
        System.out.println("OK");

        System.out.print("Duplicated texture filename mapping... ");
        List<List<String>> duplicatedTextureGroups = DuplicatedTextureFilenameMapper.loadOrCreate(model.getFrames());
        System.out.println("OK");

        if (!offline) {
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
            new WestCacheReader().restore(model);
            System.out.println("OK");
        }
        else {
            System.out.println("Offline mode: matrix merge/conversion pipeline disabled (commented).");
        }

        if (offline) {
            System.out.println("Offline mode enabled: skipping interactive GUI.");
            return;
        }

        InteractiveDebugger.runDesktopGui(model, reloadTileMatrices);
    }
}
