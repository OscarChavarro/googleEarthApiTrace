package dumpanalyzer.processing.topleveltiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import dumpanalyzer.config.Configuration;
import dumpanalyzer.logger.FatalErrorHandler;
import dumpanalyzer.model.Frame;
import dumpanalyzer.model.TileInstance;
import dumpanalyzer.processing.TriangleMeshVertexComparator;
import dumpanalyzer.processing.TriangleStripTileClassifier;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.gui.feedback.parallel.ParallelProgressMonitorConsumer;
import vsdk.toolkit.gui.feedback.parallel.ParallelProgressMonitorEvent;
import vsdk.toolkit.gui.feedback.parallel.ParallelProgressMonitorProducer;

public final class TopLevelTilesJsonBuilder {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final BigAreaCoveringTile DETECTOR = new BigAreaCoveringTile();
    private static final TriangleStripTileClassifier TRIANGLE_STRIP_CLASSIFIER = new TriangleStripTileClassifier();
    private static final int POISON_FRAME_INDEX = -1;
    private static final int SPECIAL_TILE_TRIANGLE_STRIP_COUNT = 320;
    private static final int TOP_LEVEL_TILE_COLS = 20;
    private static final int TOP_LEVEL_TILE_ROWS = 16;
    private static volatile HashMap<TopLevelTileIdentity, Integer> globeLevelTileIdentityMap = new HashMap<>();

    private TopLevelTilesJsonBuilder() {
    }

    public static void preprocessGlobeLevelTileSets(List<Frame> frames) {
        if (frames == null || frames.isEmpty()) {
            return;
        }
        System.out.println("\n[5/5] Processing GlobeLevelTileSets and synthetic tiles:");
        initializeGlobeLevelTileIdentityMap(frames);

        int workerCount = Math.max(1, Runtime.getRuntime().availableProcessors());
        BlockingQueue<Integer> frameQueue = new LinkedBlockingQueue<>();
        ConcurrentLinkedQueue<ParallelProgressMonitorEvent> progressEvents = new ConcurrentLinkedQueue<>();
        ParallelProgressMonitorProducer progressProducer = new ParallelProgressMonitorProducer(progressEvents);
        ParallelProgressMonitorConsumer progressConsumer = new ParallelProgressMonitorConsumer(progressEvents);
        Thread progressThread = new Thread(progressConsumer, "globe-level-tiles-progress-consumer");
        AtomicReferenceArray<List<TileGeometrySizeCountRecord>> tileTriangleStripsToCountMapByFrame =
            new AtomicReferenceArray<>(frames.size());

        progressProducer.init(frames.size());
        progressThread.start();

        for (int i = 0; i < frames.size(); i++) {
            putFrameTask(frameQueue, i);
        }
        for (int i = 0; i < workerCount; i++) {
            putFrameTask(frameQueue, POISON_FRAME_INDEX);
        }

        Thread[] workers = new Thread[workerCount];
        for (int i = 0; i < workerCount; i++) {
            int workerId = i;
            workers[i] = new Thread(
                () -> consumeAndProcessFrames(
                    frameQueue,
                    frames,
                    progressProducer,
                    tileTriangleStripsToCountMapByFrame
                ),
                "globe-level-tiles-worker-" + workerId
            );
            workers[i].start();
        }

        for (Thread worker : workers) {
            joinOrFail(worker, "globe level tile processing");
        }
        progressProducer.finish();
        joinOrFail(progressThread, "globe level tile progress");
        propagateTopLevelTileAppearances(frames);
        writeTopLevelTiles(globeLevelTileIdentityMap);
        writeGlobalPatches(
            frames,
            tileTriangleStripsToCountMapByFrame
        );
    }

    public static Map<TopLevelTileIdentity, Integer> snapshotGlobeLevelTileIdentityMap() {
        return Collections.unmodifiableMap(new HashMap<>(globeLevelTileIdentityMap));
    }

