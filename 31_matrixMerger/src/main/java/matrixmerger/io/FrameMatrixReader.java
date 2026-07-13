package matrixmerger.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import matrixmerger.model.contract.FrameMatrixSet;
import matrixmerger.model.contract.FrameTileMatrix;

public final class FrameMatrixReader {
    private static final ObjectMapper JSON = new ObjectMapper();

    public List<FrameMatrixSet> readAllFromOutput(Path outputPath) {
        if (outputPath == null || !Files.isDirectory(outputPath)) {
            return List.of();
        }

        List<FrameMatrixSet> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(outputPath)) {
            stream.filter(Files::isDirectory)
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .forEach(frameDir -> readFrameMatrices(frameDir).ifPresent(out::add));
        }
        catch (IOException ex) {
            System.out.println("Unable to scan " + outputPath + ": " + ex.getMessage());
        }
        return out;
    }

    private Optional<FrameMatrixSet> readFrameMatrices(Path frameDir) {
        Path matrixPath = frameDir.resolve("matrix.json");
        if (!Files.isRegularFile(matrixPath)) {
            matrixPath = frameDir.resolve("matrix.txt");
        }
        if (!Files.isRegularFile(matrixPath)) {
            return Optional.empty();
        }
        try {
            JsonNode root = JSON.readTree(matrixPath.toFile());
            int fallbackFrameId = parseFrameId(frameDir);
            FrameMatrixSet frameMatrices = parseFrameMatrices(root, fallbackFrameId);
            return frameMatrices == null || frameMatrices.getMatrices().isEmpty()
                ? Optional.empty()
                : Optional.of(frameMatrices);
        }
        catch (IOException ex) {
            System.out.println("Unable to read " + matrixPath + ": " + ex.getMessage());
            return Optional.empty();
        }
    }

    private FrameMatrixSet parseFrameMatrices(JsonNode root, int fallbackFrameId) throws IOException {
        if (root == null || root.isNull()) {
            return null;
        }
        if (root.has("matrices") && root.get("matrices").isArray()) {
            FrameMatrixSet frameMatrices = new FrameMatrixSet();
            frameMatrices.setFrameId(root.path("frameId").asInt(fallbackFrameId));
            for (JsonNode matrixNode : root.path("matrices")) {
                FrameTileMatrix matrix = JSON.treeToValue(matrixNode, FrameTileMatrix.class);
                if (matrix != null && matrix.getTiles() != null && !matrix.getTiles().isEmpty()) {
                    matrix.setFrameId(frameMatrices.getFrameId());
                    frameMatrices.setMatrices(List.of(matrix));
                    return frameMatrices;
                }
            }
            return null;
        }

        FrameTileMatrix singleMatrix = JSON.treeToValue(root, FrameTileMatrix.class);
        if (singleMatrix == null || singleMatrix.getTiles() == null || singleMatrix.getTiles().isEmpty()) {
            return null;
        }
        FrameMatrixSet frameMatrices = new FrameMatrixSet();
        int frameId = singleMatrix.getFrameId() > 0 ? singleMatrix.getFrameId() : fallbackFrameId;
        singleMatrix.setFrameId(frameId);
        frameMatrices.setFrameId(frameId);
        frameMatrices.setMatrices(List.of(singleMatrix));
        return frameMatrices;
    }

    private static int parseFrameId(Path frameDir) {
        if (frameDir == null || frameDir.getFileName() == null) {
            return -1;
        }
        try {
            return Integer.parseInt(frameDir.getFileName().toString());
        }
        catch (NumberFormatException ex) {
            return -1;
        }
    }
}
