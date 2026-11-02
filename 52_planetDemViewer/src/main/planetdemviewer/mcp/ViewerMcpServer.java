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
import planetdemviewer.terrain.TerrainSubdivisionScheme;

/**
 * Local-only Streamable HTTP MCP endpoint for visual diagnostics.  It is
 * deliberately bound to loopback and has no filesystem or network tools.
 */
public final class ViewerMcpServer implements AutoCloseable {
    static final String TOOLS = """
        {"tools":[
          {"name":"key_event","description":"Send any Java AWT key code to the viewer.","inputSchema":{"type":"object","properties":{"key_code":{"type":"integer","description":"java.awt.event.KeyEvent VK_* numeric value"}},"required":["key_code"]}},
          {"name":"set_geometry_mode","description":"Enable or disable the 3D terrain mesh mode.","inputSchema":{"type":"object","properties":{"enabled":{"type":"boolean"}},"required":["enabled"]}},
          {"name":"set_tile_bounds","description":"Show or hide white tile bounds.","inputSchema":{"type":"object","properties":{"enabled":{"type":"boolean"}},"required":["enabled"]}},
          {"name":"set_subdivision_scheme","description":"Select how terrain tile meshes are triangulated, as the Edge subdivision options of the layer panel do. Only available in the 3D terrain mode. get_camera_model reports the current scheme.","inputSchema":{"type":"object","properties":{"scheme":{"type":"string","enum":["TRIANGLE_EDGE","DIAMOND_SQUARE"]}},"required":["scheme"]}},
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
                viewer.pressKeyFromMcp(intValue(request, "key_code"));
                yield textResult("key event delivered");
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
