package dumpanalyzer.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;
import dumpanalyzer.logger.FatalErrorHandler;
import dumpanalyzer.model.state.DumpAnalyzerState;

public final class FrameTexturePathResolver {
    private static final String POISON_PATH = "__POISON__";
    private static final Pattern TEXTURE_NAME_PATTERN = Pattern.compile(".*_(\\d+)\\.(dds|png)$", Pattern.CASE_INSENSITIVE);

    private FrameTexturePathResolver() {
    }

    public static void scanFrameDirectoriesParallel(List<String> frameDirectories, DumpAnalyzerState model, int workerCount) {
        if (frameDirectories == null || frameDirectories.isEmpty()) {
            return;
        }

        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        for (String frameDirectory : frameDirectories) {
            putPath(queue, frameDirectory);
        }
        for (int i = 0; i < workerCount; i++) {
            putPath(queue, POISON_PATH);
        }

        Thread[] workers = new Thread[workerCount];
        for (int i = 0; i < workerCount; i++) {
            int workerId = i;
            workers[i] = new Thread(() -> consumeAndScan(queue, model), "texture-scan-worker-" + workerId);
            workers[i].start();
        }

        for (Thread worker : workers) {
            joinOrFail(worker);
        }
    }

    private static void consumeAndScan(BlockingQueue<String> queue, DumpAnalyzerState model) {
        while (true) {
            String frameDirectoryPath = takePath(queue);
            if (POISON_PATH.equals(frameDirectoryPath)) {
                return;
            }
            Path frameDirectory = Path.of(frameDirectoryPath);
            if (!Files.isDirectory(frameDirectory)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(frameDirectory)) {
                paths
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String n = path.getFileName().toString().toLowerCase();
                        return n.endsWith(".dds") || n.endsWith(".png");
                    })
                    .forEach(path -> register(path, model));
            } catch (IOException e) {
                FatalErrorHandler.fail(frameDirectory, "Failed to recursively scan texture files: " + e.getMessage());
            }
        }
    }

    private static void putPath(BlockingQueue<String> queue, String path) {
        try {
            queue.put(path);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FatalErrorHandler.fail(Path.of("."), "Interrupted while producing texture scan queue");
        }
    }

    private static String takePath(BlockingQueue<String> queue) {
        try {
            return queue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FatalErrorHandler.fail(Path.of("."), "Interrupted while consuming texture scan queue");
            return POISON_PATH;
        }
    }

    private static void joinOrFail(Thread worker) {
        try {
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FatalErrorHandler.fail(Path.of("."), "Interrupted while waiting texture scan workers");
        }
    }

    private static void register(Path path, DumpAnalyzerState model) {
        String name = path.getFileName().toString();
        Matcher matcher = TEXTURE_NAME_PATTERN.matcher(name);
        if (!matcher.matches()) {
            return;
        }
        Path parent = path.getParent();
        if (parent == null || parent.getFileName() == null) {
            return;
        }
        int frameId;
        try {
            frameId = Integer.parseInt(parent.getFileName().toString());
        } catch (NumberFormatException ex) {
            return;
        }
        int textureId = Integer.parseInt(matcher.group(1));
        model.registerTexturePath(frameId, textureId, path.toAbsolutePath().toString());
    }
}
