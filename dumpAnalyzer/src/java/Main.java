package dumpanalyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

public class Main {
    private static final Path OUTPUT_ROOT = Paths.get("/tmp/output");

    public static void main(String[] args) {
        scanOutputFolder(OUTPUT_ROOT);
    }

    private static void scanOutputFolder(Path outputRoot) {
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
                .forEach(Main::processFrameDirectory);
        } catch (IOException e) {
            System.err.println("Failed to scan output folder: " + outputRoot);
            e.printStackTrace(System.err);
        }
    }

    private static void processFrameDirectory(Path frameDirectory) {
        String frameDirName = frameDirectory.getFileName().toString();
        int frame;

        try {
            frame = Integer.parseInt(frameDirName);
        } catch (NumberFormatException ex) {
            return;
        }

        Path glFile = frameDirectory.resolve("gl.txt");
        if (Files.isRegularFile(glFile)) {
            processFrame(frame, glFile.toAbsolutePath().toString());
        }
    }

    private static void processFrame(int frame, String filename) {
        System.out.println("Processing frame " + frame + " from file " + filename);
    }
}
