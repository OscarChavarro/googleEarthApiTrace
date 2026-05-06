package frametexturenormalizer.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import frametexturenormalizer.config.Configuration;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.GoogleCameraState;
import frametexturenormalizer.model.Line;
import frametexturenormalizer.model.TileInstance;

public final class TraceSessionReader {
    private static final Pattern NUMERIC_DIR = Pattern.compile("\\d+");
    private static final ObjectMapper JSON = new ObjectMapper();

    public List<Path> listFrameDirectories(Path inputRoot) {
        if (inputRoot == null || !Files.isDirectory(inputRoot)) {
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
            return dirs;
        }
        catch (IOException ex) {
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
            double[] frameProjectionMatrix = readFrameProjectionMatrix(root);
            double[] frameModelViewMatrix = readFrameModelViewMatrix(root, cameraState);
            List<Line> lines = readLines(root, cameraState);
            return new FrameData(frameId, tiles, lines, cameraState, frameProjectionMatrix, frameModelViewMatrix, false);
        }
        catch (IOException ex) {
            return null;
        }
    }

    public List<FrameData> readSession(Path inputRoot) {
        List<Path> frameDirs = listFrameDirectories(inputRoot);
        if (frameDirs.isEmpty()) {
            return List.of();
        }
        List<FrameData> out = new ArrayList<>();

        for (Path dir : frameDirs) {
            FrameData frame = readFrameDirectory(dir);
            if (frame == null) {
                continue;
            }
            out.add(frame);
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

    private static double[] readFrameProjectionMatrix(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode cameraNode = root.path("camera");
        double[] projection = readArray16(cameraNode.path("projectionMatrix"));
        if (projection != null) {
            return projection;
        }
        return readArray16(root.path("projectionMatrix"));
    }

    private static double[] readFrameModelViewMatrix(JsonNode root, GoogleCameraState cameraState) {
        if (root == null) {
            return cameraState == null ? null : cameraState.getModelViewMatrix();
        }
        JsonNode cameraNode = root.path("camera");
        double[] modelView = readArray16(cameraNode.path("modelViewMatrix"));
        if (modelView != null) {
            return modelView;
        }
        modelView = readArray16(root.path("modelViewMatrix"));
        if (modelView != null) {
            return modelView;
        }
        return cameraState == null ? null : cameraState.getModelViewMatrix();
    }
}
