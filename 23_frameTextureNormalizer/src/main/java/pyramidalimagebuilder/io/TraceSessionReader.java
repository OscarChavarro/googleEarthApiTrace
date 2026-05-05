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
import pyramidalimagebuilder.model.Line;
import pyramidalimagebuilder.model.TileInstance;

public final class TraceSessionReader {
    private static final Pattern NUMERIC_DIR = Pattern.compile("\\d+");
    private static final ObjectMapper JSON = new ObjectMapper();

    public List<Path> listFrameDirectories(Path inputRoot) {
        if (inputRoot == null || !Files.isDirectory(inputRoot)) {
            System.out.println("[trace-reader] input root missing or not directory: " + inputRoot);
            return List.of();
        }
        try (var stream = Files.list(inputRoot)) {
            List<Path> dirs = stream
                .filter(Files::isDirectory)
                .filter(dir -> NUMERIC_DIR.matcher(dir.getFileName().toString()).matches())
                .filter(dir -> {
                    int frameNumber = Integer.parseInt(dir.getFileName().toString());
                    return frameNumber >= Configuration.START_FROM_FRAME;
                })
                .sorted(Comparator.comparing(dir -> dir.getFileName().toString()))
                .toList();
            System.out.println(
                "[trace-reader] input=" + inputRoot
                    + " startFrom=" + Configuration.START_FROM_FRAME
                    + " frameDirs=" + dirs.size()
            );
            return dirs;
        }
        catch (IOException ex) {
            System.out.println("[trace-reader] cannot list frame directories: " + ex.getMessage());
            return List.of();
        }
    }

    public FrameData readFrameDirectory(Path dir) {
        if (dir == null) {
            return null;
        }
        Path frameJson = dir.resolve("frame.json");
        if (!Files.isRegularFile(frameJson)) {
            if ("00100".equals(dir.getFileName().toString())) {
                System.out.println("[trace-reader] frame 00100 has no frame.json");
            }
            return null;
        }
        TileInstanceReader tileReader = new TileInstanceReader();
        try {
            JsonNode root = JSON.readTree(frameJson.toFile());
            int frameId = root.path("id").asInt(-1);
            List<TileInstance> tiles = tileReader.read(root);
            if (tiles.size() <= 1) {
                if (frameId == 100 || "00100".equals(dir.getFileName().toString())) {
                    System.out.println("[trace-reader] frame 100 discarded at read: tiles=" + tiles.size());
                }
                return null;
            }
            if (frameId == 100 || "00100".equals(dir.getFileName().toString())) {
                System.out.println("[trace-reader] frame 100 parsed tiles=" + tiles.size());
            }
            GoogleCameraState cameraState = GoogleCameraState.fromFrameJson(root);
            List<Line> lines = readLines(root, cameraState);
            return new FrameData(frameId, tiles, lines, cameraState);
        }
        catch (IOException ex) {
            if ("00100".equals(dir.getFileName().toString())) {
                System.out.println("[trace-reader] frame 100 json read error: " + ex.getMessage());
            }
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

    private static List<Line> readLines(JsonNode root, GoogleCameraState cameraState) {
        JsonNode linesNode = root.path("lines");
        if (!linesNode.isArray() || linesNode.isEmpty()) {
            return List.of();
        }
        List<Line> out = new ArrayList<>(linesNode.size());
        double[] defaultModelView = cameraState == null ? null : cameraState.getModelViewMatrix();
        for (JsonNode lineNode : linesNode) {
            if (lineNode == null || lineNode.isNull() || !lineNode.isObject()) {
                continue;
            }
            JsonNode stripNode = lineNode.path("lineStrip");
            if (!stripNode.isArray() || stripNode.size() < 2) {
                continue;
            }
            List<Line.Vertex> points = new ArrayList<>(stripNode.size());
            for (JsonNode vertexNode : stripNode) {
                points.add(new Line.Vertex(
                    vertexNode.path("x").asDouble(0.0),
                    vertexNode.path("y").asDouble(0.0),
                    vertexNode.path("z").asDouble(0.0)
                ));
            }
            if (points.size() < 2) {
                continue;
            }
            double[] modelView = readArray16(lineNode.path("modelViewMatrix"));
            if (modelView == null) {
                modelView = defaultModelView;
            }
            out.add(new Line(lineNode.path("primitive").asText("n/a"), List.copyOf(points), modelView));
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
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
}
