package frametexturenormalizer.processing.matrix;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.TileInstance;
import frametexturenormalizer.model.TileMatrix;
import frametexturenormalizer.processing.GeometricNeighborhoodSanitizer;
import frametexturenormalizer.processing.NeighborhoodDebugReporter;
import frametexturenormalizer.processing.TileFiltererByTextureCoverage;
import frametexturenormalizer.processing.TileTextureNormalizer;

public final class TileMatrixProcessor {
    private final TileSetToMatrixConverter convertor = new TileSetToMatrixConverter();

    public TileMatrixProcessingResult normalizeAndConvertTileMatrices(
        List<FrameData> frames,
        List<List<String>> duplicatedTextureGroups
    ) {
        if (frames == null || frames.isEmpty()) {
            return new TileMatrixProcessingResult(List.of(), List.of());
        }

        Map<String, String> canonicalTextureByTexture = TileTextureNormalizer.buildCanonicalTextureMap(duplicatedTextureGroups);
        ConcurrentLinkedQueue<FrameRequest> pendingFrames = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<IndexedFrameResult> completed = new ConcurrentLinkedQueue<>();
        AtomicBoolean producerDone = new AtomicBoolean(false);

        Thread producer = new Thread(
            () -> produceFrameQueue(frames, pendingFrames, producerDone),
            "tile-normalize-convert-producer"
        );
        producer.start();

        int threadCount = Math.max(1, Runtime.getRuntime().availableProcessors());
        Thread[] workers = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            workers[i] = new Thread(
                () -> consumeFrameQueue(pendingFrames, completed, producerDone, canonicalTextureByTexture),
                "tile-normalize-convert-worker-" + i
            );
            workers[i].start();
        }

        for (Thread worker : workers) {
            join(worker);
        }
        join(producer);

        List<IndexedFrameResult> ordered = new ArrayList<>(completed);
        ordered.sort(Comparator.comparingInt(IndexedFrameResult::index));

