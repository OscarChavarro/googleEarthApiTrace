package pyramidalimageexporter.processing.uncles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.MatrixLayerTile;

/**
 * Repairs uncle references that dangle because their target tile did not
 * survive the 23_frameTextureNormalizer filtering: the uncle id ("00129_35")
 * still exists in that frame's original frame.json (contentId "129_35"),
 * whose textureFile records the tile's canonical, deduplicated texture. That
 * texture is the tile's real-world identity — the planet does not change —
 * so it can anchor the reference even though the tile itself is gone:
 *
 * - If the texture is one of the catalogued top-level images, the uncle's
 *   absolute quadtree path is known outright ({@code fullPathByExternalId}).
 * - If the texture belongs to a tile that DID survive into a loaded layer,
 *   the dangling id is an alias of that tile ({@code aliasById}) and resolves
 *   whenever the surviving tile does.
 *
 * Additionally, any loaded tile whose own texture is a catalogued top-level
 * image is seeded directly with that image's path.
 */
public final class ExternalUncleBridgeBuilder {
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Bridge(Map<String, String> fullPathByExternalId, Map<String, String> aliasById) {}

    private final Map<String, JsonNode> frameJsonCache = new HashMap<>();

    public Bridge build(List<MatrixLayer> layers, Map<String, String> quadLabelByImagePath, Path outputDirectory) {
        Map<String, String> fullPathByExternalId = new HashMap<>();
        Map<String, String> aliasById = new HashMap<>();
        if (layers == null) {
            return new Bridge(fullPathByExternalId, aliasById);
        }
        Map<String, String> catalogued = quadLabelByImagePath == null ? Map.of() : quadLabelByImagePath;

        Set<String> loadedIds = new HashSet<>();
        Map<String, String> tileIdByTexture = new HashMap<>();
        for (MatrixLayer layer : layers) {
            if (layer == null || layer.getTiles() == null) {
                continue;
            }
            for (MatrixLayerTile tile : layer.getTiles()) {
                if (tile == null || tile.getId().isBlank()) {
                    continue;
                }
                loadedIds.add(tile.getId());
                // 31_matrixMerger copies each texture into matrix_<n>/<tileId>.png,
                // so the loaded textureFile no longer matches the original path the
                // catalogue and frame.json use. The id itself IS the original
                // texture's identity ("00128_103" -> 00128/256x256_103.png), so both
                // indexes are keyed by that reconstructed canonical path.
                String canonicalTexture = originalTexturePathOf(tile.getId(), outputDirectory);
                if (canonicalTexture == null) {
                    canonicalTexture = tile.getTextureFile();
                }
                if (canonicalTexture == null) {
                    continue;
                }
                tileIdByTexture.putIfAbsent(canonicalTexture, tile.getId());
                String label = catalogued.get(canonicalTexture);
                if (label != null) {
                    fullPathByExternalId.putIfAbsent(tile.getId(), requireFullPath(label));
                }
            }
        }

        for (MatrixLayer layer : layers) {
            if (layer == null || layer.getTiles() == null) {
                continue;
            }
            for (MatrixLayerTile tile : layer.getTiles()) {
                if (tile == null || tile.getUncles() == null) {
                    continue;
                }
                for (ToUncleRelationship relation : tile.getUncles()) {
                    String uncleId = relation == null ? null : relation.uncleContentId();
                    if (uncleId == null || uncleId.isBlank() || loadedIds.contains(uncleId)
                        || fullPathByExternalId.containsKey(uncleId) || aliasById.containsKey(uncleId)) {
                        continue;
                    }
                    String textureFile = danglingUncleTexture(uncleId, outputDirectory);
                    if (textureFile == null) {
                        continue;
                    }
                    String label = catalogued.get(textureFile);
                    if (label != null) {
                        fullPathByExternalId.put(uncleId, requireFullPath(label));
                        continue;
                    }
                    String survivingTileId = tileIdByTexture.get(textureFile);
                    if (survivingTileId != null) {
                        aliasById.put(uncleId, survivingTileId);
                    }
                }
            }
        }
        return new Bridge(fullPathByExternalId, aliasById);
    }

    /**
     * Uncle id "00129_35" names contentId "129_35" inside
     * outputDirectory/00129/frame.json; returns that tile's textureFile.
     */
    private String danglingUncleTexture(String uncleId, Path outputDirectory) {
        if (outputDirectory == null) {
            return null;
        }
        int separator = uncleId.lastIndexOf('_');
        if (separator <= 0 || separator >= uncleId.length() - 1) {
            return null;
        }
        String frameToken = uncleId.substring(0, separator);
        String tileNumber = uncleId.substring(separator + 1);
        int frameId;
        try {
            frameId = Integer.parseInt(frameToken);
        }
        catch (NumberFormatException ex) {
            return null;
        }
        JsonNode frameJson = readFrameJson(outputDirectory, frameId);
        if (frameJson == null) {
            return null;
        }
        String wantedContentId = frameId + "_" + tileNumber;
        JsonNode tiles = frameJson.get("tiles");
        if (tiles == null || !tiles.isArray()) {
            return null;
        }
        for (JsonNode tileNode : tiles) {
            JsonNode contentId = tileNode.get("contentId");
            if (contentId != null && wantedContentId.equals(contentId.asText())) {
                JsonNode textureFile = tileNode.get("textureFile");
                return textureFile == null ? null : textureFile.asText(null);
            }
        }
        return null;
    }

    private JsonNode readFrameJson(Path outputDirectory, int frameId) {
        String key = String.format("%05d", frameId);
        if (frameJsonCache.containsKey(key)) {
            return frameJsonCache.get(key);
        }
        Path frameJsonPath = outputDirectory.resolve(key).resolve("frame.json");
        JsonNode parsed = null;
        if (Files.isRegularFile(frameJsonPath) && Files.isReadable(frameJsonPath)) {
            try {
                parsed = JSON.readTree(frameJsonPath.toFile());
            }
            catch (IOException ex) {
                System.out.println("ExternalUncleBridgeBuilder: could not read " + frameJsonPath + ": " + ex.getMessage());
            }
        }
        frameJsonCache.put(key, parsed);
        return parsed;
    }

    /**
     * A tile id of the form "&lt;frame&gt;_&lt;n&gt;" (e.g. "00128_103") is named after
     * its canonical texture, outputDirectory/&lt;frame&gt;/256x256_&lt;n&gt;.png; returns
     * null for ids in any other format (e.g. quadkey ids of top-level tiles).
     */
    private static String originalTexturePathOf(String tileId, Path outputDirectory) {
        if (tileId == null || outputDirectory == null || !tileId.matches("\\d{5}_\\d+")) {
            return null;
        }
        int separator = tileId.indexOf('_');
        return outputDirectory
            .resolve(tileId.substring(0, separator))
            .resolve("256x256_" + tileId.substring(separator + 1) + ".png")
            .toString();
    }

    private static String requireFullPath(String path) {
        if (path == null || !path.matches("0[0-3]*")) {
            throw new IllegalArgumentException("Catalogued quadtree path is not absolute: " + path);
        }
        return path;
    }
}
