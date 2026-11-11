package planetdemviewer.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import planetdemviewer.InteractiveViewer;
import planetdemviewer.model.CameraControlMode;
import planetdemviewer.render.terrain.TerrainSubdivisionScheme;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;

/**
 * Local-only Streamable HTTP MCP endpoint for visual diagnostics.  It is
 * deliberately bound to loopback and has no filesystem or network tools.
 */
public final class ViewerMcpServer implements AutoCloseable {
    static final String TOOLS = """
        {"tools":[
          {"name":"key_event","description":"Send any Java AWT key code to the viewer. Letter keys also need key_char, which tells apart x from X.","inputSchema":{"type":"object","properties":{"key_code":{"type":"integer","description":"java.awt.event.KeyEvent VK_* numeric value"},"key_char":{"type":"string","description":"The character that the key types, if any"}},"required":["key_code"]}},
          {"name":"set_camera_control","description":"Select how the keys move the camera, as the Camera control category of the side panel does. get_camera_model reports the current one.","inputSchema":{"type":"object","properties":{"mode":{"type":"string","enum":["AQUYNZA","LEVELS_BROWSER"]}},"required":["mode"]}},
          {"name":"set_geometry_mode","description":"Enable or disable the 3D terrain mesh mode.","inputSchema":{"type":"object","properties":{"enabled":{"type":"boolean"}},"required":["enabled"]}},
          {"name":"set_tile_bounds","description":"Show or hide white tile bounds in the selected view, as 0 and F4 do.","inputSchema":{"type":"object","properties":{"enabled":{"type":"boolean"}},"required":["enabled"]}},
          {"name":"set_subdivision_scheme","description":"Select how terrain tile meshes are triangulated, as the Edge subdivision options of the layer panel do. Only available in the 3D terrain mode. get_camera_model reports the current scheme.","inputSchema":{"type":"object","properties":{"scheme":{"type":"string","enum":["TRIANGLE_EDGE","DIAMOND_SQUARE","LIND1996"]}},"required":["scheme"]}},
          {"name":"set_screen_error_threshold","description":"Set the threshold tau, in pixels, of the LIND1996 subdivision scheme.","inputSchema":{"type":"object","properties":{"pixels":{"type":"number","minimum":0}},"required":["pixels"]}},
          {"name":"set_camera_model","description":"Define the selected view's camera directly, in world coordinates; use get_camera_model to read them.","inputSchema":{"type":"object","properties":{"position_x":{"type":"number"},"position_y":{"type":"number"},"position_z":{"type":"number"},"focus_x":{"type":"number"},"focus_y":{"type":"number"},"focus_z":{"type":"number"},"up_x":{"type":"number"},"up_y":{"type":"number"},"up_z":{"type":"number"}},"required":["position_x","position_y","position_z","focus_x","focus_y","focus_z","up_x","up_y","up_z"]}},
          {"name":"set_dependencies_cross_tiles","description":"Choose whether adjacent tiles of the same level simplify their common edge together in the LIND1996 subdivision scheme, or keep all its samples.","inputSchema":{"type":"object","properties":{"enabled":{"type":"boolean"}},"required":["enabled"]}},
          {"name":"set_independent_imagery_levels","description":"Choose whether terrain color may come from other levels of its pyramid than the geometry, in the DIAMOND_SQUARE and LIND1996 subdivision schemes. get_camera_model reports the levels used.","inputSchema":{"type":"object","properties":{"enabled":{"type":"boolean"}},"required":["enabled"]}},
          {"name":"set_wires","description":"Show or hide the triangle edges in the selected view.","inputSchema":{"type":"object","properties":{"enabled":{"type":"boolean"}},"required":["enabled"]}},
          {"name":"click_viewport","description":"Click a physical pixel of the image returned by capture_view, using top-left viewport coordinates. A terrain hit selects it; a sky hit clears the selection.","inputSchema":{"type":"object","properties":{"x":{"type":"integer","description":"capture pixel x from the left"},"y":{"type":"integer","description":"capture pixel y from the top"}},"required":["x","y"]}},
          {"name":"capture_view","description":"Capture the current OpenGL canvas to a local PNG file.","inputSchema":{"type":"object","properties":{}}},
          {"name":"get_camera_model","description":"Return the selected camera model and rendering toggles as JSON.","inputSchema":{"type":"object","properties":{}}}
        ]}
        """;

    private final InteractiveViewer viewer;
    private final HttpServer server;
    private final ExecutorService executor;