    public static Integer findGlobeLevelTileIdentityId(TileInstance.TriangleStripGeometry geometry) {
        TopLevelTileIdentity probe = toIdentity(-1, geometry);
        if (probe == null) {
            return null;
        }
        for (Map.Entry<TopLevelTileIdentity, Integer> entry : globeLevelTileIdentityMap.entrySet()) {
            TopLevelTileIdentity identity = entry.getKey();
            if (identity != null && identity.compareTo(probe) == 0) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static FramePatchStats preprocessFrame(Frame frame) {
        if (frame == null) {
            return new FramePatchStats(List.of());
        }
        Map<Integer, Integer> tileGeometrySizeToCountMap = new HashMap<>();
        for (TileInstance tile : frame.getTiles()) {
            if (tile == null) {
                continue;
            }
            List<TileInstance.TriangleStripGeometry> geometries = tile.getTriangleStripGeometries();
            int triangleStripCount = geometries.size();
            tileGeometrySizeToCountMap.merge(triangleStripCount, 1, Integer::sum);
            tile.setGlobeLevelTileSet(DETECTOR.buildGlobeLevelTileSet(tile));
        }
        TopLevelTileNeighborDetector.populateNeighbors(frame);
        List<TileInstance> selectableTiles = buildSelectableTiles(frame);
        frame.setSelectableTilesOverride(selectableTiles);
        return new FramePatchStats(
            toSortedTileGeometrySizeCountRecords(tileGeometrySizeToCountMap)
        );
    }

    private static List<TileInstance> buildSelectableTiles(Frame frame) {
        if (frame == null || frame.getTiles().isEmpty()) {
            return List.of();
        }
        List<TileInstance> selectableTiles = new ArrayList<>();
        for (TileInstance tile : frame.getTiles()) {
            if (tile == null) {
                continue;
            }
            selectableTiles.add(tile);
        }
        return selectableTiles.isEmpty() ? List.of() : List.copyOf(selectableTiles);
    }

    private static void consumeAndProcessFrames(
        BlockingQueue<Integer> frameQueue,
        List<Frame> frames,
        ParallelProgressMonitorProducer progressProducer,
        AtomicReferenceArray<List<TileGeometrySizeCountRecord>> tileTriangleStripsToCountMapByFrame
    ) {
        while (true) {
            int frameIndex = takeFrameTask(frameQueue);
            if (frameIndex == POISON_FRAME_INDEX) {
                return;
            }
            if (frameIndex < 0 || frameIndex >= frames.size()) {
                continue;
            }
            Frame frame = frames.get(frameIndex);
            try {
                FramePatchStats stats = preprocessFrame(frame);
                tileTriangleStripsToCountMapByFrame.set(frameIndex, stats.tileTriangleStripsToCountMap());
            }
            finally {
                clearTriangleStripProcessingCaches(frame);
            }
            progressProducer.update(0, 1, 1);
        }
    }

    private static void initializeGlobeLevelTileIdentityMap(List<Frame> frames) {
        HashMap<TopLevelTileIdentity, Integer> identities = new HashMap<>();
        if (frames != null) {
            for (Frame frame : frames) {
                if (frame == null) {
                    continue;
                }
                for (TileInstance tile : frame.getTiles()) {
                    if (tile == null) {
                        continue;
                    }
                    List<TileInstance.TriangleStripGeometry> geometries = tile.getTriangleStripGeometries();
                    if (geometries.size() != SPECIAL_TILE_TRIANGLE_STRIP_COUNT) {
                        continue;
                    }
                    for (int stripIndex = 0; stripIndex < geometries.size(); stripIndex++) {
                        TopLevelTileIdentity identity = toIdentity(stripIndex, geometries.get(stripIndex));
                        if (identity != null) {
                            identities.put(identity, stripIndex);
                        }
                    }
                    globeLevelTileIdentityMap = identities;
                    return;
                }
            }
        }
        globeLevelTileIdentityMap = identities;
    }

    private static void writeGlobalPatches(
        List<Frame> frames,
        AtomicReferenceArray<List<TileGeometrySizeCountRecord>> tileTriangleStripsToCountMapByFrame
    ) {
        Path outputFile = Configuration.OUTPUT_ROOT.resolve("globalPatches.json");
        List<GlobalPatchFrameRecord> records = new ArrayList<>(frames.size());
        for (int i = 0; i < frames.size(); i++) {
            Frame frame = frames.get(i);
            if (frame == null) {
                continue;
            }
            records.add(new GlobalPatchFrameRecord(
                frame.getId(),
                tileTriangleStripsToCountMapByFrame.get(i) == null ? List.of() : tileTriangleStripsToCountMapByFrame.get(i)
            ));
        }
        try {
            JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), new GlobalPatchFramesRecord(records));
        }
        catch (IOException e) {
            FatalErrorHandler.fail(outputFile, "Cannot write globalPatches.json file: " + e.getMessage());
        }
    }

    private static void putFrameTask(BlockingQueue<Integer> frameQueue, int frameIndex) {
        try {
            frameQueue.put(frameIndex);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while producing TopLevelTileSet queue", e);
        }
    }

