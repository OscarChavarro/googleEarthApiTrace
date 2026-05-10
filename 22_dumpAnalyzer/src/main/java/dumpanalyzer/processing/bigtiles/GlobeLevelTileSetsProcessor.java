package dumpanalyzer.processing.bigtiles;

import dumpanalyzer.model.Frame;
import dumpanalyzer.model.TileInstance;
import dumpanalyzer.processing.TriangleStripNeighborDetector;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;
import vsdk.toolkit.common.linealAlgebra.Vector3D;
import vsdk.toolkit.gui.feedback.parallel.ParallelProgressMonitorConsumer;
import vsdk.toolkit.gui.feedback.parallel.ParallelProgressMonitorEvent;
import vsdk.toolkit.gui.feedback.parallel.ParallelProgressMonitorProducer;

public final class GlobeLevelTileSetsProcessor {
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
                () -> consumeAndProcessFrames(frameQueue, frames, progressProducer),
                "globe-level-tiles-worker-" + workerId
            );
            workers[i].start();
        }

        for (Thread worker : workers) {
            joinOrFail(worker, "globe level tile processing");
        }
        progressProducer.finish();
        joinOrFail(progressThread, "globe level tile progress");
    }

    private static void preprocessFrame(Frame frame) {
        if (frame == null) {
            return;
        }
        for (TileInstance tile : frame.getTiles()) {
            if (tile == null) {
                continue;
            }
            tile.setGlobeLevelTileSet(DETECTOR.buildGlobeLevelTileSet(tile));
        }
        GlobeLevelTileSetNeighborDetector.populateNeighbors(frame);
        List<TileInstance> selectableTiles = buildSelectableTiles(frame);
        populateSelectableNeighbors(frame, selectableTiles);
        frame.setSelectableTilesOverride(selectableTiles);
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
            if (tile.getGlobeLevelTileSet() == null) {
                continue;
            }
            for (TileInstance syntheticTile : splitGlobeLevelTileSetTile(tile)) {
                if (syntheticTile != null && syntheticTile.isFullResolutionWithRespectToTexture()) {
                    selectableTiles.add(syntheticTile);
                }
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

    private static List<TileInstance> splitGlobeLevelTileSetTile(TileInstance sourceTile) {
        List<TileInstance.TriangleStripGeometry> geometries = sourceTile == null ? List.of() : sourceTile.getTriangleStripGeometries();
        if (geometries.isEmpty()) {
            return List.of();
        }
        List<TileInstance> out = new ArrayList<>(geometries.size());
        for (int i = 0; i < geometries.size(); i++) {
            TileInstance.TriangleStripGeometry geometry = geometries.get(i);
            TileInstance syntheticTile = toSyntheticTile(sourceTile, geometry, i);
            if (syntheticTile != null) {
                out.add(syntheticTile);
            }
        }
        return out;
    }

    private static TileInstance toSyntheticTile(
        TileInstance sourceTile,
        TileInstance.TriangleStripGeometry geometry,
        int geometryIndex
    ) {
        if (sourceTile == null || geometry == null || geometry.vertices() == null || geometry.vertices().isEmpty()) {
            return null;
        }
        List<Vector3D> strip = new ArrayList<>(geometry.vertices().size());
        List<Vector3D> uv = new ArrayList<>(geometry.vertices().size());
        Vector3D min = null;
        Vector3D max = null;
        for (TileInstance.TriangleStripVertex vertex : geometry.vertices()) {
            if (vertex == null) {
                continue;
            }
            Vector3D point = new Vector3D(vertex.x(), vertex.y(), vertex.z());
            Vector3D texCoord = new Vector3D(vertex.u(), vertex.v(), 0.0);
            strip.add(point);
            uv.add(texCoord);
            min = min == null
                ? point
                : new Vector3D(
                    Math.min(min.x(), point.x()),
                    Math.min(min.y(), point.y()),
                    Math.min(min.z(), point.z())
                );
            max = max == null
                ? point
                : new Vector3D(
                    Math.max(max.x(), point.x()),
                    Math.max(max.y(), point.y()),
                    Math.max(max.z(), point.z())
                );
        }
        if (strip.size() < 3 || uv.size() != strip.size() || min == null || max == null) {
            return null;
        }
        TileInstance syntheticTile = new TileInstance(
            syntheticContentId(sourceTile.getContentId(), geometryIndex),
            sourceTile.getTextureFile(),
            null,
            null,
            null,
            null,
            min,
            max,
            strip,
            List.of(strip),
            List.of(uv),
            "GL_TRIANGLE_STRIP",
            sourceTile.getParserCall(),
            sourceTile.getGlCall(),
            geometry.vertexCount(),
            geometry.vertexCount(),
            false,
            TileInstance.SYNTHETIC_GLOBE_LEVEL_TILE_SKIP_REASON,
            sourceTile.getProjectionMatrix(),
            sourceTile.getModelViewMatrix()
        );
        syntheticTile.setGlobeLevelTileSet(sourceTile.getGlobeLevelTileSet());
        return syntheticTile;
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

    private static String syntheticContentId(String originalContentId, int geometryIndex) {
        if (originalContentId == null || originalContentId.isBlank()) {
            return "gltile_" + geometryIndex;
        }
        int separator = originalContentId.lastIndexOf('_');
        if (separator < 0 || separator >= originalContentId.length() - 1) {
            return originalContentId + "_gltile_" + geometryIndex;
        }
        String prefix = originalContentId.substring(0, separator);
        String suffix = originalContentId.substring(separator + 1);
        try {
            Integer.parseInt(suffix);
            return prefix + "_gltile_" + geometryIndex + "_" + suffix;
        }
        catch (NumberFormatException ignored) {
            return originalContentId + "_gltile_" + geometryIndex;
        }
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
        ParallelProgressMonitorProducer progressProducer
    ) {
        while (true) {
            int frameIndex = takeFrameTask(frameQueue);
            if (frameIndex == POISON_FRAME_INDEX) {
                return;
            }
            if (frameIndex < 0 || frameIndex >= frames.size()) {
                continue;
            }
            preprocessFrame(frames.get(frameIndex));
            progressProducer.update(0, 1, 1);
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
}
