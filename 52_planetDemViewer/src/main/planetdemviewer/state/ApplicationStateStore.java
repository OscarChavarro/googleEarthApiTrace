package planetdemviewer.state;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import planetdemviewer.model.OperationModes;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.model.PyramidalImageInstance;
import planetdemviewer.render.PlanetViewerSession;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.camera.Camera;

/** Local, human-readable session state.  It deliberately never stores DEM data. */
public final class ApplicationStateStore {
    public static final Path DEFAULT_PATH = Path.of("state.json");
    private static final String NUMBER = "([-+0-9.Ee]+)";

    private ApplicationStateStore() {
    }

    public static void save(Path path, PlanetViewerModel model, PlanetViewerSession session)
        throws IOException {
        Camera camera = model.getViewingCamera();
        StringBuilder json = new StringBuilder("{\n  \"version\": 1,");
        json.append("\n  \"camera\": {")
            .append(vector("position", camera.getPosition())).append(',')
            .append(vector("focus", camera.getFocusedPosition())).append(',')
            .append(vector("up", camera.getUp())).append(", \"projectionMode\": ")
            .append(camera.getProjectionMode()).append("},");
        json.append("\n  \"model\": {\"operationMode\": \"")
            .append(model.getOperationMode()).append("\", \"heightExaggeration\": ")
            .append(model.getHeightExagerationFactor()).append(", \"currentPSC\": ")
            .append(model.getCurrentPSC()).append(", \"imageryEnabled\": ")
            .append(model.isImageryEnabled()).append("},");
        json.append("\n  \"renderer\": {\"tileBounds\": ")
            .append(session.isTerrainTileBoundsVisible()).append(", \"uniformLod\": ")
            .append(session.isUniformLodVisible()).append("},");
        json.append("\n  \"instances\": [");
        List<PyramidalImageInstance> instances = model.getStack();
        for (int i = 0; i < instances.size(); i++) {
            PyramidalImageInstance instance = instances.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("\n    {\"index\": ").append(i)
                .append(", \"offsetX\": ").append(instance.getOffsetX())
                .append(", \"offsetY\": ").append(instance.getOffsetY())
                .append(", \"psc\": ").append(instance.getPsc())
                .append(", \"zOffset\": ").append(instance.getZOffset())
                .append(", \"opacity\": ").append(instance.getOpacity())
                .append(", \"visible\": ").append(instance.isVisible()).append('}');
        }
        json.append("\n  ]\n}\n");
        Files.writeString(path, json, StandardCharsets.UTF_8);
    }

    /** Restores model state before the renderer is created. */
    public static Optional<RendererState> restoreModel(Path path, PlanetViewerModel model) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            Camera camera = model.getViewingCamera();
            camera.setPosition(vectorValue(json, "position"));
            camera.setFocusedPositionDirect(vectorValue(json, "focus"));
            camera.setUpDirect(vectorValue(json, "up"));
            camera.setProjectionMode(intValue(json, "projectionMode"));
            camera.updateVectors();
            model.setOperationMode(OperationModes.valueOf(stringValue(json, "operationMode")));
            model.setHeightExagerationFactor(doubleValue(json, "heightExaggeration"));
            model.setCurrentPSC(intValue(json, "currentPSC"));
            // Files saved before this setting existed keep the default.
            model.setImageryEnabled(booleanValue(json, "imageryEnabled", model.isImageryEnabled()));
            restoreInstances(json, model.getStack());
            return Optional.of(new RendererState(booleanValue(json, "tileBounds"), booleanValue(json, "uniformLod")));
        }
        catch (Exception e) {
            System.out.println("Could not restore " + path + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    public static boolean delete(Path path) throws IOException {
        return Files.deleteIfExists(path);
    }

    /** Used only when no DEM folders were supplied on the command line. */
    private static void restoreInstances(String json, List<PyramidalImageInstance> instances) {
        Pattern pattern = Pattern.compile("\\{\\\"index\\\":\\s*(\\d+),\\s*\\\"offsetX\\\":\\s*" + NUMBER
            + ",\\s*\\\"offsetY\\\":\\s*" + NUMBER + ",\\s*\\\"psc\\\":\\s*(-?\\d+)"
            + ",\\s*\\\"zOffset\\\":\\s*" + NUMBER + ",\\s*\\\"opacity\\\":\\s*" + NUMBER
            + ",\\s*\\\"visible\\\":\\s*(true|false)\\}");
        Matcher matcher = pattern.matcher(json);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index < 0 || index >= instances.size()) {
                continue;
            }
            PyramidalImageInstance instance = instances.get(index);
            instance.setOffsetX(Double.parseDouble(matcher.group(2)));
            instance.setOffsetY(Double.parseDouble(matcher.group(3)));
            instance.setPsc(Integer.parseInt(matcher.group(4)));
            instance.setZOffset(Double.parseDouble(matcher.group(5)));
            instance.setOpacity(Double.parseDouble(matcher.group(6)));
            instance.setVisible(Boolean.parseBoolean(matcher.group(7)));
        }
    }

    private static String vector(String name, Vector3Dd value) {
        return "\"" + name + "\": {\"x\": " + value.x() + ", \"y\": " + value.y()
            + ", \"z\": " + value.z() + '}';
    }

    private static Vector3Dd vectorValue(String json, String name) {
        Matcher matcher = Pattern.compile("\\\"" + name + "\\\"\\s*:\\s*\\{\\s*\\\"x\\\"\\s*:\\s*" + NUMBER
            + "\\s*,\\s*\\\"y\\\"\\s*:\\s*" + NUMBER + "\\s*,\\s*\\\"z\\\"\\s*:\\s*" + NUMBER).matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Missing camera vector " + name);
        }
        return new Vector3Dd(Double.parseDouble(matcher.group(1)), Double.parseDouble(matcher.group(2)),
            Double.parseDouble(matcher.group(3)));
    }

    private static String stringValue(String json, String name) {
        Matcher matcher = Pattern.compile("\\\"" + name + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Missing state value " + name);
        }
        return matcher.group(1);
    }

    private static double doubleValue(String json, String name) {
        Matcher matcher = Pattern.compile("\\\"" + name + "\\\"\\s*:\\s*" + NUMBER).matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Missing state value " + name);
        }
        return Double.parseDouble(matcher.group(1));
    }

    private static int intValue(String json, String name) {
        return (int) doubleValue(json, name);
    }

    private static boolean booleanValue(String json, String name) {
        Matcher matcher = Pattern.compile("\\\"" + name + "\\\"\\s*:\\s*(true|false)").matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Missing state value " + name);
        }
        return Boolean.parseBoolean(matcher.group(1));
    }

    private static boolean booleanValue(String json, String name, boolean missingValue) {
        Matcher matcher = Pattern.compile("\\\"" + name + "\\\"\\s*:\\s*(true|false)").matcher(json);
        return matcher.find() ? Boolean.parseBoolean(matcher.group(1)) : missingValue;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record RendererState(boolean tileBounds, boolean uniformLod) {
    }
}
