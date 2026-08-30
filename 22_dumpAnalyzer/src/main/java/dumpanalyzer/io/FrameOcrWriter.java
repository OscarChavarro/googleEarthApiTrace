package dumpanalyzer.io;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import dumpanalyzer.config.Configuration;
import dumpanalyzer.logger.FatalErrorHandler;
import dumpanalyzer.model.Frame;
import dumpanalyzer.model.replay.ReplayDraw;
import dumpanalyzer.model.replay.ReplayTexture;
import dumpanalyzer.ocr.LocalOcrEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import vsdk.toolkit.gui.feedback.parallel.ParallelProgressMonitorConsumer;
import vsdk.toolkit.gui.feedback.parallel.ParallelProgressMonitorEvent;
import vsdk.toolkit.gui.feedback.parallel.ParallelProgressMonitorProducer;

public final class FrameOcrWriter {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final DefaultPrettyPrinter JSON_PRETTY_PRINTER = createPrettyPrinter();
    private static final int POISON_FRAME_INDEX = -1;

    private FrameOcrWriter() {
    }

    public static void writeFramesParallelWithProgress(Path outputRoot, List<Frame> frames) {
        if (!Configuration.LOCAL_OCR_ENABLED) {
            return;
        }
        System.out.println("\n[2/6] Running OCR on screen-space HUD textures:");
        if (frames == null || frames.isEmpty()) {
            return;
        }
        if (!LocalOcrEngine.isAvailable()) {
            System.out.println("Local OCR library unavailable at " + Configuration.LOCAL_OCR_LIBRARY + "; skipping OCR.");
            return;
        }

        int workerCount = Configuration.LOCAL_OCR_THREADS;
        System.out.println("Using " + workerCount + " OCR worker(s).");
        BlockingQueue<Integer> frameQueue = new LinkedBlockingQueue<>();
        ConcurrentLinkedQueue<ParallelProgressMonitorEvent> progressEvents = new ConcurrentLinkedQueue<>();
        ParallelProgressMonitorProducer progressProducer = new ParallelProgressMonitorProducer(progressEvents);
        ParallelProgressMonitorConsumer progressConsumer = new ParallelProgressMonitorConsumer(progressEvents);
        Thread progressThread = new Thread(progressConsumer, "frame-ocr-progress-consumer");

        progressProducer.init(frames.size());
        progressThread.start();

        for (int i = 0; i < frames.size(); i++) {
            putFrameTask(frameQueue, i, outputRoot);
        }
        for (int i = 0; i < workerCount; i++) {
            putFrameTask(frameQueue, POISON_FRAME_INDEX, outputRoot);
        }

        Thread[] workers = new Thread[workerCount];
        for (int i = 0; i < workerCount; i++) {
            int workerId = i;
            workers[i] = new Thread(
                () -> consumeAndWriteOcr(outputRoot, frameQueue, frames, progressProducer),
                "frame-ocr-worker-" + workerId
            );
            workers[i].start();
        }

        for (Thread worker : workers) {
            joinOrFail(worker, "OCR", outputRoot);
        }
        progressProducer.finish();
        joinOrFail(progressThread, "OCR progress", outputRoot);
    }

    public static void writeFrame(Path outputRoot, Frame frame) {
        if (!Configuration.LOCAL_OCR_ENABLED) {
            return;
        }
        if (frame == null) {
            return;
        }
        Set<String> texts = new LinkedHashSet<>();
        for (Path texturePath : hudPngTexturePaths(frame)) {
            String text = LocalOcrEngine.recognizePng(texturePath);
            if (!text.isBlank()) {
                texts.add(text);
            }
        }
        if (texts.isEmpty()) {
            return;
        }
        Path frameDir = outputRoot.resolve(String.format("%05d", frame.getId()));
        Path ocrFile = frameDir.resolve("ocr.json");
        try {
            Files.createDirectories(frameDir);
            JSON_MAPPER.writer(JSON_PRETTY_PRINTER).writeValue(ocrFile.toFile(), new OcrTexts(List.copyOf(texts)));
        }
        catch (IOException e) {
            FatalErrorHandler.fail(ocrFile, "Cannot write OCR file: " + e.getMessage());
        }
    }

    private static List<Path> hudPngTexturePaths(Frame frame) {
        List<Path> paths = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ReplayDraw draw : frame.getReplayDraws()) {
            if (draw == null || !"SCREEN_SPACE".equals(draw.pass())) {
                continue;
            }
            ReplayTexture texture = draw.texture();
            if (texture == null || texture.imagePath() == null || texture.imagePath().isBlank()) {
                continue;
            }
            Path path = Path.of(texture.imagePath());
            if (!path.getFileName().toString().toLowerCase().endsWith(".png") || !Files.isRegularFile(path)) {
                continue;
            }
            String absolute = path.toAbsolutePath().toString();
            if (seen.add(absolute)) {
                paths.add(path);
            }
        }
        return paths;
    }

    private static void consumeAndWriteOcr(
        Path outputRoot,
        BlockingQueue<Integer> frameQueue,
        List<Frame> frames,
        ParallelProgressMonitorProducer progressProducer
    ) {
        while (true) {
            int frameIndex = takeFrameTask(frameQueue, outputRoot);
            if (frameIndex == POISON_FRAME_INDEX) {
                return;
            }
            if (frameIndex >= 0 && frameIndex < frames.size()) {
                writeFrame(outputRoot, frames.get(frameIndex));
            }
            progressProducer.update(0, 1, 1);
        }
    }

    private static void putFrameTask(BlockingQueue<Integer> frameQueue, int frameIndex, Path outputRoot) {
        try {
            frameQueue.put(frameIndex);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FatalErrorHandler.fail(outputRoot, "Interrupted while producing OCR queue");
        }
    }

    private static int takeFrameTask(BlockingQueue<Integer> frameQueue, Path outputRoot) {
        try {
            return frameQueue.take();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FatalErrorHandler.fail(outputRoot, "Interrupted while consuming OCR queue");
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

    private record OcrTexts(List<String> texts) {}
}
