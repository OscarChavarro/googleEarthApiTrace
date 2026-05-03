package matrixmerger.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class MatrixReader {
    private static final ObjectMapper JSON = new ObjectMapper();

    public List<TileMatrix> readAllFromOutput(Path outputPath) {
        if (outputPath == null || !Files.isDirectory(outputPath)) {
            return List.of();
        }

        List<TileMatrix> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(outputPath)) {
            stream.filter(Files::isDirectory)
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .forEach(frameDir -> readFromFrameDir(frameDir).ifPresent(out::add));
        }
        catch (IOException ex) {
            System.out.println("Unable to scan " + outputPath + ": " + ex.getMessage());
        }
        return out;
    }

    private java.util.Optional<TileMatrix> readFromFrameDir(Path frameDir) {
        Path matrixPath = frameDir.resolve("matrix.txt");
        if (!Files.isRegularFile(matrixPath)) {
            matrixPath = frameDir.resolve("matrix.json");
        }
        if (!Files.isRegularFile(matrixPath)) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(JSON.readValue(matrixPath.toFile(), TileMatrix.class));
        }
        catch (IOException ex) {
            System.out.println("Unable to read " + matrixPath + ": " + ex.getMessage());
            return java.util.Optional.empty();
        }
    }
}
