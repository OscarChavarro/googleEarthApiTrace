package dumpanalyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

public class FrameScanner {
    @FunctionalInterface
    public interface FrameConsumer {
        void accept(int frame, String filename);
    }

    private final Path outputRoot;
    private final FrameConsumer frameConsumer;

    public FrameScanner(Path outputRoot, FrameConsumer frameConsumer) {
        this.outputRoot = outputRoot;
        this.frameConsumer = frameConsumer;
    }

    public void scan() {
        if (!Files.exists(outputRoot)) {
            System.out.println("Output folder does not exist: " + outputRoot);
            return;
        }

        if (!Files.isDirectory(outputRoot)) {
            System.out.println("Output path is not a directory: " + outputRoot);
            return;
        }

        try (Stream<Path> entries = Files.list(outputRoot)) {
            entries
                .filter(Files::isDirectory)
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .forEach(this::processFrameDirectory);
        } catch (IOException e) {
            FatalErrorHandler.fail(outputRoot, "Failed to scan output folder: " + e.getMessage());
        }
    }

    private void processFrameDirectory(Path frameDirectory) {
        String frameDirName = frameDirectory.getFileName().toString();
        int frame;

        try {
            frame = Integer.parseInt(frameDirName);
        } catch (NumberFormatException ex) {
            return;
        }

        Path glFile = frameDirectory.resolve("gl.txt");
        if (Files.isRegularFile(glFile)) {
            frameConsumer.accept(frame, glFile.toAbsolutePath().toString());
        }
    }
}
