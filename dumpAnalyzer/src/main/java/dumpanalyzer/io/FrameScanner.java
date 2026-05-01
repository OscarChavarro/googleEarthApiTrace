package dumpanalyzer.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import dumpanalyzer.logger.FatalErrorHandler;

public class FrameScanner {
    private final Path outputRoot;

    public FrameScanner(Path outputRoot) {
        this.outputRoot = outputRoot;
    }

    public List<Path> scanFrames() {
        if (!Files.exists(outputRoot)) {
            System.out.println("Output folder does not exist: " + outputRoot);
            return List.of();
        }

        if (!Files.isDirectory(outputRoot)) {
            System.out.println("Output path is not a directory: " + outputRoot);
            return List.of();
        }

        try (Stream<Path> entries = Files.list(outputRoot)) {
            return entries
                .filter(Files::isDirectory)
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .map(frameDirectory -> frameDirectory.resolve("gl.txt"))
                .filter(Files::isRegularFile)
                .map(Path::toAbsolutePath)
                .toList();
        } catch (IOException e) {
            FatalErrorHandler.fail(outputRoot, "Failed to scan output folder: " + e.getMessage());
        }

        return List.of();
    }
}
