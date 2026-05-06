package frametexturenormalizer.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import frametexturenormalizer.config.Configuration;

public final class OrphanWriter {
    private static final ObjectMapper JSON = new ObjectMapper();

    private OrphanWriter() {
    }

    public static void writeOrphansJson(Set<String> orphanScopedIds) {
        Path outputRoot = Path.of(Configuration.INPUT_PATH);
        Path orphansJson = outputRoot.resolve("orphans.json");
        try {
            Files.createDirectories(outputRoot);
            List<String> sorted = new ArrayList<>(orphanScopedIds == null ? Set.of() : orphanScopedIds);
            Collections.sort(sorted);
            JSON.writerWithDefaultPrettyPrinter().writeValue(orphansJson.toFile(), sorted);
        }
        catch (IOException ex) {
            System.out.println("Unable to write " + orphansJson + ": " + ex.getMessage());
        }
    }
}
