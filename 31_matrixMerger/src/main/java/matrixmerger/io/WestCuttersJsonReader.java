package matrixmerger.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

public final class WestCuttersJsonReader {
    private static final ObjectMapper JSON = new ObjectMapper();

    public Set<String> readFromOutput(Path outputPath) {
        if (outputPath == null) {
            return Set.of();
        }
        Path cachePath = outputPath.resolve("westCutters.json");
        if (!Files.isRegularFile(cachePath)) {
            return Set.of();
        }
        try {
            JsonNode root = JSON.readTree(cachePath.toFile());
            if (root == null || !root.isArray()) {
                return Set.of();
            }
            Set<String> out = new LinkedHashSet<>();
            for (JsonNode node : root) {
                if (node == null) {
                    continue;
                }
                if (node.isTextual()) {
                    String id = normalizeScopedTileId(node.asText());
                    if (id != null) {
                        out.add(id);
                    }
                    continue;
                }
                if (node.canConvertToInt()) {
                    int id = node.asInt(-1);
                    if (id >= 0) {
                        out.add(Integer.toString(id));
                    }
                }
            }
            return out;
        }
        catch (IOException ex) {
            System.out.println("Unable to read " + cachePath + ": " + ex.getMessage());
            return Set.of();
        }
    }

    public static String normalizeScopedTileId(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isBlank()) {
            return null;
        }
        int separator = value.indexOf('_');
        if (separator <= 0 || separator >= value.length() - 1) {
            return value;
        }
        try {
            int frameId = Integer.parseInt(value.substring(0, separator));
            int tileId = Integer.parseInt(value.substring(separator + 1));
            if (frameId < 0 || tileId < 0) {
                return null;
            }
            return String.format("%05d_%d", frameId, tileId);
        }
        catch (NumberFormatException ex) {
            return value;
        }
    }
}
