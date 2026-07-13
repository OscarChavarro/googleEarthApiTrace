package pyramidalimageexporter.processing.uncles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.MatrixLayerTile;

/**
 * Restores uncle relationships that 31_matrixMerger's merge drops: when two
 * frame matrices are merged, cells already present in the accumulated matrix
 * keep their original (often uncle-less) record, and the duplicate cell's
 * uncle metadata is discarded. The per-frame matrix.json files under
 * output.directory still hold every original relationship, so this pass
 * scans them and re-attaches, to each loaded tile, any uncle recorded for
 * the same tile id in any frame. Without this, whole merged layers end up
 * with no path to the quadtree root even though the traced session recorded
 * one.
 */
public final class FrameJsonUncleMetadataRestorer {
    private static final ObjectMapper JSON = new ObjectMapper();

    public void enrich(List<MatrixLayer> layers, Path outputDirectory) {
        if (layers == null || outputDirectory == null || !Files.isDirectory(outputDirectory)) {
            return;
        }
        Map<String, List<MatrixLayerTile>> loadedTilesById = collectLoadedTiles(layers);
        if (loadedTilesById.isEmpty()) {
            return;
        }
        int recoveredRelations = 0;
        int enrichedTiles = 0;
        List<Path> frameMatrixFiles = listFrameMatrixFiles(outputDirectory);
        for (Path matrixJson : frameMatrixFiles) {
            JsonNode root = readJson(matrixJson);
            JsonNode tiles = tilesArray(root);
            if (tiles == null) {
                continue;
            }
            for (JsonNode tileNode : tiles) {
                String id = textOf(tileNode, "id");
                JsonNode uncles = tileNode.get("uncles");
                if (id == null || uncles == null || !uncles.isArray() || uncles.isEmpty()) {
                    continue;
                }
                List<MatrixLayerTile> targets = loadedTilesById.get(id);
                if (targets == null) {
                    continue;
                }
                List<ToUncleRelationship> recovered = parseUncles(uncles);
                if (recovered.isEmpty()) {
                    continue;
                }
                for (MatrixLayerTile target : targets) {
                    Set<ToUncleRelationship> merged = new LinkedHashSet<>(target.getUncles());
                    if (merged.addAll(recovered)) {
                        target.setUncles(new ArrayList<>(merged));
                        recoveredRelations += recovered.size();
                        enrichedTiles++;
                    }
                }
            }
        }
        if (enrichedTiles > 0) {
            System.out.println(
                "FrameJsonUncleMetadataRestorer: re-attached uncle relations to " + enrichedTiles
                    + " tile(s) from per-frame matrix.json files (lost during 31_matrixMerger merges)."
            );
        }
    }

    private static Map<String, List<MatrixLayerTile>> collectLoadedTiles(List<MatrixLayer> layers) {
        Map<String, List<MatrixLayerTile>> out = new HashMap<>();
        for (MatrixLayer layer : layers) {
            if (layer == null || layer.getTiles() == null) {
                continue;
            }
            for (MatrixLayerTile tile : layer.getTiles()) {
                if (tile != null && !tile.getId().isBlank()) {
                    out.computeIfAbsent(tile.getId(), key -> new ArrayList<>()).add(tile);
                }
            }
        }
        return out;
    }

    private static List<Path> listFrameMatrixFiles(Path outputDirectory) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(outputDirectory)) {
            stream.filter(Files::isDirectory)
                .filter(path -> path.getFileName().toString().matches("\\d+"))
                .map(path -> path.resolve("matrix.json"))
                .filter(Files::isRegularFile)
                .forEach(out::add);
        }
        catch (IOException ex) {
            System.out.println("FrameJsonUncleMetadataRestorer: could not scan " + outputDirectory + ": " + ex.getMessage());
        }
        return out;
    }

    private static JsonNode readJson(Path file) {
        try {
            return JSON.readTree(file.toFile());
        }
        catch (IOException ex) {
            System.out.println("FrameJsonUncleMetadataRestorer: could not read " + file + ": " + ex.getMessage());
            return null;
        }
    }

    private static JsonNode tilesArray(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode matrices = root.get("matrices");
        JsonNode matrixNode = matrices != null && matrices.isArray() && !matrices.isEmpty() ? matrices.get(0) : root;
        JsonNode tiles = matrixNode == null ? null : matrixNode.get("tiles");
        return tiles != null && tiles.isArray() ? tiles : null;
    }

    private static List<ToUncleRelationship> parseUncles(JsonNode uncles) {
        List<ToUncleRelationship> out = new ArrayList<>();
        for (JsonNode uncleNode : uncles) {
            String direction = textOf(uncleNode, "direction");
            String uncleContentId = textOf(uncleNode, "uncleContentId");
            if (direction == null || uncleContentId == null) {
                continue;
            }
            try {
                out.add(new ToUncleRelationship(UncleDirections.valueOf(direction), uncleContentId));
            }
            catch (IllegalArgumentException ignored) {
                // Unknown direction label: skip this relation.
            }
        }
        return out;
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        String text = value == null ? null : value.asText(null);
        return text == null || text.isBlank() ? null : text;
    }
}
