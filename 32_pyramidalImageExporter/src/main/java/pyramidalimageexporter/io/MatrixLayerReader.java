package pyramidalimageexporter.io;

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
import pyramidalimageexporter.model.MatrixLayer;

public final class MatrixLayerReader {
    private static final ObjectMapper JSON = new ObjectMapper();

    public List<MatrixLayer> readAllFromInput(Path inputDirectory) {
        if (inputDirectory == null || !Files.isDirectory(inputDirectory) || !Files.isReadable(inputDirectory)) {
            return List.of();
        }
        List<MatrixLayer> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(inputDirectory)) {
            stream.filter(Files::isDirectory)
                .filter(path -> path.getFileName() != null && path.getFileName().toString().startsWith("matrix_"))
                .sorted(Comparator.comparingInt(MatrixLayerReader::layerIndexOf))
                .forEach(path -> readLayer(path).ifPresent(out::add));
        }
        catch (IOException ex) {
            System.out.println("Unable to scan " + inputDirectory + ": " + ex.getMessage());
        }
        return out;
    }

    private Optional<MatrixLayer> readLayer(Path layerDirectory) {
        Path matrixPath = layerDirectory.resolve("matrixLayer.json");
        if (!Files.isRegularFile(matrixPath) || !Files.isReadable(matrixPath)) {
            return Optional.empty();
        }
        try {
            JsonNode root = JSON.readTree(matrixPath.toFile());
            MatrixLayer layer = parseLayer(root, layerIndexOf(layerDirectory));
            if (layer == null || layer.getTiles() == null || layer.getTiles().isEmpty()) {
                return Optional.empty();
            }
            layer.setSourceFolderName(layerDirectory.getFileName().toString());
            return Optional.of(layer);
        }
        catch (IOException ex) {
            System.out.println("Unable to read " + matrixPath + ": " + ex.getMessage());
            return Optional.empty();
        }
    }

    private MatrixLayer parseLayer(JsonNode root, int fallbackIndex) throws IOException {
        if (root == null || root.isNull()) {
            return null;
        }
        if (root.has("matrices") && root.get("matrices").isArray() && !root.get("matrices").isEmpty()) {
            JsonNode matrixNode = root.get("matrices").get(0);
            MatrixLayer layer = JSON.treeToValue(matrixNode, MatrixLayer.class);
            if (layer != null && layer.getFrameId() <= 0) {
                layer.setFrameId(root.path("frameId").asInt(fallbackIndex));
            }
            return layer;
        }
        MatrixLayer layer = JSON.treeToValue(root, MatrixLayer.class);
        if (layer != null && layer.getFrameId() <= 0) {
            layer.setFrameId(fallbackIndex);
        }
        return layer;
    }

    private static int layerIndexOf(Path path) {
        if (path == null || path.getFileName() == null) {
            return Integer.MAX_VALUE;
        }
        String fileName = path.getFileName().toString();
        int separator = fileName.lastIndexOf('_');
        if (separator < 0 || separator >= fileName.length() - 1) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(fileName.substring(separator + 1));
        }
        catch (NumberFormatException ex) {
            return Integer.MAX_VALUE;
        }
    }
}
