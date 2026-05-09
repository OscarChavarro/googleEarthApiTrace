package frametexturenormalizer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import frametexturenormalizer.io.FrameReader;
import frametexturenormalizer.io.TileMatrixExporter;
import frametexturenormalizer.io.TraceSessionReader;
import frametexturenormalizer.io.WestCutterCacheReader;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.FrameTextureNormalizerModel;
import frametexturenormalizer.model.TileMatrix;
import frametexturenormalizer.processing.filtering.FrameFiltererByTileCount;
import frametexturenormalizer.processing.filtering.TileFiltererByConnectedComponents;
import frametexturenormalizer.processing.filtering.TileFiltererByGeometricNullNeighbors;
import frametexturenormalizer.processing.filtering.TileFilteringByError;
import frametexturenormalizer.processing.preparation.DuplicatedTextureFilenameMapper;
import frametexturenormalizer.processing.preparation.Sha256SignatureGenerator;
import frametexturenormalizer.processing.matrix.TileMatrixProcessingResult;
import frametexturenormalizer.processing.matrix.TileMatrixProcessor;

public final class NormalizationPipeline {
    private final TraceSessionReader traceSessionReader = new TraceSessionReader();
    private final TileFiltererByConnectedComponents connectedComponentsFilterer =
        new TileFiltererByConnectedComponents();
    private final TileFiltererByGeometricNullNeighbors tileFilterer =
        new TileFiltererByGeometricNullNeighbors();
    private final FrameFiltererByTileCount frameFiltererByTileCount = new FrameFiltererByTileCount();
    private final TileFilteringByError tileFilteringByError = new TileFilteringByError();
    private final TileMatrixExporter tileMatrixExporter = new TileMatrixExporter();
    private final TileMatrixProcessor tileMatrixProcessor = new TileMatrixProcessor();
    private final WestCutterCacheReader westCutterCacheReader = new WestCutterCacheReader();

    public void run(FrameTextureNormalizerModel model, int startFrame, int endFrame, boolean offline) {
        if (model == null) {
            return;
        }

        System.out.print("Loading traced frames... ");
        FrameReader.loadTracedFrames(
            traceSessionReader,
            connectedComponentsFilterer,
            tileFilterer,
            model
        );
        applyFrameRange(model, startFrame, endFrame);
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
        TileMatrixProcessingResult matrixResult = tileMatrixProcessor.normalizeAndConvertTileMatrices(
            model.getFrames(),
            duplicatedTextureGroups
        );
        System.out.println("OK");

        List<FrameData> cleanFrames = tileFilteringByError.removeFramesWithErrors(matrixResult.frames());

        System.out.print("Exporting matrices... ");
        List<TileMatrix> matrices = deduplicateMatricesByTileIds(matrixResult.matrices());
        tileMatrixExporter.export(matrices);
        model.setFrames(cleanFrames);
        System.out.println("OK");

        System.out.print("Restoring west cutters for editor/UI... ");
        westCutterCacheReader.restore(model);
        System.out.println("OK");
    }

    private static void applyFrameRange(FrameTextureNormalizerModel model, int startFrame, int endFrame) {
        if (model == null) {
            return;
        }
        int boundedEndFrame = Math.max(endFrame, startFrame);
        final int startFrameFinal = startFrame;
        final int endFrameFinal = boundedEndFrame;
        model.setFrames(
            model.getFrames().stream()
                .filter(frame -> frame.getId() >= startFrameFinal && frame.getId() <= endFrameFinal)
                .collect(Collectors.toList())
        );
    }

    private static List<TileMatrix> deduplicateMatricesByTileIds(List<TileMatrix> matrices) {
        if (matrices == null || matrices.isEmpty()) {
            return List.of();
        }
        List<TileMatrix> out = new ArrayList<>(matrices.size());
        Set<String> seenSignatures = new LinkedHashSet<>();
        for (TileMatrix matrix : matrices) {
            if (matrix == null) {
                continue;
            }
            String signature = tileIdSignature(matrix);
            if (!seenSignatures.add(signature)) {
                continue;
            }
            out.add(matrix);
        }
        return out;
    }

    private static String tileIdSignature(TileMatrix matrix) {
        if (matrix == null || matrix.getTiles() == null || matrix.getTiles().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (TileMatrix.TileCoord tile : matrix.getTiles()) {
            if (tile != null && tile.tileId() >= 0) {
                if (!sb.isEmpty()) {
                    sb.append(',');
                }
                sb.append(tile.tileId());
            }
        }
        return sb.toString();
    }
}
