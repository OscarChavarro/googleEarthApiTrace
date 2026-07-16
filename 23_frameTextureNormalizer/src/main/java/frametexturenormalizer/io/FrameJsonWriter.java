package frametexturenormalizer.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import frametexturenormalizer.config.Configuration;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.TileInstance;
import frametexturenormalizer.model.TileInstance.TriangleStripGeometry;
import frametexturenormalizer.model.TileInstance.TriangleStripVertex;
import frametexturenormalizer.processing.uncles.ToUncleRelationship;

public final class FrameJsonWriter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");

    private FrameJsonWriter() {
    }

    public static void writeFrames(List<FrameData> frames, Set<Integer> frameIds) {
        if (frames == null || frames.isEmpty() || frameIds == null || frameIds.isEmpty()) {
            return;
        }
        for (FrameData frame : frames) {
            if (frame != null && frameIds.contains(frame.getId())) {
                writeFrame(frame);
            }
        }
    }

    public static Set<Integer> writeDeletedTiles(Map<Integer, Set<Integer>> deletedTileIdsByFrame) {
        if (deletedTileIdsByFrame == null || deletedTileIdsByFrame.isEmpty()) {
            return Set.of();
        }
        long started = System.nanoTime();
        System.out.printf("Writing %d edited frame.json files...%n", deletedTileIdsByFrame.size());
        int threadCount = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<Integer>> futures = new ArrayList<>(deletedTileIdsByFrame.size());
        try {
            for (Map.Entry<Integer, Set<Integer>> entry : deletedTileIdsByFrame.entrySet()) {
                Integer frameId = entry.getKey();
                Set<Integer> deletedIds = entry.getValue();
                if (frameId == null || deletedIds == null || deletedIds.isEmpty()) {
                    continue;
                }
                Set<Integer> deletedIdsCopy = new LinkedHashSet<>(deletedIds);
                futures.add(executor.submit(() -> writeDeletedTiles(frameId, deletedIdsCopy) ? frameId : null));
            }

            Set<Integer> writtenFrameIds = new LinkedHashSet<>();
            for (Future<Integer> future : futures) {
                try {
                    Integer frameId = future.get();
                    if (frameId != null) {
                        writtenFrameIds.add(frameId);
                    }
                }
                catch (Exception ignored) {
                }
            }
            double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
            System.out.printf("Wrote %d/%d edited frame.json files in %.1fs.%n",
                writtenFrameIds.size(),
                futures.size(),
                seconds
            );
            return writtenFrameIds;
        }
        finally {
            executor.shutdownNow();
        }
    }

    private static void writeFrame(FrameData frame) {
        Path frameDir = Path.of(Configuration.INPUT_PATH).resolve(String.format("%05d", frame.getId()));
        Path frameJson = frameDir.resolve("frame.json");
        try {
            Files.createDirectories(frameDir);
            ObjectNode root = readOrCreateRoot(frameJson, frame.getId());
            root.set("tiles", rewrittenTiles(root.path("tiles"), frame.getTiles()));
            JSON.writerWithDefaultPrettyPrinter().writeValue(frameJson.toFile(), root);
        }
        catch (IOException ignored) {
        }
    }

    private static boolean writeDeletedTiles(int frameId, Set<Integer> deletedTileIds) {
        Path frameDir = Path.of(Configuration.INPUT_PATH).resolve(String.format("%05d", frameId));
        Path frameJson = frameDir.resolve("frame.json");
        if (!Files.isRegularFile(frameJson)) {
            return false;
        }
        try {
            JsonNode rootNode = JSON.readTree(frameJson.toFile());
            if (rootNode == null || !rootNode.isObject()) {
                return false;
            }
            ObjectNode root = (ObjectNode) rootNode;
            JsonNode tilesNode = root.path("tiles");
            if (!tilesNode.isArray()) {
                return false;
            }
            ArrayNode rewritten = JSON.createArrayNode();
            boolean changed = false;
            for (JsonNode tileNode : tilesNode) {
                int tileId = parseTileId(tileNode);
                if (deletedTileIds.contains(tileId)) {
                    changed = true;
                    continue;
                }
                if (tileNode != null && tileNode.isObject()) {
                    changed |= clearDeletedNeighbors((ObjectNode) tileNode, deletedTileIds);
                }
                rewritten.add(tileNode);
            }
            if (!changed) {
                return true;
            }
            root.set("tiles", rewritten);
            writeAtomically(frameJson, root);
            return true;
        }
        catch (IOException ex) {
            return false;
        }
    }

    private static boolean clearDeletedNeighbors(ObjectNode tileNode, Set<Integer> deletedTileIds) {
        boolean changed = false;
        changed |= clearDeletedNeighbor(tileNode, "southNeighbor", deletedTileIds);
        changed |= clearDeletedNeighbor(tileNode, "northNeighbor", deletedTileIds);
        changed |= clearDeletedNeighbor(tileNode, "eastNeighbor", deletedTileIds);
        changed |= clearDeletedNeighbor(tileNode, "westNeighbor", deletedTileIds);
        return changed;
    }

    private static boolean clearDeletedNeighbor(ObjectNode tileNode, String field, Set<Integer> deletedTileIds) {
        JsonNode node = tileNode.get(field);
        Integer neighborId = nullableNeighbor(node);
        if (neighborId == null || !deletedTileIds.contains(neighborId)) {
            return false;
        }
        tileNode.putNull(field);
        return true;
    }

    private static Integer nullableNeighbor(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isInt() || node.isLong()) {
            int value = node.asInt(-1);
            return value < 0 ? null : value;
        }
        int value = extractLastNumber(node.asText(null), -1);
        return value < 0 ? null : value;
    }

    private static void writeAtomically(Path frameJson, ObjectNode root) throws IOException {
        Path tmp = frameJson.resolveSibling(frameJson.getFileName().toString() + ".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), root);
        try {
            Files.move(tmp, frameJson, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException ex) {
            Files.move(tmp, frameJson, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static ObjectNode readOrCreateRoot(Path frameJson, int frameId) throws IOException {
        if (Files.isRegularFile(frameJson)) {
            JsonNode root = JSON.readTree(frameJson.toFile());
            if (root != null && root.isObject()) {
                return (ObjectNode) root;
            }
        }
        ObjectNode root = JSON.createObjectNode();
        root.put("id", frameId);
        return root;
    }

    private static ArrayNode rewrittenTiles(JsonNode originalTiles, List<TileInstance> currentTiles) {
        Map<Integer, TileInstance> currentById = new HashMap<>();
        if (currentTiles != null) {
            for (TileInstance tile : currentTiles) {
                if (tile != null) {
                    currentById.put(tile.getTileId(), tile);
                }
            }
        }

        ArrayNode out = JSON.createArrayNode();
        if (originalTiles != null && originalTiles.isArray()) {
            for (JsonNode originalTile : originalTiles) {
                int tileId = parseTileId(originalTile);
                TileInstance current = currentById.remove(tileId);
                if (current == null) {
                    continue;
                }
                ObjectNode tileNode = originalTile != null && originalTile.isObject()
                    ? ((ObjectNode) originalTile).deepCopy()
                    : tileToJson(current);
                updateNeighbors(tileNode, current);
                out.add(tileNode);
            }
        }
        for (TileInstance remaining : currentById.values()) {
            out.add(tileToJson(remaining));
        }
        return out;
    }

    private static int parseTileId(JsonNode tileNode) {
        if (tileNode == null || tileNode.isNull()) {
            return -1;
        }
        JsonNode contentId = tileNode.get("contentId");
        if (contentId != null && !contentId.isNull()) {
            if (contentId.isInt() || contentId.isLong()) {
                return contentId.asInt(-1);
            }
            int parsed = extractLastNumber(contentId.asText(null), -1);
            if (parsed >= 0) {
                return parsed;
            }
        }
        JsonNode textureFile = tileNode.get("textureFile");
        return extractLastNumber(textureFile == null ? null : textureFile.asText(null), -1);
    }

    private static int extractLastNumber(String text, int fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        Integer last = null;
        while (matcher.find()) {
            last = Integer.parseInt(matcher.group(1));
        }
        return last == null ? fallback : last;
    }

    private static void updateNeighbors(ObjectNode tileNode, TileInstance tile) {
        putNullableInt(tileNode, "southNeighbor", tile.getSouthNeighbor());
        putNullableInt(tileNode, "northNeighbor", tile.getNorthNeighbor());
        putNullableInt(tileNode, "eastNeighbor", tile.getEastNeighbor());
        putNullableInt(tileNode, "westNeighbor", tile.getWestNeighbor());
    }

    private static ObjectNode tileToJson(TileInstance tile) {
        ObjectNode node = JSON.createObjectNode();
        node.put("contentId", tile.getTileId());
        if (tile.getTextureFile() == null) {
            node.putNull("textureFile");
        }
        else {
            node.put("textureFile", tile.getTextureFile());
        }
        updateNeighbors(node, tile);
        writeTriangleStrip(node, tile.getTriangleStrip());
        writeArray16(node, "modelViewMatrix", tile.getModelViewMatrix());
        writeUncles(node, tile.getUncles());
        return node;
    }

    private static void putNullableInt(ObjectNode node, String field, Integer value) {
        if (value == null) {
            node.putNull(field);
        }
        else {
            node.put(field, value);
        }
    }

    private static void writeTriangleStrip(ObjectNode node, TriangleStripGeometry strip) {
        if (strip == null) {
            node.putNull("triangleStrip");
            return;
        }
        ObjectNode stripNode = JSON.createObjectNode();
        stripNode.put("vertexCount", strip.vertexCount());
        ArrayNode vertices = JSON.createArrayNode();
        if (strip.vertices() != null) {
            for (TriangleStripVertex vertex : strip.vertices()) {
                ObjectNode vertexNode = JSON.createObjectNode();
                vertexNode.put("x", vertex.x());
                vertexNode.put("y", vertex.y());
                vertexNode.put("z", vertex.z());
                vertexNode.put("u", vertex.u());
                vertexNode.put("v", vertex.v());
                vertices.add(vertexNode);
            }
        }
        stripNode.set("vertices", vertices);
        node.set("triangleStrip", stripNode);
    }

    private static void writeArray16(ObjectNode node, String field, double[] values) {
        if (values == null || values.length != 16) {
            node.putNull(field);
            return;
        }
        ArrayNode arr = JSON.createArrayNode();
        for (double value : values) {
            arr.add(value);
        }
        node.set(field, arr);
    }

    private static void writeUncles(ObjectNode node, List<ToUncleRelationship> uncles) {
        ArrayNode arr = JSON.createArrayNode();
        if (uncles != null) {
            for (ToUncleRelationship uncle : uncles) {
                if (uncle == null) {
                    continue;
                }
                ObjectNode uncleNode = JSON.createObjectNode();
                uncleNode.put("direction", uncle.direction().name());
                uncleNode.put("uncleContentId", uncle.uncleContentId());
                arr.add(uncleNode);
            }
        }
        node.set("uncles", arr);
    }
}
