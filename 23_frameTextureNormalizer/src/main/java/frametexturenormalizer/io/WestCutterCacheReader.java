package frametexturenormalizer.io;

// Java classes
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Libraries classes
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// App classes
import frametexturenormalizer.config.Configuration;
import frametexturenormalizer.model.FrameTextureNormalizerModel;
import frametexturenormalizer.processing.neighborhood.TileCutter;
import frametexturenormalizer.util.ScopedTileIds;

public final class WestCutterCacheReader {
    private static final ObjectMapper JSON = new ObjectMapper();

    public void restore(FrameTextureNormalizerModel model) {
        if (model == null) {
            return;
        }
        WestCacheData westIds = readWestCutterIds();
        if (westIds.scopedIds().isEmpty() && westIds.legacyIds().isEmpty()) {
            return;
        }
        Set<String> scopedIds = new LinkedHashSet<>(westIds.scopedIds());
        if (!scopedIds.isEmpty()) {
            scopedIds = new LinkedHashSet<>(TileCutter.expandWestCutScopedIdsAcrossFrames(model.getFrames(), scopedIds));
        }
        if (!westIds.legacyIds().isEmpty()) {
            scopedIds.addAll(TileCutter.scopedIdsFromLegacyTileIdsAcrossFrames(model.getFrames(), westIds.legacyIds()));
        }
        model.addWestCutterTileIds(scopedIds);
    }

    private WestCacheData readWestCutterIds() {
        Path cachePath = Path.of(Configuration.INPUT_PATH).resolve("westCutters.json");
        if (!Files.isRegularFile(cachePath)) {
            return new WestCacheData(Set.of(), Set.of());
        }
        try {
            JsonNode root = JSON.readTree(cachePath.toFile());
            List<String> scopedIds = new ArrayList<>();
            Set<Integer> legacyIds = new LinkedHashSet<>();
            if (root != null && root.isArray()) {
                for (JsonNode n : root) {
                    if (n != null && n.isTextual()) {
                        String id = ScopedTileIds.normalize(n.asText());
                        if (id != null) {
                            scopedIds.add(id);
                        }
                    }
                    else if (n != null && n.canConvertToInt()) {
                        int id = n.asInt(-1);
                        if (id >= 0) {
                            legacyIds.add(id);
                        }
                    }
                }
            }
            return new WestCacheData(new LinkedHashSet<>(scopedIds), legacyIds);
        }
        catch (IOException ignored) {
            return new WestCacheData(Set.of(), Set.of());
        }
    }

    private record WestCacheData(Set<String> scopedIds, Set<Integer> legacyIds) {
    }
}
