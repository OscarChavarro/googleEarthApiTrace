package pyramidalimagebuilder.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import pyramidalimagebuilder.config.Configuration;
import pyramidalimagebuilder.model.FrameData;
import pyramidalimagebuilder.model.GoogleCameraState;
import pyramidalimagebuilder.model.TileInstance;

public final class TraceSessionReader {
    private static final Pattern NUMERIC_DIR = Pattern.compile("\\d+");
    private static final ObjectMapper JSON = new ObjectMapper();

    public List<FrameData> readSession(Path inputRoot) {
        if (inputRoot == null || !Files.isDirectory(inputRoot)) {
            return List.of();
        }

        TileInstanceReader tileReader = new TileInstanceReader();
        List<FrameData> out = new ArrayList<>();
        Set<Integer> previousTileIds = null;

        try (var stream = Files.list(inputRoot)) {
            List<Path> frameDirs = stream
                .filter(Files::isDirectory)
                .filter(dir -> NUMERIC_DIR.matcher(dir.getFileName().toString()).matches())
                .filter(dir -> {
                    int frameNumber = Integer.parseInt(dir.getFileName().toString());
                    return frameNumber >= Configuration.START_FROM_FRAME;
                })
                .sorted(Comparator.comparing(dir -> dir.getFileName().toString()))
                .toList();

            for (Path dir : frameDirs) {
                Path frameJson = dir.resolve("frame.json");
                if (!Files.isRegularFile(frameJson)) {
                    continue;
                }
                try {
                    JsonNode root = JSON.readTree(frameJson.toFile());
                    int frameId = root.path("id").asInt(-1);
                    List<TileInstance> tiles = tileReader.read(root);
                    if (tiles.size() <= 1) {
                        continue;
                    }
                    GoogleCameraState cameraState = GoogleCameraState.fromFrameJson(root);
                    Set<Integer> tileIds = tileIdSet(tiles);
                    if (previousTileIds != null && previousTileIds.equals(tileIds)) {
                        continue;
                    }
                    out.add(new FrameData(frameId, tiles, cameraState));
                    previousTileIds = tileIds;
                }
                catch (IOException ignored) {
                }
            }
        }
        catch (IOException ignored) {
            return List.of();
        }

        return out;
    }

    private static Set<Integer> tileIdSet(List<TileInstance> tiles) {
        Set<Integer> out = new LinkedHashSet<>();
        if (tiles == null) {
            return out;
        }
        for (TileInstance tile : tiles) {
            if (tile != null && tile.getTileId() >= 0) {
                out.add(tile.getTileId());
            }
        }
        return out;
    }
}