    public ViewerMcpServer(InteractiveViewer viewer, int port) throws IOException {
        this.viewer = viewer;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "planetdemviewer-mcp");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/mcp", this::handle);
    }

    public void start() {
        server.start();
        System.out.println("PlanetDemViewer MCP listening at http://127.0.0.1:"
            + server.getAddress().getPort() + "/mcp");
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String id = rawId(request);
        try {
            String method = stringValue(request, "method");
            String result = switch (method) {
                case "initialize" -> "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"planetdemviewer\",\"version\":\"1.0\"}}";
                case "notifications/initialized" -> null;
                case "tools/list" -> TOOLS;
                case "tools/call" -> callTool(request);
                default -> throw new IllegalArgumentException("Unsupported MCP method: " + method);
            };
            if (result != null && id != null) {
                respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}");
            }
            else {
                exchange.sendResponseHeaders(202, -1);
            }
        }
        catch (Exception e) {
            String message = escape(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":"
                + (id == null ? "null" : id) + ",\"error\":{\"code\":-32000,\"message\":\""
                + message + "\"}}");
        }
    }

    private String callTool(String request) throws Exception {
        String name = stringValue(request, "name");
        return switch (name) {
            case "key_event" -> {
                if (request.contains("\"key_char\"")) {
                    viewer.pressKeyFromMcp(intValue(request, "key_code"), stringValue(request, "key_char").charAt(0));
                }
                else {
                    viewer.pressKeyFromMcp(intValue(request, "key_code"));
                }
                yield textResult("key event delivered");
            }
            case "set_camera_control" -> {
                viewer.setCameraControlModeFromMcp(CameraControlMode.valueOf(stringValue(request, "mode")));
                yield textResult("camera control updated");
            }
            case "set_geometry_mode" -> {
                viewer.setGeometryModeFromMcp(booleanValue(request, "enabled"));
                yield textResult("geometry mode updated");
            }
            case "set_tile_bounds" -> {
                viewer.setTileBoundsFromMcp(booleanValue(request, "enabled"));
                yield textResult("tile bounds updated");
            }
            case "set_subdivision_scheme" -> {
                TerrainSubdivisionScheme scheme = subdivisionScheme(stringValue(request, "scheme"));
                if (!viewer.setSubdivisionSchemeFromMcp(scheme)) {
                    throw new IllegalStateException(
                        "The subdivision scheme can only change in the 3D terrain mode; call set_geometry_mode first");
                }
                yield textResult("subdivision scheme set to " + scheme);
            }
            case "set_screen_error_threshold" -> {
                double pixels = doubleValue(request, "pixels");
                if (pixels < 0.0) {
                    throw new IllegalArgumentException("The threshold cannot be negative");
                }
                viewer.setScreenErrorThresholdFromMcp(pixels);
                yield textResult("screen error threshold updated");
            }
            case "set_dependencies_cross_tiles" -> {
                viewer.setDependenciesCrossTilesFromMcp(booleanValue(request, "enabled"));
                yield textResult("dependencies across tiles updated");
            }
            case "set_independent_imagery_levels" -> {
                viewer.setIndependentImageryLevelsFromMcp(booleanValue(request, "enabled"));
                yield textResult("independent imagery levels updated");
            }
            case "set_camera_model" -> {
                viewer.setCameraModelFromMcp(
                    vectorValue(request, "position"), vectorValue(request, "focus"), vectorValue(request, "up"));
                yield textResult("camera model updated");
            }
            case "set_wires" -> {
                viewer.setWiresFromMcp(booleanValue(request, "enabled"));
                yield textResult("wires updated");
            }
            case "click_viewport" -> {
                viewer.clickViewportFromMcp(intValue(request, "x"), intValue(request, "y"));
                yield textResult("viewport click delivered");
            }
            case "capture_view" -> {
                Path snapshot = viewer.captureSnapshotFromMcp();
                yield textResult(snapshot.toString());
            }
            case "get_camera_model" -> {
                String camera = viewer.cameraModelJsonFromMcp();
                yield "{\"content\":[{\"type\":\"text\",\"text\":\"" + escape(camera)
                    + "\"}],\"structuredContent\":" + camera + "}";
            }
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
        };
    }

    static TerrainSubdivisionScheme subdivisionScheme(String name) {
        for (TerrainSubdivisionScheme scheme : TerrainSubdivisionScheme.values()) {
            if (scheme.name().equals(name)) {
                return scheme;
            }
        }
        throw new IllegalArgumentException("Unknown subdivision scheme: " + name + "; expected one of "
            + Arrays.toString(TerrainSubdivisionScheme.values()));
    }

    private static String textResult(String text) {
        return "{\"content\":[{\"type\":\"text\",\"text\":\"" + escape(text) + "\"}]}";
    }

    private static String rawId(String request) {
        Matcher matcher = Pattern.compile("\\\"id\\\"\\s*:\\s*(\\\"(?:\\\\.|[^\\\"])*\\\"|-?\\d+|null)").matcher(request);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String stringValue(String request, String key) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key)
            + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(request);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Missing string argument: " + key);
        }
        return matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static int intValue(String request, String key) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(-?\\d+)").matcher(request);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Missing integer argument: " + key);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static Vector3Dd vectorValue(String request, String prefix) {
        return new Vector3Dd(doubleValue(request, prefix + "_x"), doubleValue(request, prefix + "_y"),
            doubleValue(request, prefix + "_z"));
    }

    static double doubleValue(String request, String key) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key)
            + "\\\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)").matcher(request);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Missing number argument: " + key);
        }
        return Double.parseDouble(matcher.group(1));
    }

    private static boolean booleanValue(String request, String key) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(true|false)").matcher(request);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Missing boolean argument: " + key);
        }
        return Boolean.parseBoolean(matcher.group(1));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r");
    }
}
