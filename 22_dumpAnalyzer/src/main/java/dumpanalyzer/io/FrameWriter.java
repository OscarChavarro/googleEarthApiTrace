package dumpanalyzer.io;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import dumpanalyzer.config.Configuration;
import dumpanalyzer.logger.FatalErrorHandler;
import dumpanalyzer.model.Frame;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import vsdk.toolkit.gui.feedback.ProgressMonitor;
import vsdk.toolkit.gui.feedback.ProgressMonitorConsoleLongFormat;
import vsdk.toolkit.gui.feedback.parallel.ParallelProgressMonitorConsumer;
import vsdk.toolkit.gui.feedback.parallel.ParallelProgressMonitorEvent;
import vsdk.toolkit.gui.feedback.parallel.ParallelProgressMonitorProducer;

public final class FrameWriter {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final DefaultPrettyPrinter JSON_PRETTY_PRINTER = createPrettyPrinter();
    private static final int POISON_FRAME_INDEX = -1;

    private FrameWriter() {
    }

    public static void writeFrames(Path outputRoot, List<Frame> frames) {
        for (Frame frame : frames) {
            writeFrame(outputRoot, frame);
        }
    }

    public static void writeFrame(Path outputRoot, Frame frame) {
        Path frameDir = outputRoot.resolve(String.format("%05d", frame.getId()));
        Path frameFile = frameDir.resolve("frame.json");
        try {
            JSON_MAPPER.writer(JSON_PRETTY_PRINTER).writeValue(frameFile.toFile(), frame);
        } catch (IOException e) {
            FatalErrorHandler.fail(frameFile, "Cannot write frame file: " + e.getMessage());
        }
    }

    public static void writeFramesParallelWithProgress(Path outputRoot, List<Frame> frames) {
        System.out.println("\n[4/5] Writing processed frames to disk:");
        if (frames == null || frames.isEmpty()) {
            ProgressMonitor progressMonitor = new ProgressMonitorConsoleLongFormat();
            progressMonitor.begin();
            progressMonitor.end();
            return;
        }

        int availableCores = Math.max(1, Runtime.getRuntime().availableProcessors());
        int workerCount = Math.max(1, (int)Math.ceil(availableCores * Configuration.FRAME_WRITER_THREADS_CORES_RATIO));
        BlockingQueue<Integer> frameQueue = new LinkedBlockingQueue<>();
        ConcurrentLinkedQueue<ParallelProgressMonitorEvent> progressEvents = new ConcurrentLinkedQueue<>();
        ParallelProgressMonitorProducer progressProducer = new ParallelProgressMonitorProducer(progressEvents);
        ParallelProgressMonitorConsumer progressConsumer = new ParallelProgressMonitorConsumer(progressEvents);
        Thread progressThread = new Thread(progressConsumer, "frame-write-progress-consumer");

        progressProducer.init(frames.size());
        progressThread.start();

        for (int i = 0; i < frames.size(); i++) {
            putFrameWriteTask(frameQueue, i, outputRoot);
        }
        for (int i = 0; i < workerCount; i++) {
            putFrameWriteTask(frameQueue, POISON_FRAME_INDEX, outputRoot);
        }

        Thread[] workers = new Thread[workerCount];
        for (int i = 0; i < workerCount; i++) {
            int workerId = i;
            workers[i] = new Thread(
                () -> consumeAndWriteFrames(outputRoot, frameQueue, frames, progressProducer),
                "frame-write-worker-" + workerId
            );
            workers[i].start();
        }

        for (Thread worker : workers) {
            joinOrFail(worker, "frame write", outputRoot);
        }
        progressProducer.finish();
        joinOrFail(progressThread, "frame write progress", outputRoot);
    }

    private static void consumeAndWriteFrames(
        Path outputRoot,
        BlockingQueue<Integer> frameQueue,
        List<Frame> frames,
        ParallelProgressMonitorProducer progressProducer
    ) {
        while (true) {
            int frameIndex = takeFrameWriteTask(frameQueue, outputRoot);
            if (frameIndex == POISON_FRAME_INDEX) {
                return;
            }
            if (frameIndex < 0 || frameIndex >= frames.size()) {
                continue;
            }
            Frame frame = frames.get(frameIndex);
            if (frame != null) {
                writeFrame(outputRoot, frame);
            }
            progressProducer.update(0, 1, 1);
        }
    }

    private static void putFrameWriteTask(BlockingQueue<Integer> frameQueue, int frameIndex, Path outputRoot) {
        try {
            frameQueue.put(frameIndex);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FatalErrorHandler.fail(outputRoot, "Interrupted while producing frame write queue");
        }
    }

    private static int takeFrameWriteTask(BlockingQueue<Integer> frameQueue, Path outputRoot) {
        try {
            return frameQueue.take();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FatalErrorHandler.fail(outputRoot, "Interrupted while consuming frame write queue");
            return POISON_FRAME_INDEX;
        }
    }

    private static void joinOrFail(Thread thread, String threadRole, Path outputRoot) {
        try {
            thread.join();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FatalErrorHandler.fail(outputRoot, "Interrupted while waiting for " + threadRole + " thread");
        }
    }

    private static DefaultPrettyPrinter createPrettyPrinter() {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        DefaultIndenter indenter = new DefaultIndenter("  ", System.lineSeparator());
        printer = printer.withArrayIndenter(indenter);
        printer = printer.withObjectIndenter(indenter);
        return printer;
    }
}
