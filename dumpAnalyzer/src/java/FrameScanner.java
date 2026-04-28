package dumpanalyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public class FrameScanner {
    private final Path outputRoot;

    public FrameScanner(Path outputRoot) {
        this.outputRoot = outputRoot;
    }

    public List<FrameTask> scanFrames() {
        if (!Files.exists(outputRoot)) {
            System.out.println("Output folder does not exist: " + outputRoot);
            return List.of();
        }

        if (!Files.isDirectory(outputRoot)) {
            System.out.println("Output path is not a directory: " + outputRoot);
            return List.of();
        }

        List<FrameTask> tasks = new ArrayList<>();
        try (Stream<Path> entries = Files.list(outputRoot)) {
            entries
                .filter(Files::isDirectory)
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .forEach(frameDirectory -> addFrameTask(frameDirectory, tasks));
        } catch (IOException e) {
            FatalErrorHandler.fail(outputRoot, "Failed to scan output folder: " + e.getMessage());
        }

        return tasks;
    }

    private static void addFrameTask(Path frameDirectory, List<FrameTask> tasks) {
        String frameDirName = frameDirectory.getFileName().toString();
        int frame;

        try {
            frame = Integer.parseInt(frameDirName);
        } catch (NumberFormatException ex) {
            return;
        }

        Path glFile = frameDirectory.resolve("gl.txt");
        if (Files.isRegularFile(glFile)) {
            tasks.add(new FrameTask(frame, glFile.toAbsolutePath().toString()));
        }
    }
}
