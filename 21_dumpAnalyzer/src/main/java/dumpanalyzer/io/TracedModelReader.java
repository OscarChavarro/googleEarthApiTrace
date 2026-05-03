package dumpanalyzer.io;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

import dumpanalyzer.io.parser.FunctionCounter;
import dumpanalyzer.io.parser.TraceProcessor;
import dumpanalyzer.logger.ConcurrentMessages;
import dumpanalyzer.logger.FatalErrorHandler;
import dumpanalyzer.model.DumpAnalyzerModel;
import dumpanalyzer.model.Frame;
import vsdk.toolkit.gui.feedback.parallel.ParallelProgressMonitorConsumer;
import vsdk.toolkit.gui.feedback.parallel.ParallelProgressMonitorEvent;
import vsdk.toolkit.gui.feedback.parallel.ParallelProgressMonitorProducer;

public final class TracedModelReader {
    private static final String POISON_PATH = "__POISON__";

    private final Path outputRoot;
    private final int endFrameIdInclusive;

    public TracedModelReader(Path outputRoot, int endFrameIdInclusive) {
        this.outputRoot = outputRoot;
        this.endFrameIdInclusive = endFrameIdInclusive;
    }

    public void importInto(DumpAnalyzerModel model) {
        System.out.print("Scanning folders... ");
        System.out.flush();
        List<String> frameDirectories = scanDirectSubdirectories(outputRoot);
        System.out.println("OK");

        FunctionCounter counter = new FunctionCounter();
        int workerCount = Runtime.getRuntime().availableProcessors();
        TexturePathScanner.scanFrameDirectoriesParallel(frameDirectories, model, workerCount);

        List<String> glFilesToProcess = scanGlFilesFromFrameDirectories(frameDirectories);
        if (endFrameIdInclusive > 0) {
            glFilesToProcess = glFilesToProcess.stream()
                .filter(path -> {
                    int frameId = parseFrameFromGlPath(path);
                    return frameId > 0 && frameId <= endFrameIdInclusive;
                })
                .toList();
        }
        long problemSize = glFilesToProcess.size();

        BlockingQueue<String> frameQueue = new LinkedBlockingQueue<>();
        BlockingQueue<String> logQueue = new LinkedBlockingQueue<>();
        ConcurrentLinkedQueue<ParallelProgressMonitorEvent> progressEvents =
            new ConcurrentLinkedQueue<>();
        ParallelProgressMonitorProducer progressProducer =
            new ParallelProgressMonitorProducer(progressEvents);
        ParallelProgressMonitorConsumer progressConsumer =
            new ParallelProgressMonitorConsumer(progressEvents);
        Thread progressThread = new Thread(progressConsumer, "frame-progress-monitor-consumer");

        Thread loggerThread = ConcurrentMessages.createLoggerThread(outputRoot, logQueue);
        loggerThread.start();
        System.out.println("\n[1/5] Processing frame traces:");
        progressProducer.init(problemSize);
        progressThread.start();

        for (String glFilePath : glFilesToProcess) {
            putFrameTask(frameQueue, glFilePath);
        }

        for (int i = 0; i < workerCount; i++) {
            putFrameTask(frameQueue, POISON_PATH);
        }

        Thread[] workers = new Thread[workerCount];
        for (int i = 0; i < workerCount; i++) {
            int workerId = i;
            TraceProcessor processor = new TraceProcessor(counter);
            workers[i] = createWorker(frameQueue, logQueue, processor, model, progressProducer, workerId);
            workers[i].start();
        }

        for (Thread worker : workers) {
            joinOrFail(worker, "worker");
        }
        progressProducer.finish();
        joinOrFail(progressThread, "progress");

        model.selectFirstFrameWithTiles();

        ConcurrentMessages.putLogMessage(outputRoot, logQueue, ConcurrentMessages.LOG_POISON);
        joinOrFail(loggerThread, "logger");

    }

    private Thread createWorker(
        BlockingQueue<String> frameQueue,
        BlockingQueue<String> logQueue,
        TraceProcessor processor,
        DumpAnalyzerModel model,
        ParallelProgressMonitorProducer progressProducer,
        int workerId
    ) {
        return new Thread(() -> {
            while (true) {
                String glFilePath = takeFrameTask(frameQueue);
                if (POISON_PATH.equals(glFilePath)) {
                    return;
                }
                int frameId = parseFrameFromGlPath(glFilePath);
                if (frameId < 0) {
                    continue;
                }
                Frame frame = processor.processFrame(frameId, glFilePath, logQueue);
                model.addFrame(frame);
                progressProducer.update(0, 1, 1);
            }
        }, "frame-worker-" + workerId);
    }

    private String takeFrameTask(BlockingQueue<String> frameQueue) {
        try {
            return frameQueue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FatalErrorHandler.fail(outputRoot, "Interrupted while consuming frame queue");
            return POISON_PATH;
        }
    }

    private void putFrameTask(BlockingQueue<String> frameQueue, String task) {
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

    private int parseFrameFromGlPath(String glFilePath) {
        Path glFile = Path.of(glFilePath);
        if (glFile.getParent() == null || glFile.getParent().getFileName() == null) {
            return -1;
        }
        String frameDirName = glFile.getParent().getFileName().toString();
        try {
            return Integer.parseInt(frameDirName);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static List<String> scanDirectSubdirectories(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var entries = Files.list(root)) {
            return entries
                .filter(Files::isDirectory)
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .map(path -> path.toAbsolutePath().toString())
                .toList();
        } catch (IOException e) {
            FatalErrorHandler.fail(root, "Failed to count frame directories: " + e.getMessage());
            return List.of();
        }
    }

    private static List<String> scanGlFilesFromFrameDirectories(List<String> frameDirectories) {
        return frameDirectories.stream()
            .map(Path::of)
            .map(frameDirectory -> frameDirectory.resolve("gl.txt"))
            .filter(Files::isRegularFile)
            .map(path -> path.toAbsolutePath().toString())
            .toList();
    }
}
