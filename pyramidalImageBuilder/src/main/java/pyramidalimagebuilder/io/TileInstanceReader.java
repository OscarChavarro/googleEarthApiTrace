package pyramidalimagebuilder.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import pyramidalimagebuilder.model.TileInstance;

public final class TileInstanceReader {
    private static final ObjectMapper JSON = new ObjectMapper();

    public List<TileInstance> read(Path frameJsonPath) throws IOException {
        JsonNode root = JSON.readTree(frameJsonPath.toFile());
        int frameId = root.path("id").asInt(-1);
        JsonNode tiles = root.path("tiles");
        if (!tiles.isArray()) {
            return List.of();
        }

        List<TileInstance> result = new ArrayList<>(tiles.size());
        for (JsonNode tile : tiles) {
            int tileId = tile.path("contentId").asInt(-1);
            Integer south = nullableNeighbor(tile.get("southNeighbor"));
            Integer north = nullableNeighbor(tile.get("northNeighbor"));
            Integer east = nullableNeighbor(tile.get("eastNeighbor"));
            Integer west = nullableNeighbor(tile.get("westNeighbor"));
            result.add(new TileInstance(tileId, frameId, south, north, east, west));
        }
        return result;
    }

    private static Integer nullableNeighbor(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        int value = node.asInt(-1);
        if (value < 0) {
            return null;
        }
        return value;
    }
}
