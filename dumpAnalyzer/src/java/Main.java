package dumpanalyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import vsdk.toolkit.common.linealAlgebra.Vector3D;

public class Main {
    private static final Path OUTPUT_ROOT = Paths.get("/tmp/output");
    private static final int MAX_FRAME = 100000;
    private static final FrameTask POISON_TASK = new FrameTask(-1, "");
    private static final String LOG_POISON = "__LOG_POISON__";
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final DefaultPrettyPrinter JSON_PRETTY_PRINTER = createPrettyPrinter();

    public static void main(String[] args) {
        JSON_MAPPER.addMixIn(Vector3D.class, Vector3DMixin.class);
        int workerCount = Runtime.getRuntime().availableProcessors();

        DumpAnalyzerModel model = new DumpAnalyzerModel();
        TexturePathScanner.scanRecursive(OUTPUT_ROOT, model);

        FunctionCounter counter = new FunctionCounter();
        GlTraceProcessor processor = new GlTraceProcessor(counter);
        FrameScanner scanner = new FrameScanner(OUTPUT_ROOT);
        List<FrameTask> frameTasks = scanner.scanFrames();

        Thread rendererThread = createRendererThread(model);
        rendererThread.start();

        BlockingQueue<FrameTask> frameQueue = new LinkedBlockingQueue<>();
        BlockingQueue<String> logQueue = new LinkedBlockingQueue<>();

        Thread loggerThread = createLoggerThread(logQueue);
        loggerThread.start();

        for (FrameTask task : frameTasks.stream().limit(MAX_FRAME).toList()) {
            putFrameTask(frameQueue, task);
        }

        for (int i = 0; i < workerCount; i++) {
            putFrameTask(frameQueue, POISON_TASK);
        }

        Thread[] workers = new Thread[workerCount];
        for (int i = 0; i < workerCount; i++) {
            int workerId = i;
            workers[i] = createWorker(frameQueue, logQueue, processor, model, workerId);
            workers[i].start();
        }

        for (Thread worker : workers) {
            joinOrFail(worker, "worker");
        }

        putLogMessage(logQueue, LOG_POISON);
        joinOrFail(loggerThread, "logger");

        counter.printSorted();
        writeFrames(model.snapshotFrames());
    }

    private static Thread createRendererThread(DumpAnalyzerModel model) {
        return new Thread(() -> {
            Jogl4DumpAnalyzerRenderer renderer = new Jogl4DumpAnalyzerRenderer(model, Main::shutdownNow);
            renderer.start();
        }, "jogl4-renderer");
    }

    private static Thread createWorker(
        BlockingQueue<FrameTask> frameQueue,
        BlockingQueue<String> logQueue,
        GlTraceProcessor processor,
        DumpAnalyzerModel model,
        int workerId
    ) {
        return new Thread(() -> {
            while (true) {
                FrameTask task = takeFrameTask(frameQueue);
                if (task.frame() < 0) {
                    return;
                }
                Frame frame = processor.processFrame(task.frame(), task.filename(), logQueue);
                model.addFrame(frame);
            }
        }, "frame-worker-" + workerId);
    }

    private static void writeFrames(List<Frame> frames) {
        for (Frame frame : frames) {
            Path frameDir = OUTPUT_ROOT.resolve(String.format("%05d", frame.getId()));
            Path frameFile = frameDir.resolve("frame.json");
            try {
                JSON_MAPPER.writer(JSON_PRETTY_PRINTER).writeValue(frameFile.toFile(), frame);
            } catch (IOException e) {
                FatalErrorHandler.fail(frameFile, "Cannot write frame file: " + e.getMessage());
            }
        }
    }

    private static DefaultPrettyPrinter createPrettyPrinter() {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        DefaultIndenter indenter = new DefaultIndenter("  ", System.lineSeparator());
        printer = printer.withArrayIndenter(indenter);
        printer = printer.withObjectIndenter(indenter);
        return printer;
    }

    private static Thread createLoggerThread(BlockingQueue<String> logQueue) {
        return new Thread(() -> {
            while (true) {
                String message = takeLogMessage(logQueue);
                if (LOG_POISON.equals(message)) {
                    return;
                }
                System.out.println(message);
            }
        }, "log-consumer");
    }

    private static FrameTask takeFrameTask(BlockingQueue<FrameTask> frameQueue) {
        try {
            return frameQueue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FatalErrorHandler.fail(OUTPUT_ROOT, "Interrupted while consuming frame queue");
            return POISON_TASK;
        }
    }

    private static void putFrameTask(BlockingQueue<FrameTask> frameQueue, FrameTask task) {
        try {
            frameQueue.put(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FatalErrorHandler.fail(OUTPUT_ROOT, "Interrupted while producing frame queue");
        }
    }

    private static String takeLogMessage(BlockingQueue<String> logQueue) {
        try {
            return logQueue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FatalErrorHandler.fail(OUTPUT_ROOT, "Interrupted while consuming log queue");
            return LOG_POISON;
        }
    }

    private static void putLogMessage(BlockingQueue<String> logQueue, String message) {
        try {
            logQueue.put(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FatalErrorHandler.fail(OUTPUT_ROOT, "Interrupted while producing log queue");
        }
    }

    private static void joinOrFail(Thread thread, String threadRole) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FatalErrorHandler.fail(OUTPUT_ROOT, "Interrupted while waiting for " + threadRole + " thread");
        }
    }

    private static void shutdownNow() {
        System.exit(0);
    }
}
