package frametexturenormalizer;

import java.util.List;
import java.util.stream.Collectors;

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
import frametexturenormalizer.render.Jogl4PyramidalImageBuilderRenderer;

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
        Runnable reloadTileMatricesRaw = FrameReader.loadTracedFrames(
            traceSessionReader,
            connectedComponentsFilterer,
            tileFilterer,
            model
        );
        int startFrame = CommandLineOptions.startFrame(args, 0);
        int endFrame = CommandLineOptions.endFrame(args, Integer.MAX_VALUE);
        if (endFrame < startFrame) {
            endFrame = startFrame;
        }
        final int startFrameFinal = startFrame;
        final int endFrameFinal = endFrame;
        Runnable applyFrameRange = () -> model.setFrames(
            model.getFrames().stream()
                .filter(frame -> frame.getId() >= startFrameFinal && frame.getId() <= endFrameFinal)
                .collect(Collectors.toList())
        );
        applyFrameRange.run();
        Runnable reloadTileMatrices = () -> {
            reloadTileMatricesRaw.run();
            applyFrameRange.run();
        };
        int minTilesExclusive = offline ? 0 : 1;
        model.setFrames(frameFiltererByTileCount.keepFramesWithMoreThanTiles(model.getFrames(), minTilesExclusive));
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
        new WestCacheReader().restore(model);
        System.out.println("OK");

        if (offline) {
            if (model.getSelectedFrame() == null) {
                System.out.println(
                    "Offline error: no frames available after applying range and filters. "
                        + "Check --start-frame/--end-frame and input data in "
                        + frametexturenormalizer.config.Configuration.INPUT_PATH
                );
                return;
            }
            renderOffline(
                model,
                CommandLineOptions.offlineOutputPath(args),
                CommandLineOptions.offlineWidth(args),
                CommandLineOptions.offlineHeight(args)
            );
            return;
        }

        InteractiveDebugger.runDesktopGui(model, reloadTileMatrices);
    }

    private static void renderOffline(PyramidalImageModel model, String outputPath, int width, int height) {
        try {
            Jogl4PyramidalImageBuilderRenderer renderer = new Jogl4PyramidalImageBuilderRenderer(model);
            renderer.startOffscreen(outputPath, width, height);
        }
        catch (Throwable t) {
            System.out.println(
                "Warning: Offline image export is not available because there is no access to a graphics system."
            );
        }
    }
}
