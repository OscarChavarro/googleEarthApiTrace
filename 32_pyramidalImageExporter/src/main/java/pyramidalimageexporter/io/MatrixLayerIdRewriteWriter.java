package pyramidalimageexporter.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Persists content-hash-resolved quadtree ids back into a matrix_&lt;n&gt;/matrixLayer.json
 * tile record, so a tile anchored once by ContentHashRootPathResolver becomes a
 * direct quadtree seed on every later run, with no re-hashing needed.
 */
public final class MatrixLayerIdRewriteWriter {
    private static final ObjectMapper JSON = new ObjectMapper();

    public void rewriteIds(Path matrixLayerJsonFile, Map<String, String> newIdByOldId) {
        if (matrixLayerJsonFile == null || newIdByOldId == null || newIdByOldId.isEmpty()) {
            return;
        }
        if (!Files.isRegularFile(matrixLayerJsonFile) || !Files.isWritable(matrixLayerJsonFile)) {
            return;
        }
        try {
            JsonNode root = JSON.readTree(matrixLayerJsonFile.toFile());
            ArrayNode tiles = findTilesArray(root);
            if (tiles == null) {
                return;
            }
            boolean changed = false;
            for (JsonNode tileNode : tiles) {
                if (!(tileNode instanceof ObjectNode tile)) {
                    continue;
                }
                JsonNode idNode = tile.get("id");
                String currentId = idNode == null ? null : idNode.asText(null);
                String newId = currentId == null ? null : newIdByOldId.get(currentId);
                if (newId != null) {
                    tile.put("id", newId);
                    changed = true;
                }
            }
            if (changed) {
                JSON.writerWithDefaultPrettyPrinter().writeValue(matrixLayerJsonFile.toFile(), root);
            }
        }
        catch (IOException ex) {
            System.out.println(
                "MatrixLayerIdRewriteWriter: could not rewrite " + matrixLayerJsonFile + ": " + ex.getMessage()
            );
        }
    }

    private static ArrayNode findTilesArray(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode matrices = root.get("matrices");
        JsonNode matrixNode = matrices != null && matrices.isArray() && !matrices.isEmpty() ? matrices.get(0) : root;
        JsonNode tiles = matrixNode == null ? null : matrixNode.get("tiles");
        return tiles instanceof ArrayNode arrayNode ? arrayNode : null;
    }
}
