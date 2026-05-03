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

    public List<Path> listFrameDirectories(Path inputRoot) {
        if (inputRoot == null || !Files.isDirectory(inputRoot)) {
            return List.of();
        }
        try (var stream = Files.list(inputRoot)) {
            return stream
                .filter(Files::isDirectory)
                .filter(dir -> NUMERIC_DIR.matcher(dir.getFileName().toString()).matches())
                .filter(dir -> {
                    int frameNumber = Integer.parseInt(dir.getFileName().toString());
                    return frameNumber >= Configuration.START_FROM_FRAME;
                })
                .sorted(Comparator.comparing(dir -> dir.getFileName().toString()))
                .toList();
        }
        catch (IOException ignored) {
            return List.of();
        }
    }

    public FrameData readFrameDirectory(Path dir) {
        if (dir == null) {
            return null;
        }
        Path frameJson = dir.resolve("frame.json");
        if (!Files.isRegularFile(frameJson)) {
            return null;
        }
        TileInstanceReader tileReader = new TileInstanceReader();
        try {
            JsonNode root = JSON.readTree(frameJson.toFile());
            int frameId = root.path("id").asInt(-1);
            List<TileInstance> tiles = tileReader.read(root);
            if (tiles.size() <= 1) {
                return null;
            }
            GoogleCameraState cameraState = GoogleCameraState.fromFrameJson(root);
            return new FrameData(frameId, tiles, cameraState);
        }
        catch (IOException ignored) {
            return null;
        }
    }

    public List<FrameData> readSession(Path inputRoot) {
        List<Path> frameDirs = listFrameDirectories(inputRoot);
        if (frameDirs.isEmpty()) {
            return List.of();
        }
        List<FrameData> out = new ArrayList<>();
        Set<Integer> previousTileIds = null;

        for (Path dir : frameDirs) {
            FrameData frame = readFrameDirectory(dir);
            if (frame == null) {
                continue;
            }
            Set<Integer> tileIds = tileIdSet(frame.getTiles());
            if (previousTileIds != null && previousTileIds.equals(tileIds)) {
                continue;
            }
            out.add(frame);
            previousTileIds = tileIds;
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
