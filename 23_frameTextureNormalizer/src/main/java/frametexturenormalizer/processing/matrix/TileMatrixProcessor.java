package frametexturenormalizer.processing.matrix;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.TileInstance;
import frametexturenormalizer.model.TileMatrix;
import frametexturenormalizer.processing.filtering.TileFiltererByConnectedComponents;
import frametexturenormalizer.processing.filtering.TileFiltererByIsolatedTiles;
import frametexturenormalizer.processing.filtering.TileFiltererByTextureCoverage;
import frametexturenormalizer.processing.neighborhood.GeometricNeighborhoodSanitizer;
import frametexturenormalizer.processing.texture.TileTextureNormalizer;

public final class TileMatrixProcessor {
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
        List<TileMatrix> matrices = new ArrayList<>();
        for (IndexedFrameResult result : ordered) {
            if (result == null) {
                continue;
            }
            out.add(result.frame());
            if (result.matrices() != null && !result.matrices().isEmpty()) {
                matrices.addAll(result.matrices());
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
            TileInstance tileWithCoordinates = getTileInstance(tile, c == null ? null : c.i(), c == null ? null : c.j(), tile.isIncorrectMatrixMapping());
            out.add(tileWithCoordinates);
        }
        return out;
    }

    private static TileInstance getTileInstance(TileInstance tile, Integer c, Integer c1, boolean tile1) {
        TileInstance tileWithCoordinates = getTileInstance2(tile, c, c1, tile1);
        return tileWithCoordinates;
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
            MatrixTileCoordinate coordinate = partialCoords == null ? null : partialCoords.get(tile.getTileId());
            TileInstance flaggedTile = getTileInstance2(tile, coordinate == null ? tile.getMatrixI() : coordinate.i(), coordinate == null ? tile.getMatrixJ() : coordinate.j(), incorrect);
            out.add(flaggedTile);
        }
        return out;
    }

    private static TileInstance getTileInstance2(TileInstance tile, int coordinate, int coordinate1, boolean incorrect) {
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
                coordinate,
                coordinate1,
                incorrect,
                tile.getUncles()
        );
        flaggedTile.setWestCuttingCell(tile.isWestCuttingCell());
        return flaggedTile;
    }

    private IndexedFrameResult processFrameRequest(
        FrameRequest request,
        Map<String, String> canonicalTextureByTexture,
        TileFiltererByTextureCoverage textureCoverageFilterer,
        TileFiltererByIsolatedTiles isolatedTileFilterer,
        GeometricNeighborhoodSanitizer sanitizer,
        TileFiltererByConnectedComponents connectedComponentsFilterer,
        TileSetToMatrixConverter localConvertor
    ) {
        if (request == null || request.frame() == null) {
            return null;
        }
        FrameData normalized = TileTextureNormalizer.normalizeFrame(request.frame(), canonicalTextureByTexture);
        List<List<TileInstance>> components = connectedComponentsFilterer.partitionReciprocalComponents(normalized.getTiles());
        components = repartitionFilteredComponents(normalized, components, connectedComponentsFilterer, tiles ->
            sanitizer.sanitizeFrame(copyFrameWithTiles(normalized, tiles, false)).getTiles()
        );
        components = repartitionFilteredComponents(normalized, components, connectedComponentsFilterer, tiles ->
            textureCoverageFilterer.removeNonFullResolutionTiles(copyFrameWithTiles(normalized, tiles, false)).getTiles()
        );
        components = repartitionFilteredComponents(normalized, components, connectedComponentsFilterer, tiles ->
            isolatedTileFilterer.removeIsolatedTiles(copyFrameWithTiles(normalized, tiles, false)).getTiles()
        );

        List<TileMatrix> matrices = new ArrayList<>();
        List<TileInstance> displayTiles = new ArrayList<>();
        boolean hadErrors = false;
        for (List<TileInstance> component : components) {
            if (component == null || component.size() < 2) {
                continue;
            }
            FrameData componentFrame = copyFrameWithTiles(normalized, component, false);
            TileMatrix matrix = localConvertor.convert(componentFrame);
            if (!isValidMatrix(matrix)) {
                hadErrors = true;
                Set<Integer> conflictIds = localConvertor.getLastConflictingTileIds();
                Map<Integer, MatrixTileCoordinate> partialCoords = localConvertor.getLastCoordinatesByTileId();
                displayTiles.addAll(markIncorrectMatrixMappings(component, conflictIds, partialCoords));
                continue;
            }
            matrices.add(matrix);
            displayTiles.addAll(applyMatrixCoordinates(component, matrix));
        }

        FrameData frameWithMatrices = copyFrameWithTiles(normalized, displayTiles, hadErrors && matrices.isEmpty());
        return new IndexedFrameResult(request.index(), frameWithMatrices, List.copyOf(matrices));
    }

    private void consumeFrameQueue(
        ConcurrentLinkedQueue<FrameRequest> pendingFrames,
        ConcurrentLinkedQueue<IndexedFrameResult> completed,
        AtomicBoolean producerDone,
        Map<String, String> canonicalTextureByTexture
    ) {
        TileSetToMatrixConverter localConvertor = new TileSetToMatrixConverter();
        TileFiltererByTextureCoverage textureCoverageFilterer = new TileFiltererByTextureCoverage();
        TileFiltererByIsolatedTiles isolatedTileFilterer = new TileFiltererByIsolatedTiles();
        GeometricNeighborhoodSanitizer sanitizer = new GeometricNeighborhoodSanitizer();
        TileFiltererByConnectedComponents connectedComponentsFilterer = new TileFiltererByConnectedComponents();
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
                isolatedTileFilterer,
                sanitizer,
                connectedComponentsFilterer,
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

    private static FrameData copyFrameWithTiles(FrameData source, List<TileInstance> tiles, boolean withMatrixErrors) {
        if (source == null) {
            return null;
        }
        return new FrameData(
            source.getId(),
            tiles,
            source.getLines(),
            source.getCameraState(),
            source.getProjectionMatrix(),
            source.getModelViewMatrix(),
            withMatrixErrors
        );
    }

    private static List<List<TileInstance>> repartitionFilteredComponents(
        FrameData frame,
        List<List<TileInstance>> components,
        TileFiltererByConnectedComponents connectedComponentsFilterer,
        java.util.function.Function<List<TileInstance>, List<TileInstance>> filter
    ) {
        if (frame == null || components == null || components.isEmpty()) {
            return List.of();
        }
        List<List<TileInstance>> out = new ArrayList<>();
        for (List<TileInstance> component : components) {
            if (component == null || component.isEmpty()) {
                continue;
            }
            List<TileInstance> filteredTiles = filter.apply(component);
            if (filteredTiles == null || filteredTiles.isEmpty()) {
                continue;
            }
            out.addAll(connectedComponentsFilterer.partitionReciprocalComponents(filteredTiles));
        }
        return out;
    }

    private static boolean isValidMatrix(TileMatrix matrix) {
        if (matrix == null || matrix.getTiles() == null || matrix.getTiles().size() < 2) {
            return false;
        }
        Set<Integer> tileIds = new HashSet<>();
        Set<String> coordinates = new HashSet<>();
        for (TileMatrix.TileCoord tile : matrix.getTiles()) {
            if (tile == null) {
                return false;
            }
            if (tile.i() < 0 || tile.j() < 0 || tile.i() >= matrix.getRows() || tile.j() >= matrix.getCols()) {
                return false;
            }
            if (!tileIds.add(tile.tileId())) {
                return false;
            }
            if (!coordinates.add(tile.i() + ":" + tile.j())) {
                return false;
            }
        }
        return isOrthogonallyConnected(matrix.getTiles());
    }

    private static boolean isOrthogonallyConnected(List<TileMatrix.TileCoord> tiles) {
        if (tiles == null || tiles.isEmpty()) {
            return false;
        }
        Map<String, TileMatrix.TileCoord> byPosition = new HashMap<>();
        for (TileMatrix.TileCoord tile : tiles) {
            if (tile != null) {
                byPosition.put(tile.i() + ":" + tile.j(), tile);
            }
        }
        ArrayList<TileMatrix.TileCoord> queue = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        TileMatrix.TileCoord start = tiles.get(0);
        if (start == null) {
            return false;
        }
        queue.add(start);
        visited.add(start.i() + ":" + start.j());
        for (int index = 0; index < queue.size(); index++) {
            TileMatrix.TileCoord current = queue.get(index);
            visitNeighbor(current.i() - 1, current.j(), byPosition, visited, queue);
            visitNeighbor(current.i() + 1, current.j(), byPosition, visited, queue);
            visitNeighbor(current.i(), current.j() - 1, byPosition, visited, queue);
            visitNeighbor(current.i(), current.j() + 1, byPosition, visited, queue);
        }
        return visited.size() == byPosition.size();
    }

    private static void visitNeighbor(
        int i,
        int j,
        Map<String, TileMatrix.TileCoord> byPosition,
        Set<String> visited,
        List<TileMatrix.TileCoord> queue
    ) {
        String key = i + ":" + j;
        TileMatrix.TileCoord neighbor = byPosition.get(key);
        if (neighbor != null && visited.add(key)) {
            queue.add(neighbor);
        }
    }
}
