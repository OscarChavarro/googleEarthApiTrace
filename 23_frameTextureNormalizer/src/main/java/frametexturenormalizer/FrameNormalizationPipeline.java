package frametexturenormalizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import frametexturenormalizer.io.FrameJsonReader;
import frametexturenormalizer.io.FrameMatrixJsonExporter;
import frametexturenormalizer.io.FrameJsonSessionReader;
import frametexturenormalizer.io.WestCuttersJsonReader;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.TileInstance;
import frametexturenormalizer.model.contract.ScopedTileIds;
import frametexturenormalizer.model.state.FrameTextureNormalizerState;
import frametexturenormalizer.model.TileMatrix;
import frametexturenormalizer.processing.filtering.FrameFiltererByTileCount;
import frametexturenormalizer.processing.filtering.TileFiltererByConnectedComponents;
import frametexturenormalizer.processing.filtering.TileFiltererByGeometricNullNeighbors;
import frametexturenormalizer.processing.filtering.TileFilteringByError;
import frametexturenormalizer.processing.preparation.DuplicatedTextureFilenameMapper;
import frametexturenormalizer.processing.preparation.Sha256SignatureGenerator;
import frametexturenormalizer.processing.matrix.TileMatrixProcessingResult;
import frametexturenormalizer.processing.matrix.TileMatrixProcessor;
import frametexturenormalizer.processing.uncles.ToUncleRelationship;

public final class FrameNormalizationPipeline {
    private final FrameJsonSessionReader traceSessionReader = new FrameJsonSessionReader();
    private final TileFiltererByConnectedComponents connectedComponentsFilterer =
        new TileFiltererByConnectedComponents();
    private final TileFiltererByGeometricNullNeighbors tileFilterer =
        new TileFiltererByGeometricNullNeighbors();
    private final FrameFiltererByTileCount frameFiltererByTileCount = new FrameFiltererByTileCount();
    private final TileFilteringByError tileFilteringByError = new TileFilteringByError();
    private final FrameMatrixJsonExporter tileMatrixExporter = new FrameMatrixJsonExporter();
    private final TileMatrixProcessor tileMatrixProcessor = new TileMatrixProcessor();
    private final WestCuttersJsonReader westCutterCacheReader = new WestCuttersJsonReader();

    public void run(FrameTextureNormalizerState model, int startFrame, int endFrame, boolean offline) {
        if (model == null) {
            return;
        }

        System.out.print("Loading traced frames... ");
        FrameJsonReader.loadTracedFrames(
            traceSessionReader,
            connectedComponentsFilterer,
            tileFilterer,
            model
        );
        applyFrameRange(model, startFrame, endFrame);
        int minTilesExclusive = offline ? 0 : 1;
        model.setFrames(frameFiltererByTileCount.keepFramesWithMoreThanTiles(model.getFrames(), minTilesExclusive));
        Map<String, String> canonicalReferenceIdByOccurrence = canonicalReferenceIds(model.getFrames());
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
        tileMatrixExporter.export(matrices, canonicalReferenceIdByOccurrence);
        model.setFrames(cleanFrames);
        System.out.println("OK");

        System.out.print("Restoring west cutters for editor/UI... ");
        westCutterCacheReader.restore(model);
        System.out.println("OK");
    }

    private static void applyFrameRange(FrameTextureNormalizerState model, int startFrame, int endFrame) {
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

    static List<TileMatrix> deduplicateMatricesByTileIds(List<TileMatrix> matrices) {
        if (matrices == null || matrices.isEmpty()) {
            return List.of();
        }
        Map<String, TileMatrix> bySignature = new LinkedHashMap<>();
        for (TileMatrix matrix : matrices) {
            if (matrix == null) {
                continue;
            }
            String signature = tileIdSignature(matrix);
            bySignature.merge(signature, matrix, FrameNormalizationPipeline::mergeRelationshipMetadata);
        }
        return List.copyOf(bySignature.values());
    }

    private static TileMatrix mergeRelationshipMetadata(TileMatrix representative, TileMatrix duplicate) {
        Map<Integer, List<ToUncleRelationship>> duplicateRelationships = new LinkedHashMap<>();
        for (TileMatrix.TileCoord tile : duplicate.getTiles()) {
            duplicateRelationships.put(tile.tileId(), tile.uncles());
        }

        List<TileMatrix.TileCoord> mergedTiles = new ArrayList<>(representative.getTiles().size());
        for (TileMatrix.TileCoord tile : representative.getTiles()) {
            List<ToUncleRelationship> mergedRelationships = new ArrayList<>();
            if (tile.uncles() != null) {
                mergedRelationships.addAll(tile.uncles());
            }
            List<ToUncleRelationship> additional = duplicateRelationships.get(tile.tileId());
            if (additional != null) {
                for (ToUncleRelationship relationship : additional) {
                    if (relationship != null && !mergedRelationships.contains(relationship)) {
                        mergedRelationships.add(relationship);
                    }
                }
            }
            mergedTiles.add(new TileMatrix.TileCoord(
                tile.tileId(),
                tile.i(),
                tile.j(),
                tile.textureFile(),
                List.copyOf(mergedRelationships)
            ));
        }
        return new TileMatrix(
            representative.getFrameId(),
            representative.getRows(),
            representative.getCols(),
            mergedTiles
        );
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

    private static Map<String, String> canonicalReferenceIds(List<FrameData> frames) {
        Map<String, String> out = new LinkedHashMap<>();
        if (frames == null) {
            return out;
        }
        for (FrameData frame : frames) {
            if (frame == null || frame.getTiles() == null) {
                continue;
            }
            for (TileInstance tile : frame.getTiles()) {
                if (tile == null) {
                    continue;
                }
                String occurrenceId = ScopedTileIds.normalize(tile.getScopedId());
                String canonicalId = ScopedTileIds.formatFromTextureFile(
                    tile.getTextureFile(),
                    tile.getFrameId(),
                    tile.getTileId()
                );
                if (occurrenceId != null && canonicalId != null && !occurrenceId.equals(canonicalId)) {
                    out.put(occurrenceId, canonicalId);
                }
            }
        }
        return out;
    }
}
