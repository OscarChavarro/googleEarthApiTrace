package matrixmerger.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class WestCuttersJsonWriter {
    private static final ObjectMapper JSON = new ObjectMapper();

    public void writeToOutput(Path outputPath, Set<String> westCutterTileIds) {
        if (outputPath == null || westCutterTileIds == null || westCutterTileIds.isEmpty()) {
            return;
        }
        Path cachePath = outputPath.resolve("westCutters.json");
        List<String> sorted = new ArrayList<>();
        for (String id : new LinkedHashSet<>(westCutterTileIds)) {
            String normalized = WestCuttersJsonReader.normalizeScopedTileId(id);
            if (normalized != null) {
                sorted.add(normalized);
            }
        }
        Collections.sort(sorted);
        try {
            Files.createDirectories(outputPath);
            JSON.writerWithDefaultPrettyPrinter().writeValue(cachePath.toFile(), sorted);
        }
        catch (IOException ex) {
            System.out.println("Unable to write " + cachePath + ": " + ex.getMessage());
        }
    }
}
