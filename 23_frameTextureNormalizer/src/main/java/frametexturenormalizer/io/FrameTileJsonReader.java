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
import frametexturenormalizer.model.contract.ScopedTileIds;
import frametexturenormalizer.processing.uncles.ToUncleRelationship;
import frametexturenormalizer.processing.uncles.UncleDirections;
import frametexturenormalizer.processing.uncles.UncleRelationshipKind;

public final class FrameTileJsonReader {
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
            TileInstance parsed = new TileInstance(
                tileId,
                frameId,
                textureFile,
                south,
                north,
                east,
                west,
                triangleStrip,
                modelViewMatrix,
                null,
                null,
                false,
                parseUncles(tile.get("uncles"))
            );
            parsed.setRelationshipGeometries(parseTriangleStrips(tile.get("relationshipGeometries")));
            result.add(parsed);
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

    private static List<ToUncleRelationship> parseUncles(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return List.of();
        }
        List<ToUncleRelationship> out = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (item == null || item.isNull()) {
                continue;
            }
            UncleDirections direction = parseDirection(item.get("direction"));
            String uncleContentId = nullableScopedTileId(
                item.hasNonNull("referenceContentId") ? item.get("referenceContentId") : item.get("uncleContentId")
            );
            Integer levelDelta = nullablePositiveInt(item.get("levelDelta"));
            Integer rowOffset = nullableInt(item.get("rowOffset"));
            Integer columnOffset = nullableInt(item.get("columnOffset"));
            boolean hasGridOffset = levelDelta != null && rowOffset != null && columnOffset != null;
            if (uncleContentId == null || (!hasGridOffset && direction == null)) {
                continue;
            }
            out.add(new ToUncleRelationship(
                direction,
                uncleContentId,
                parseRelationshipKind(item.get("relationshipKind")),
                levelDelta,
                rowOffset,
                columnOffset
            ));
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private static List<TriangleStripGeometry> parseTriangleStrips(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return List.of();
        }
        List<TriangleStripGeometry> out = new ArrayList<>(node.size());
        for (JsonNode geometry : node) {
            TriangleStripGeometry parsed = parseTriangleStrip(geometry);
            if (parsed != null) {
                out.add(parsed);
            }
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private static Integer nullablePositiveInt(JsonNode node) {
        Integer value = nullableInt(node);
        return value != null && value > 0 ? value : null;
    }

    private static Integer nullableInt(JsonNode node) {
        return node == null || node.isNull() || !node.isIntegralNumber() ? null : node.intValue();
    }

    private static String nullableScopedTileId(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isInt() || node.isLong()) {
            int value = node.asInt(-1);
            return value < 0 ? null : Integer.toString(value);
        }
        String text = nullableText(node);
        if (text == null) {
            return null;
        }
        String normalized = ScopedTileIds.normalize(text);
        return normalized == null || normalized.isBlank() ? text : normalized;
    }

    private static UncleDirections parseDirection(JsonNode node) {
        String text = nullableText(node);
        if (text == null) {
            return null;
        }
        try {
            return UncleDirections.valueOf(text);
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static UncleRelationshipKind parseRelationshipKind(JsonNode node) {
        String text = nullableText(node);
        if (text == null) {
            return null;
        }
        try {
            return UncleRelationshipKind.valueOf(text);
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
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
