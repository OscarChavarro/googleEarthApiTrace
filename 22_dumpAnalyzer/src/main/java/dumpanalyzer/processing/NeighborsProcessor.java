package dumpanalyzer.processing;

import dumpanalyzer.config.Configuration;
import dumpanalyzer.logger.FatalErrorHandler;
import dumpanalyzer.model.state.DumpAnalyzerState;
import dumpanalyzer.model.Frame;
import dumpanalyzer.model.TileInstance;
import dumpanalyzer.processing.uncles.UncleDetector;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.gui.feedback.ProgressMonitor;
import vsdk.toolkit.gui.feedback.ProgressMonitorConsoleLongFormat;
import vsdk.toolkit.gui.feedback.parallel.ParallelProgressMonitorConsumer;
import vsdk.toolkit.gui.feedback.parallel.ParallelProgressMonitorEvent;
import vsdk.toolkit.gui.feedback.parallel.ParallelProgressMonitorProducer;

public final class NeighborsProcessor {
    private static final UncleDetector UNCLE_DETECTOR = new UncleDetector();
    private static final int DEBUG_FRAME_ID = 50;
    private static final int POISON_FRAME_INDEX = -1;

    private NeighborsProcessor() {
    }

    public static void preprocessNeighbors(
        DumpAnalyzerState model,
        List<Frame> frames,
        int viewportWidth,
        int viewportHeight
    ) {
        if (frames == null || frames.isEmpty()) {
            return;
        }
        int width = Math.max(1, viewportWidth);
        int height = Math.max(1, viewportHeight);
        runStageWithProgress(
            "\n[2/5] Assigning texture file paths to tiles:",
            frames.size(),
            i -> {
                Frame frame = frames.get(i);
                if (frame == null) {
                    return;
                }
                for (TileInstance tile : frame.getTiles()) {
                    if (tile == null) {
                        continue;
                    }
                    String texturePath = model.getTexturePath(frame.getId(), tile.getContentId());
                    tile.setTextureFile(texturePath);
                }
            }
        );
        runStage3WithProgress(model, frames, width, height);
    }

    private static void runStageWithProgress(String stageTitle, int totalWorkUnits, IndexedTask task) {
        int total = Math.max(1, totalWorkUnits);
        System.out.println(stageTitle);
        ProgressMonitor progressMonitor = new ProgressMonitorConsoleLongFormat();
        progressMonitor.begin();
        for (int i = 0; i < totalWorkUnits; i++) {
            task.run(i);
            progressMonitor.update(0, total, i + 1);
        }
        progressMonitor.end();
    }

    @FunctionalInterface
    private interface IndexedTask {
        void run(int index);
    }

    private static void runStage3WithProgress(
        DumpAnalyzerState model,
        List<Frame> frames,
        int width,
        int height
    ) {
        System.out.println("\n[3/5] Detecting neighbors per frame:");
        if (frames == null || frames.isEmpty()) {
            ProgressMonitor progressMonitor = new ProgressMonitorConsoleLongFormat();
            progressMonitor.begin();
            progressMonitor.end();
            return;
        }

        int workerCount = Configuration.FRAME_NEIGHBOR_THREADS;
        System.out.println("Using " + workerCount + " neighbor worker(s).");
        BlockingQueue<Integer> frameQueue = new LinkedBlockingQueue<>();
        ConcurrentLinkedQueue<ParallelProgressMonitorEvent> progressEvents = new ConcurrentLinkedQueue<>();
        ParallelProgressMonitorProducer progressProducer = new ParallelProgressMonitorProducer(progressEvents);
        ParallelProgressMonitorConsumer progressConsumer = new ParallelProgressMonitorConsumer(progressEvents);
        Thread progressThread = new Thread(progressConsumer, "neighbor-progress-consumer");

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
                () -> consumeAndProcessStage3Frames(model, frames, frameQueue, progressProducer, width, height),
                "neighbor-worker-" + workerId
            );
            workers[i].start();
        }

        for (Thread worker : workers) {
            joinOrFail(worker, "neighbor detection");
        }
        progressProducer.finish();
        joinOrFail(progressThread, "neighbor detection progress");
    }

    private static Matrix4x4d matrixFromColumnMajor(double[] m) {
        if (m == null || m.length != 16) {
            return null;
        }
        Matrix4x4d out = new Matrix4x4d();
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                out = out.withVal(row, col, m[col * 4 + row]);
            }
        }
        return out;
    }

    private static void consumeAndProcessStage3Frames(
        DumpAnalyzerState model,
        List<Frame> frames,
        BlockingQueue<Integer> frameQueue,
        ParallelProgressMonitorProducer progressProducer,
        int width,
        int height
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
            if (frame != null) {
                frame = VisualTilePositioner.reorderFrame(frame, width, height);
                frames.set(frameIndex, frame);
                model.replaceFrame(frame);
            }
            Matrix4x4d projection = matrixFromColumnMajor(frame == null ? null : frame.getProjectionMatrix());
            if (projection == null) {
                projection = Matrix4x4d.identityMatrix();
            }
            double[] frameModelView = frame == null ? null : frame.getModelViewMatrix();
            try {
                TriangleStripNeighborDetector.populateNeighbors(frame, projection, width, height, frameModelView, true);
                populateUncles(frame);
                debugFrame(frame);
            }
            finally {
                clearTriangleStripProcessingCaches(frame);
            }
            progressProducer.update(0, 1, 1);
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

    private static void putFrameTask(BlockingQueue<Integer> frameQueue, int frameIndex) {
        try {
            frameQueue.put(frameIndex);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FatalErrorHandler.fail(Configuration.OUTPUT_ROOT, "Interrupted while producing neighbor detection queue");
        }
    }

    private static int takeFrameTask(BlockingQueue<Integer> frameQueue) {
        try {
            return frameQueue.take();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FatalErrorHandler.fail(Configuration.OUTPUT_ROOT, "Interrupted while consuming neighbor detection queue");
            return POISON_FRAME_INDEX;
        }
    }

    private static void joinOrFail(Thread thread, String threadRole) {
        try {
            thread.join();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FatalErrorHandler.fail(Configuration.OUTPUT_ROOT, "Interrupted while waiting for " + threadRole + " thread");
        }
    }

    private static void populateUncles(Frame frame) {
        if (frame == null) {
            return;
        }
        Object preparedCandidates = UNCLE_DETECTOR.prepareCandidates(frame);
        for (TileInstance tile : frame.getTiles()) {
            if (tile == null) {
                continue;
            }
            if (hasAnyMissingCardinalNeighbor(tile)) {
                tile.setUncles(UNCLE_DETECTOR.detect(frame, tile, preparedCandidates));
            }
            else {
                tile.setUncles(List.of());
            }
        }
    }

    private static boolean hasAnyMissingCardinalNeighbor(TileInstance tile) {
        return tile.getSouthNeighbor() == null
            || tile.getNorthNeighbor() == null
            || tile.getEastNeighbor() == null
            || tile.getWestNeighbor() == null;
    }

    private static void debugFrame(Frame frame) {
        if (frame == null || frame.getId() != DEBUG_FRAME_ID) {
            return;
        }
        for (TileInstance tile : frame.getTiles()) {
            if (tile == null || !hasAnyMissingCardinalNeighbor(tile)) {
                continue;
            }
        }
    }
}