    private static int takeFrameTask(BlockingQueue<Integer> frameQueue) {
        try {
            return frameQueue.take();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while consuming TopLevelTileSet queue", e);
        }
    }

    private static void joinOrFail(Thread thread, String threadRole) {
        try {
            thread.join();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + threadRole + " thread", e);
        }
    }

    private static List<TileGeometrySizeCountRecord> toSortedTileGeometrySizeCountRecords(Map<Integer, Integer> counts) {
        List<TileGeometrySizeCountRecord> records = new ArrayList<>(counts.size());
        counts.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> records.add(new TileGeometrySizeCountRecord(entry.getKey(), entry.getValue())));
        return List.copyOf(records);
    }

    private static TopLevelTileIdentity toIdentity(int id, TileInstance.TriangleStripGeometry geometry) {
        if (geometry == null || geometry.vertices() == null || geometry.vertices().isEmpty()) {
            return null;
        }
        List<TileInstance.TriangleStripVertex> deDuplicated = TRIANGLE_STRIP_CLASSIFIER.deduplicateVertices(
            geometry.vertices(),
            TriangleMeshVertexComparator.vertexEpsilon(geometry)
        );
        if (deDuplicated.isEmpty()) {
            return null;
        }
        List<Vector3Dd> positions = new ArrayList<>(deDuplicated.size());
        for (TileInstance.TriangleStripVertex vertex : deDuplicated) {
            if (vertex == null) {
                continue;
            }
            positions.add(new Vector3Dd(vertex.x(), vertex.y(), vertex.z()));
        }
        if (positions.isEmpty()) {
            return null;
        }
        TopLevelTileIdentity.TexCoordRange texCoord = getTexCoordRange(geometry.vertices());
        int col = texCoord == null ? -1 : indexFromUnitRange((texCoord.u0() + texCoord.u1()) * 0.5, TOP_LEVEL_TILE_COLS);
        int row = texCoord == null ? -1 : indexFromUnitRange((texCoord.v0() + texCoord.v1()) * 0.5, TOP_LEVEL_TILE_ROWS);
        return new TopLevelTileIdentity(
            id,
            quadtreePathFromMatrixCell(row, col, TOP_LEVEL_TILE_ROWS, TOP_LEVEL_TILE_COLS),
            row,
            col,
            positions
        );
    }

    private static void propagateTopLevelTileAppearances(List<Frame> frames) {
        if (frames == null || frames.isEmpty() || globeLevelTileIdentityMap.isEmpty()) {
            return;
        }
        Map<Integer, TopLevelTileIdentity> byStripId = new HashMap<>();
        for (Map.Entry<TopLevelTileIdentity, Integer> entry : globeLevelTileIdentityMap.entrySet()) {
            if (entry != null && entry.getKey() != null && entry.getValue() != null) {
                byStripId.put(entry.getValue(), entry.getKey());
            }
        }
        for (Frame frame : frames) {
            if (frame == null) {
                continue;
            }
            try {
                for (TileInstance tile : frame.getTiles()) {
                    if (tile == null) {
                        continue;
                    }
                    List<TileInstance.TriangleStripGeometry> geometries = tile.getTriangleStripGeometries();
                    if (geometries.size() < 2) {
                        continue;
                    }
                    String imageId = normalizeScopedTileId(tile.getContentId());
                    String imagePath = toAbsolutePathString(tile.getTextureFile());
                    for (int stripIndex = 0; stripIndex < geometries.size(); stripIndex++) {
                        TileInstance.TriangleStripGeometry geometry = geometries.get(stripIndex);
                        Integer identityId = findGlobeLevelTileIdentityId(geometry);
                        if (identityId == null) {
                            continue;
                        }
                        TopLevelTileIdentity identity = byStripId.get(identityId);
                        if (identity == null) {
                            continue;
                        }
                        identity.addAppearance(frame.getId(), imageId, imagePath, getTexCoordRange(geometry.vertices()));
                    }
                }
            }
            finally {
                clearTriangleStripProcessingCaches(frame);
            }
        }
    }

    private static void clearTriangleStripProcessingCaches(Frame frame) {
        if (frame == null) {
            return;
        }
        for (TileInstance tile : frame.getTiles()) {
            if (tile != null) {
                tile.clearTriangleStripProcessingCaches();
            }
        }
    }

    private static void writeTopLevelTiles(HashMap<TopLevelTileIdentity, Integer> identities) {
        Path outputFile = Configuration.OUTPUT_ROOT.resolve("topLevelTiles.json");
        Map<String, TopLevelTileIdentity> byStripId = new LinkedHashMap<>();
        if (identities != null && !identities.isEmpty()) {
            List<Map.Entry<TopLevelTileIdentity, Integer>> entries = new ArrayList<>(identities.entrySet());
            entries.sort(Map.Entry.comparingByValue());
            for (Map.Entry<TopLevelTileIdentity, Integer> entry : entries) {
                if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                byStripId.put(Integer.toString(entry.getValue()), entry.getKey());
            }
        }
        try {
            JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), new TopLevelTilesRecord(byStripId));
        }
        catch (IOException e) {
            FatalErrorHandler.fail(outputFile, "Cannot write topLevelTiles.json file: " + e.getMessage());
        }
    }

    private static TopLevelTileIdentity.TexCoordRange getTexCoordRange(List<TileInstance.TriangleStripVertex> vertices) {
        if (vertices == null || vertices.isEmpty()) {
            return null;
        }
        boolean hasAny = false;
        double minU = Double.POSITIVE_INFINITY;
        double maxU = Double.NEGATIVE_INFINITY;
        double minV = Double.POSITIVE_INFINITY;
        double maxV = Double.NEGATIVE_INFINITY;
        for (TileInstance.TriangleStripVertex vertex : vertices) {
            if (vertex == null || !Double.isFinite(vertex.u()) || !Double.isFinite(vertex.v())) {
                continue;
            }
            hasAny = true;
            minU = Math.min(minU, vertex.u());
            maxU = Math.max(maxU, vertex.u());
            minV = Math.min(minV, vertex.v());
            maxV = Math.max(maxV, vertex.v());
        }
        if (!hasAny) {
            return null;
        }
        return new TopLevelTileIdentity.TexCoordRange(clamp01(minU), clamp01(minV), clamp01(maxU), clamp01(maxV));
    }

    private static int indexFromUnitRange(double value, int size) {
        if (size <= 0 || !Double.isFinite(value)) {
            return -1;
        }
        int out = (int) Math.floor(clamp01(value) * size);
        if (out >= size) {
            return size - 1;
        }
        if (out < 0) {
            return 0;
        }
        return out;
    }

    private static List<Integer> quadtreePathFromMatrixCell(int row, int col, int totalRows, int totalCols) {
        if (row < 0 || col < 0 || row >= totalRows || col >= totalCols) {
            return List.of();
        }
        List<Integer> path = new ArrayList<>();
        int localRow = row;
        int localCol = col;
        int height = totalRows;
        int width = totalCols;
        while (height > 1 || width > 1) {
            int southRows = (height + 1) / 2;
            int westCols = (width + 1) / 2;
            boolean south = localRow < southRows;
            boolean west = localCol < westCols;
            if (south && west) {
                path.add(0);
            }
            else if (south) {
                path.add(1);
            }
            else if (!west) {
                path.add(2);
            }
            else {
                path.add(3);
            }
            if (!south) {
                localRow -= southRows;
                height -= southRows;
            }
            else {
                height = southRows;
            }
            if (!west) {
                localCol -= westCols;
                width -= westCols;
            }
            else {
                width = westCols;
            }
        }
        return List.copyOf(path);
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private static String toAbsolutePathString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Path.of(value).toAbsolutePath().normalize().toString();
        }
        catch (RuntimeException ex) {
            return value;
        }
    }

    private static String normalizeScopedTileId(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isBlank()) {
            return null;
        }
        int separator = value.indexOf('_');
        if (separator <= 0 || separator >= value.length() - 1) {
            return value;
        }
        try {
            int frameId = Integer.parseInt(value.substring(0, separator));
            int tileId = Integer.parseInt(value.substring(separator + 1));
            if (frameId < 0 || tileId < 0) {
                return null;
            }
            return String.format("%05d_%d", frameId, tileId);
        }
        catch (NumberFormatException ex) {
            return value;
        }
    }

    private record FramePatchStats(
        List<TileGeometrySizeCountRecord> tileTriangleStripsToCountMap
    ) {}

    private record TileGeometrySizeCountRecord(int tileGeometrySize, int count) {}

    private record GlobalPatchFramesRecord(List<GlobalPatchFrameRecord> frames) {}

    private record GlobalPatchFrameRecord(
        int frameId,
        List<TileGeometrySizeCountRecord> tileTriangleStripsToCountMap
    ) {}

    private record TopLevelTilesRecord(Map<String, TopLevelTileIdentity> byStripId) {}
}
