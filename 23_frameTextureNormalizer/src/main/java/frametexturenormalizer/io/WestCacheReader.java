package frametexturenormalizer.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import frametexturenormalizer.config.Configuration;
import frametexturenormalizer.model.FrameTextureNormalizerModel;
import frametexturenormalizer.processing.TileCutter;

public final class WestCacheReader {
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
            TileCutter.cutWestFromTileIdsAcrossFrames(model.getFrames(), scopedIds);
        }
        if (!westIds.legacyIds().isEmpty()) {
            TileCutter.cutWestFromLegacyTileIdsAcrossFrames(model.getFrames(), westIds.legacyIds());
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
                        String id = n.asText();
                        if (id != null && !id.isBlank()) {
                            scopedIds.add(id.trim());
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
