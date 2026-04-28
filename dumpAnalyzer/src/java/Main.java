package dumpanalyzer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

public class Main {
    private static final Path OUTPUT_ROOT = Paths.get("/tmp/output");
    private static final FrameTask POISON_TASK = new FrameTask(-1, "");
    private static final String LOG_POISON = "__LOG_POISON__";

    public static void main(String[] args) {
        int workerCount = Runtime.getRuntime().availableProcessors();

        FunctionCounter counter = new FunctionCounter();
        GlTraceProcessor processor = new GlTraceProcessor(counter);
        FrameScanner scanner = new FrameScanner(OUTPUT_ROOT);
        List<FrameTask> frameTasks = scanner.scanFrames();
        Set<Frame> frames = ConcurrentHashMap.newKeySet();

        BlockingQueue<FrameTask> frameQueue = new LinkedBlockingQueue<>();
        BlockingQueue<String> logQueue = new LinkedBlockingQueue<>();

        Thread loggerThread = createLoggerThread(logQueue);
        loggerThread.start();

        for (FrameTask task : frameTasks) {
            putFrameTask(frameQueue, task);
        }

        for (int i = 0; i < workerCount; i++) {
            putFrameTask(frameQueue, POISON_TASK);
        }

        Thread[] workers = new Thread[workerCount];
        for (int i = 0; i < workerCount; i++) {
            int workerId = i;
            workers[i] = createWorker(frameQueue, logQueue, processor, frames, workerId);
            workers[i].start();
        }

        for (Thread worker : workers) {
            joinOrFail(worker, "worker");
        }

        putLogMessage(logQueue, LOG_POISON);
        joinOrFail(loggerThread, "logger");

        counter.printSorted();
        writeFrames(frames);
    }

    private static Thread createWorker(
        BlockingQueue<FrameTask> frameQueue,
        BlockingQueue<String> logQueue,
        GlTraceProcessor processor,
        Set<Frame> frames,
        int workerId
    ) {
        return new Thread(() -> {
            while (true) {
                FrameTask task = takeFrameTask(frameQueue);
                if (task.frame() < 0) {
                    return;
                }
                Frame frame = processor.processFrame(task.frame(), task.filename(), logQueue);
                frames.add(frame);
            }
        }, "frame-worker-" + workerId);
    }

    private static void writeFrames(Set<Frame> frameSet) {
        for (Frame frame : frameSet) {
            Path frameDir = OUTPUT_ROOT.resolve(String.format("%05d", frame.getId()));
            Path frameFile = frameDir.resolve("frame.txt");
            try {
                Files.writeString(frameFile, frame.toString() + System.lineSeparator(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                FatalErrorHandler.fail(frameFile, "Cannot write frame file: " + e.getMessage());
            }
        }
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
}
