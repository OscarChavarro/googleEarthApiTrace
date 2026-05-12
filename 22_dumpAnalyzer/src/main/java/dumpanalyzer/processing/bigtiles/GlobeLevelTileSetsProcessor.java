package dumpanalyzer.processing.bigtiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import dumpanalyzer.config.Configuration;
import dumpanalyzer.logger.FatalErrorHandler;
import dumpanalyzer.model.Frame;
import dumpanalyzer.model.TileInstance;
import dumpanalyzer.processing.TriangleStripNeighborDetector;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;
import vsdk.toolkit.gui.feedback.parallel.ParallelProgressMonitorConsumer;
import vsdk.toolkit.gui.feedback.parallel.ParallelProgressMonitorEvent;
import vsdk.toolkit.gui.feedback.parallel.ParallelProgressMonitorProducer;

public final class GlobeLevelTileSetsProcessor {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final BigAreaCoveringTile DETECTOR = new BigAreaCoveringTile();
    private static final int POISON_FRAME_INDEX = -1;

    private GlobeLevelTileSetsProcessor() {
    }

    public static void preprocessGlobeLevelTileSets(List<Frame> frames) {
        if (frames == null || frames.isEmpty()) {
            return;
        }
        System.out.println("\n[5/5] Processing GlobeLevelTileSets and synthetic tiles:");

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
        writeGlobalPatches(
            frames,
            tileTriangleStripsToCountMapByFrame
        );
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
        GlobeLevelTileSetNeighborDetector.populateNeighbors(frame);
        List<TileInstance> selectableTiles = buildSelectableTiles(frame);
        populateSelectableNeighbors(frame, selectableTiles);
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
            if (!shouldHideSourceTile(tile)) {
                selectableTiles.add(tile);
            }
        }
        return selectableTiles.isEmpty() ? List.of() : List.copyOf(selectableTiles);
    }

    private static boolean shouldHideSourceTile(TileInstance tile) {
        if (tile == null || tile.isSyntheticGlobeLevelTile()) {
            return false;
        }
        return tile.getGlobeLevelTileSet() != null && !tile.getGlobeLevelTileSet().shouldDrawSourceTile();
    }

    private static void populateSelectableNeighbors(Frame frame, List<TileInstance> selectableTiles) {
        if (frame == null || selectableTiles == null || selectableTiles.isEmpty()) {
            return;
        }
        Frame selectableFrame = new Frame(
            frame.getId(),
            selectableTiles,
            List.of(),
            frame.getProjectionMatrix(),
            frame.getModelViewMatrix(),
            frame.getGoogleCamera()
        );
        Matrix4x4 projection = matrixFromColumnMajor(frame.getProjectionMatrix());
        if (projection == null) {
            return;
        }
        TriangleStripNeighborDetector.populateNeighbors(
            selectableFrame,
            projection,
            1,
            1,
            frame.getModelViewMatrix(),
            true
        );
    }

    private static Matrix4x4 matrixFromColumnMajor(double[] m) {
        if (m == null || m.length != 16) {
            return null;
        }
        Matrix4x4 out = new Matrix4x4();
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                out = out.withVal(row, col, m[col * 4 + row]);
            }
        }
        return out;
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
            FramePatchStats stats = preprocessFrame(frames.get(frameIndex));
            tileTriangleStripsToCountMapByFrame.set(frameIndex, stats.tileTriangleStripsToCountMap());
            progressProducer.update(0, 1, 1);
        }
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
            throw new IllegalStateException("Interrupted while producing GlobeLevelTileSet queue", e);
        }
    }

    private static int takeFrameTask(BlockingQueue<Integer> frameQueue) {
        try {
            return frameQueue.take();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while consuming GlobeLevelTileSet queue", e);
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

    private record FramePatchStats(
        List<TileGeometrySizeCountRecord> tileTriangleStripsToCountMap
    ) {}

    private record TileGeometrySizeCountRecord(int tileGeometrySize, int count) {}

    private record GlobalPatchFramesRecord(List<GlobalPatchFrameRecord> frames) {}

    private record GlobalPatchFrameRecord(
        int frameId,
        List<TileGeometrySizeCountRecord> tileTriangleStripsToCountMap
    ) {}
}
