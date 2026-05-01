package dumpanalyzer.io;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import dumpanalyzer.io.parser.FunctionCounter;
import dumpanalyzer.io.parser.TraceProcessor;
import dumpanalyzer.logger.ConcurrentMessages;
import dumpanalyzer.logger.FatalErrorHandler;
import dumpanalyzer.model.DumpAnalyzerModel;
import dumpanalyzer.model.Frame;

public final class TracedModelReader {
    private static final FrameTask POISON_TASK = new FrameTask(-1, "");

    private final Path outputRoot;
    private final int maxFrame;

    public TracedModelReader(Path outputRoot, int maxFrame) {
        this.outputRoot = outputRoot;
        this.maxFrame = maxFrame;
    }

    public void importInto(DumpAnalyzerModel model, int workerCount) {
        TexturePathScanner.scanRecursive(outputRoot, model);

        FunctionCounter counter = new FunctionCounter();
        TraceProcessor processor = new TraceProcessor(counter);
        FrameScanner scanner = new FrameScanner(outputRoot);
        List<FrameTask> frameTasks = scanner.scanFrames().stream()
            .map(TracedModelReader::toFrameTask)
            .filter(task -> task.frame() >= 0)
            .toList();

        BlockingQueue<FrameTask> frameQueue = new LinkedBlockingQueue<>();
        BlockingQueue<String> logQueue = new LinkedBlockingQueue<>();

        Thread loggerThread = ConcurrentMessages.createLoggerThread(outputRoot, logQueue);
        loggerThread.start();

        for (FrameTask task : frameTasks.stream().limit(maxFrame).toList()) {
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

        model.selectFirstFrameWithTiles();

        ConcurrentMessages.putLogMessage(outputRoot, logQueue, ConcurrentMessages.LOG_POISON);
        joinOrFail(loggerThread, "logger");

        counter.printSorted();
    }

    private Thread createWorker(
        BlockingQueue<FrameTask> frameQueue,
        BlockingQueue<String> logQueue,
        TraceProcessor processor,
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

    private FrameTask takeFrameTask(BlockingQueue<FrameTask> frameQueue) {
        try {
            return frameQueue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FatalErrorHandler.fail(outputRoot, "Interrupted while consuming frame queue");
            return POISON_TASK;
        }
    }

    private void putFrameTask(BlockingQueue<FrameTask> frameQueue, FrameTask task) {
        try {
            frameQueue.put(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FatalErrorHandler.fail(outputRoot, "Interrupted while producing frame queue");
        }
    }

    private void joinOrFail(Thread thread, String threadRole) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FatalErrorHandler.fail(outputRoot, "Interrupted while waiting for " + threadRole + " thread");
        }
    }

    private static FrameTask toFrameTask(Path glFile) {
        if (glFile == null || glFile.getParent() == null || glFile.getParent().getFileName() == null) {
            return POISON_TASK;
        }
        String frameDirName = glFile.getParent().getFileName().toString();
        try {
            int frame = Integer.parseInt(frameDirName);
            return new FrameTask(frame, glFile.toString());
        } catch (NumberFormatException ex) {
            return POISON_TASK;
        }
    }

    private record FrameTask(int frame, String filename) {
    }
}
