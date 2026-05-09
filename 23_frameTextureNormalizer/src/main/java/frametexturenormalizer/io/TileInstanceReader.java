package frametexturenormalizer.io;

// Java classes
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Libraries classes
import com.fasterxml.jackson.databind.JsonNode;

// App classes
import frametexturenormalizer.model.TileInstance;
import frametexturenormalizer.model.TileInstance.TriangleStripGeometry;
import frametexturenormalizer.model.TileInstance.TriangleStripVertex;

public final class TileInstanceReader {
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");

    public List<TileInstance> read(JsonNode root) {
        int frameId = root.path("id").asInt(-1);
        JsonNode tiles = root.path("tiles");
        if (!tiles.isArray()) {
            return List.of();
        }

        List<TileInstance> result = new ArrayList<>(tiles.size());
        for (JsonNode tile : tiles) {
            String textureFile = nullableText(tile.get("textureFile"));
            int tileId = parseTileId(tile.get("contentId"), textureFile);
            Integer south = nullableNeighbor(tile.get("southNeighbor"));
            Integer north = nullableNeighbor(tile.get("northNeighbor"));
            Integer east = nullableNeighbor(tile.get("eastNeighbor"));
            Integer west = nullableNeighbor(tile.get("westNeighbor"));
            TriangleStripGeometry triangleStrip = parseTriangleStrip(tile.get("triangleStrip"));
            double[] modelViewMatrix = readArray16(tile.get("modelViewMatrix"));
            result.add(new TileInstance(tileId, frameId, textureFile, south, north, east, west, triangleStrip, modelViewMatrix));
        }
        return result;
    }

    private static int parseTileId(JsonNode contentIdNode, String textureFile) {
        if (contentIdNode != null && !contentIdNode.isNull()) {
            if (contentIdNode.isInt() || contentIdNode.isLong()) {
                return contentIdNode.asInt(-1);
            }
            String contentId = nullableText(contentIdNode);
            int parsedFromContent = extractLastNumber(contentId, -1);
            if (parsedFromContent >= 0) {
                return parsedFromContent;
            }
        }
        return extractLastNumber(textureFile, -1);
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

    private static TriangleStripGeometry parseTriangleStrip(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode verticesNode = node.path("vertices");
        if (!verticesNode.isArray()) {
            return null;
        }
        List<TriangleStripVertex> vertices = new ArrayList<>(verticesNode.size());
        for (JsonNode v : verticesNode) {
            vertices.add(new TriangleStripVertex(
                v.path("x").asDouble(0.0),
                v.path("y").asDouble(0.0),
                v.path("z").asDouble(0.0),
                v.path("u").asDouble(0.0),
                v.path("v").asDouble(0.0)
            ));
        }
        int vertexCount = node.path("vertexCount").asInt(vertices.size());
        if (vertexCount <= 0 || vertices.isEmpty()) {
            return null;
        }
        return new TriangleStripGeometry(vertexCount, List.copyOf(vertices));
    }

    private static Integer nullableNeighbor(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isInt() || node.isLong()) {
            int value = node.asInt(-1);
            return value < 0 ? null : value;
        }
        String text = nullableText(node);
        if (text == null) {
            return null;
        }
        int value = extractLastNumber(text, -1);
        return value < 0 ? null : value;
    }

    private static double[] readArray16(JsonNode arrNode) {
        if (arrNode == null || !arrNode.isArray() || arrNode.size() != 16) {
            return null;
        }
        double[] out = new double[16];
        for (int i = 0; i < 16; i++) {
            out[i] = arrNode.get(i).asDouble(0.0);
        }
        return out;
    }

    private static String nullableText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }
        return text;
    }
}
