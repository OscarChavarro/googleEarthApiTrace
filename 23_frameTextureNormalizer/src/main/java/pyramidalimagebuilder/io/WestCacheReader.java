package pyramidalimagebuilder.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import pyramidalimagebuilder.config.Configuration;
import pyramidalimagebuilder.model.PyramidalImageModel;
import pyramidalimagebuilder.processing.TileCutter;

public final class WestCacheReader {
    private static final ObjectMapper JSON = new ObjectMapper();

    public void restore(PyramidalImageModel model) {
        if (model == null) {
            return;
        }
        Set<Integer> westIds = readWestCutterIds();
        if (westIds.isEmpty()) {
            return;
        }
        // Reuse the same cutting operation used by keyboard command 'c'
        TileCutter.cutWestFromTileIdsAcrossFrames(model.getFrames(), westIds);
        model.addWestCutterTileIds(westIds);
    }

    private Set<Integer> readWestCutterIds() {
        Path cachePath = Path.of(Configuration.INPUT_PATH).resolve("westCutters.json");
        if (!Files.isRegularFile(cachePath)) {
            return Set.of();
        }
        try {
            JsonNode root = JSON.readTree(cachePath.toFile());
            Set<Integer> ids = new LinkedHashSet<>();
            if (root != null && root.isArray()) {
                for (JsonNode n : root) {
                    if (n != null && n.canConvertToInt()) {
                        int id = n.asInt(-1);
                        if (id >= 0) {
                            ids.add(id);
                        }
                    }
                }
            }
            return ids;
        }
        catch (IOException ignored) {
            return Set.of();
        }
    }
}