        List<FrameData> out = new ArrayList<>(ordered.size());
        List<TileMatrix> matrices = new ArrayList<>(ordered.size());
        for (IndexedFrameResult result : ordered) {
            if (result == null) {
                continue;
            }
            out.add(result.frame());
            if (result.matrix() != null) {
                matrices.add(result.matrix());
            }
        }
        return new TileMatrixProcessingResult(out, matrices);
    }

    public List<TileInstance> applyMatrixCoordinates(List<TileInstance> tiles, TileMatrix matrix) {
        if (tiles == null || tiles.isEmpty() || matrix == null || matrix.getTiles() == null) {
            return List.of();
        }

        Map<Integer, TileMatrix.TileCoord> byId = new HashMap<>();
        for (TileMatrix.TileCoord c : matrix.getTiles()) {
            if (c != null) {
                byId.put(c.tileId(), c);
            }
        }

        List<TileInstance> out = new ArrayList<>(tiles.size());
        for (TileInstance tile : tiles) {
            if (tile == null) {
                continue;
            }
            TileMatrix.TileCoord c = byId.get(tile.getTileId());
            TileInstance tileWithCoords = new TileInstance(
                tile.getTileId(),
                tile.getFrameId(),
                tile.getTextureFile(),
                tile.getSouthNeighbor(),
                tile.getNorthNeighbor(),
                tile.getEastNeighbor(),
                tile.getWestNeighbor(),
                tile.getTriangleStrip(),
                tile.getModelViewMatrix(),
                c == null ? null : c.i(),
                c == null ? null : c.j(),
                tile.isIncorrectMatrixMapping()
            );
            tileWithCoords.setWestCuttingCell(tile.isWestCuttingCell());
            out.add(tileWithCoords);
        }
        return out;
    }

    private List<TileInstance> markIncorrectMatrixMappings(
        List<TileInstance> tiles,
        Set<Integer> conflictIds,
        Map<Integer, MatrixTileCoordinate> partialCoords
    ) {
        if (tiles == null || tiles.isEmpty()) {
            return List.of();
        }
        List<TileInstance> out = new ArrayList<>(tiles.size());
        for (TileInstance tile : tiles) {
            if (tile == null) {
                continue;
            }
            boolean incorrect = conflictIds != null && conflictIds.contains(tile.getTileId());
            MatrixTileCoordinate coord = partialCoords == null ? null : partialCoords.get(tile.getTileId());
            TileInstance flaggedTile = new TileInstance(
                tile.getTileId(),
                tile.getFrameId(),
                tile.getTextureFile(),
                tile.getSouthNeighbor(),
                tile.getNorthNeighbor(),
                tile.getEastNeighbor(),
                tile.getWestNeighbor(),
                tile.getTriangleStrip(),
                tile.getModelViewMatrix(),
                coord == null ? tile.getMatrixI() : coord.i(),
                coord == null ? tile.getMatrixJ() : coord.j(),
                incorrect
            );
            flaggedTile.setWestCuttingCell(tile.isWestCuttingCell());
            out.add(flaggedTile);
        }
        return out;
    }

    private IndexedFrameResult processFrameRequest(
        FrameRequest request,
        Map<String, String> canonicalTextureByTexture,
        TileFiltererByTextureCoverage textureCoverageFilterer,
        GeometricNeighborhoodSanitizer sanitizer,
        TileSetToMatrixConverter localConvertor
    ) {
        if (request == null || request.frame() == null) {
            return null;
        }
        FrameData normalized = TileTextureNormalizer.normalizeFrame(request.frame(), canonicalTextureByTexture);
        NeighborhoodDebugReporter.dumpFrame("normalized", normalized);
        FrameData sanitized = sanitizer.sanitizeFrame(normalized);
        NeighborhoodDebugReporter.dumpFrame("sanitized-after-normalize", sanitized);
        FrameData filtered = textureCoverageFilterer.removeNonFullResolutionTiles(sanitized);
        NeighborhoodDebugReporter.dumpFrame("full-res-filtered", filtered);
        TileMatrix matrix = localConvertor.convert(filtered);
        if (matrix == null) {
            Set<Integer> conflictIds = localConvertor.getLastConflictingTileIds();
            Map<Integer, MatrixTileCoordinate> partialCoords = localConvertor.getLastCoordinatesByTileId();
            List<TileInstance> flaggedTiles = markIncorrectMatrixMappings(filtered.getTiles(), conflictIds, partialCoords);
            return new IndexedFrameResult(
                request.index(),
                new FrameData(
                    filtered.getId(),
                    flaggedTiles,
                    filtered.getLines(),
                    filtered.getCameraState(),
                    filtered.getProjectionMatrix(),
                    filtered.getModelViewMatrix(),
                    true
                ),
                null
            );
        }

        List<TileInstance> tilesWithCoords = applyMatrixCoordinates(filtered.getTiles(), matrix);
        FrameData frameWithMatrix = new FrameData(
            filtered.getId(),
            tilesWithCoords,
            filtered.getLines(),
            filtered.getCameraState(),
            filtered.getProjectionMatrix(),
            filtered.getModelViewMatrix(),
            false
        );
        return new IndexedFrameResult(request.index(), frameWithMatrix, matrix);
    }

    private void consumeFrameQueue(
        ConcurrentLinkedQueue<FrameRequest> pendingFrames,
        ConcurrentLinkedQueue<IndexedFrameResult> completed,
        AtomicBoolean producerDone,
        Map<String, String> canonicalTextureByTexture
    ) {
        TileSetToMatrixConverter localConvertor = new TileSetToMatrixConverter();
        TileFiltererByTextureCoverage textureCoverageFilterer = new TileFiltererByTextureCoverage();
        GeometricNeighborhoodSanitizer sanitizer = new GeometricNeighborhoodSanitizer();
        while (true) {
            FrameRequest request = pendingFrames.poll();
            if (request == null) {
                if (producerDone.get()) {
                    return;
                }
                Thread.yield();
                continue;
            }
            IndexedFrameResult result = processFrameRequest(
                request,
                canonicalTextureByTexture,
                textureCoverageFilterer,
                sanitizer,
                localConvertor
            );
            if (result != null) {
                completed.add(result);
            }
        }
    }

    private static void produceFrameQueue(
        List<FrameData> frames,
        ConcurrentLinkedQueue<FrameRequest> pendingFrames,
        AtomicBoolean producerDone
    ) {
        try {
            for (int i = 0; i < frames.size(); i++) {
                FrameData frame = frames.get(i);
                if (frame != null) {
                    pendingFrames.add(new FrameRequest(i, frame));
                }
            }
        }
        finally {
            producerDone.set(true);
        }
    }

    private static void join(Thread thread) {
        if (thread == null) {
            return;
        }
        boolean done = false;
        while (!done) {
            try {
                thread.join();
                done = true;
            }
            catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                done = true;
            }
        }
    }

    private record FrameRequest(int index, FrameData frame) {
    }

    private record IndexedFrameResult(int index, FrameData frame, TileMatrix matrix) {
    }
}
