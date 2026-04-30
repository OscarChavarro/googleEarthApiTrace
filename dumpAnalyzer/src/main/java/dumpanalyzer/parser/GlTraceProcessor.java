package dumpanalyzer.parser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.BlockingQueue;
import dumpanalyzer.FatalErrorHandler;
import dumpanalyzer.LogicalLineNormalizer;
import dumpanalyzer.model.Frame;
import dumpanalyzer.model.TileInstance;
import vsdk.toolkit.common.linealAlgebra.Vector3D;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

public class GlTraceProcessor {
    private static final String GL_TRIANGLE_STRIP = "GL_TRIANGLE_STRIP";
    private static final String GL_TRIANGLES = "GL_TRIANGLES";
    private static final Pattern BIND_TEXTURE_LINE_PATTERN = Pattern.compile("\\bglBindTexture\\s*\\((.*)\\)");
    private static final Pattern VERTEX_ATTRIB_LINE_PATTERN = Pattern.compile("\\bglVertexAttribPointer\\s*\\((.*)\\)");
    private static final Pattern DRAW_ELEMENTS_LINE_PATTERN = Pattern.compile("\\bglDrawElements\\s*\\((.*)\\)");
    private static final Pattern TEXTURE_VALUE_PATTERN = Pattern.compile("texture\\s*=\\s*(\\d+)");
    private static final Pattern ATTRIB_INDEX_PATTERN = Pattern.compile("index\\s*=\\s*(\\d+)");
    private static final Pattern ATTRIB_SIZE_PATTERN = Pattern.compile("size\\s*=\\s*(\\d+)");
    private static final Pattern ATTRIB_STRIDE_PATTERN = Pattern.compile("stride\\s*=\\s*(\\d+)");
    private static final Pattern ATTRIB_TYPE_PATTERN = Pattern.compile("type\\s*=\\s*([A-Z0-9_]+)");
    private static final Pattern ATTRIB_POINTER_BLOB_PATTERN = Pattern.compile("pointer\\s*=\\s*blob\\(\\d+\\)");
    private static final Pattern DRAW_MODE_PATTERN = Pattern.compile("mode\\s*=\\s*([A-Z0-9_]+)");
    private static final Pattern DRAW_COUNT_PATTERN = Pattern.compile("count\\s*=\\s*(\\d+)");
    private static final Pattern DRAW_TYPE_PATTERN = Pattern.compile("type\\s*=\\s*([A-Z0-9_]+)");

    private final FunctionCounter functionCounter;

    public GlTraceProcessor(FunctionCounter functionCounter) {
        this.functionCounter = functionCounter;
    }

    public Frame processFrame(int frame, String filename, BlockingQueue<String> logQueue) {
        enqueueLog(logQueue, "Processing frame " + frame + " from file " + filename);
        Path filePath = Paths.get(filename).toAbsolutePath();

        String content;
        try {
            content = Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            FatalErrorHandler.fail(filePath, "Cannot read file: " + e.getMessage());
            return new Frame(frame, List.of());
        }

        String normalized = LogicalLineNormalizer.normalize(content);
        parseOrFail(filePath, normalized);
        functionCounter.addFromContent(normalized);

        return buildFrameFromGlTrace(frame, normalized, filePath.getParent());
    }

    private static Frame buildFrameFromGlTrace(int frameId, String normalizedContent, Path frameDirectory) {
        return new Frame(frameId, processFrameCalls(normalizedContent, frameDirectory));
    }

    private static List<TileInstance> processFrameCalls(String normalizedContent, Path frameDirectory) {
        Map<Integer, TileBuilder> tilesByTextureId = new HashMap<>();
        Set<String> warned = new HashSet<>();
        String[] lines = normalizedContent.split("\\n");
        int drawCount = 0;
        int vertexAttribCallCount = 0;
        int lastTextureId = -1;
        Integer currentPositionVertexAttribId = null;
        int currentVertexSize = 3;
        int currentVertexStride = 0;

        for (String line : lines) {
            Matcher bindMatcher = BIND_TEXTURE_LINE_PATTERN.matcher(line);
            if (bindMatcher.find()) {
                Integer textureId = extractInt(TEXTURE_VALUE_PATTERN, bindMatcher.group(1));
                if (textureId != null) {
                    lastTextureId = textureId;
                }
                else {
                    warnOnce(warned, "Malformed glBindTexture call. Texture id missing.");
                }
                continue;
            }

            Matcher attribMatcher = VERTEX_ATTRIB_LINE_PATTERN.matcher(line);
            if (attribMatcher.find()) {
                vertexAttribCallCount++;
                String args = attribMatcher.group(1);

                Integer index = extractInt(ATTRIB_INDEX_PATTERN, args);
                Integer size = extractInt(ATTRIB_SIZE_PATTERN, args);
                Integer stride = extractInt(ATTRIB_STRIDE_PATTERN, args);
                String type = extractToken(ATTRIB_TYPE_PATTERN, args);
                boolean hasBlobPointer = ATTRIB_POINTER_BLOB_PATTERN.matcher(args).find();
                if (index != null && index == 0 && "GL_FLOAT".equals(type) && size != null && size >= 3 && hasBlobPointer) {
                    currentPositionVertexAttribId = vertexAttribCallCount;
                    currentVertexSize = size;
                    currentVertexStride = stride == null ? 0 : stride;
                }
                continue;
            }

            Matcher drawMatcher = DRAW_ELEMENTS_LINE_PATTERN.matcher(line);
            if (!drawMatcher.find()) {
                continue;
            }

            drawCount++;
            if (lastTextureId < 0) {
                warnOnce(warned, "Skipping draw: no bound texture id.");
                continue;
            }

            String drawArgs = drawMatcher.group(1);
            String mode = extractToken(DRAW_MODE_PATTERN, drawArgs);
            Integer count = extractInt(DRAW_COUNT_PATTERN, drawArgs);
            String type = extractToken(DRAW_TYPE_PATTERN, drawArgs);
            if (mode == null || type == null || count == null) {
                warnOnce(warned, "Skipping draw: malformed glDrawElements arguments.");
                continue;
            }
            if ((!GL_TRIANGLE_STRIP.equals(mode) && !GL_TRIANGLES.equals(mode)) || !"GL_UNSIGNED_SHORT".equals(type) || count == null || count <= 0) {
                if (!GL_TRIANGLE_STRIP.equals(mode) && !GL_TRIANGLES.equals(mode)) {
                    warnOnce(warned, "Skipping draw: unsupported draw mode " + mode + ".");
                }
                else if (!"GL_UNSIGNED_SHORT".equals(type)) {
                    warnOnce(warned, "Skipping draw: unsupported index type " + type + ".");
                }
                else {
                    warnOnce(warned, "Skipping draw: invalid index count.");
                }
                continue;
            }

            Path indicesPath = frameDirectory.resolve("drawElements_indices_" + drawCount + ".bin");
            int[] drawIndices = loadUnsignedShortBlob(indicesPath, count);
            if (drawIndices == null || drawIndices.length == 0) {
                if (drawArgs.contains("indices = NULL")) {
                    warnOnce(warned, "Skipping draw: indices come from element buffer and blob is unavailable.");
                }
                else {
                    warnOnce(warned, "Skipping draw: missing index blob " + indicesPath.getFileName() + ".");
                }
                continue;
            }
            if (currentPositionVertexAttribId == null) {
                warnOnce(warned, "Skipping draw: position vertex attribute is unavailable.");
                continue;
            }
            Path vertexPath = frameDirectory.resolve("glVertexAttribPointer_vertexAttrib_" + currentPositionVertexAttribId + ".bin");
            byte[] positionBlob = loadBlob(vertexPath);
            if (positionBlob == null || positionBlob.length == 0) {
                warnOnce(warned, "Skipping draw: missing vertex blob " + vertexPath.getFileName() + ".");
                continue;
            }
            TileGeometry candidate = computeGeometry(positionBlob, currentVertexSize, currentVertexStride, drawIndices);
            if (candidate == null) {
                warnOnce(warned, "Skipping draw: invalid geometry data.");
                continue;
            }
            TileBuilder builder = tilesByTextureId.computeIfAbsent(lastTextureId, TileBuilder::new);
            if (GL_TRIANGLES.equals(mode)) {
                addTrianglesAsStrips(builder, candidate.points(), warned);
            }
            else {
                builder.addStrip(candidate.points(), candidate.min(), candidate.max());
            }
        }
        List<TileInstance> tiles = new ArrayList<>(tilesByTextureId.size());
        for (TileBuilder builder : tilesByTextureId.values()) {
            tiles.add(builder.build());
        }
        return tiles;
    }

    private static Integer extractInt(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find()) return null;
        return Integer.parseInt(matcher.group(1));
    }

    private static String extractToken(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find()) return null;
        return matcher.group(1);
    }

    private static byte[] loadBlob(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            return null;
        }
    }

    private static TileGeometry computeGeometry(byte[] vertexBlob, int vertexSize, int vertexStride, int[] indices) {
        if (vertexSize < 3 || vertexBlob == null || indices == null || indices.length == 0) return null;
        int minStride = vertexSize * 4;
        int strideBytes = vertexStride > 0 ? vertexStride : minStride;
        if (strideBytes < minStride) return null;
        if (vertexBlob.length < minStride) return null;
        int vertexCount = vertexBlob.length / strideBytes;
        if (vertexCount <= 0) return null;
        ByteBuffer bb = ByteBuffer.wrap(vertexBlob).order(ByteOrder.LITTLE_ENDIAN);

        boolean hasAny = false;
        double minX = 0, minY = 0, minZ = 0, maxX = 0, maxY = 0, maxZ = 0;
        List<Vector3D> points = new ArrayList<>();

        for (int index : indices) {
            if (index < 0 || index >= vertexCount) continue;
            int baseBytes = index * strideBytes;
            if (baseBytes + 12 > vertexBlob.length) continue;
            double x = bb.getFloat(baseBytes);
            double y = bb.getFloat(baseBytes + 4);
            double z = bb.getFloat(baseBytes + 8);
            points.add(new Vector3D(x, y, z));

            if (!hasAny) {
                minX = maxX = x;
                minY = maxY = y;
                minZ = maxZ = z;
                hasAny = true;
                continue;
            }

            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        if (!hasAny) return null;
        return new TileGeometry(new Vector3D(minX, minY, minZ), new Vector3D(maxX, maxY, maxZ), points);
    }

    private static void addTrianglesAsStrips(TileBuilder builder, List<Vector3D> points, Set<String> warned) {
        if (builder == null || points == null || points.size() < 3) {
            return;
        }
        if ((points.size() % 3) != 0) {
            warnOnce(warned, "GL_TRIANGLES point list is not divisible by 3. Truncating trailing points.");
        }
        int triangleCount = points.size() / 3;
        for (int i = 0; i < triangleCount; i++) {
            int base = i * 3;
            List<Vector3D> triangle = List.of(
                points.get(base),
                points.get(base + 1),
                points.get(base + 2)
            );
            Vector3D min = minOf(triangle);
            Vector3D max = maxOf(triangle);
            builder.addStrip(triangle, min, max);
        }
    }

    private static Vector3D minOf(List<Vector3D> points) {
        Vector3D p0 = points.get(0);
        double minX = p0.x();
        double minY = p0.y();
        double minZ = p0.z();
        for (int i = 1; i < points.size(); i++) {
            Vector3D p = points.get(i);
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            minZ = Math.min(minZ, p.z());
        }
        return new Vector3D(minX, minY, minZ);
    }

    private static Vector3D maxOf(List<Vector3D> points) {
        Vector3D p0 = points.get(0);
        double maxX = p0.x();
        double maxY = p0.y();
        double maxZ = p0.z();
        for (int i = 1; i < points.size(); i++) {
            Vector3D p = points.get(i);
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
            maxZ = Math.max(maxZ, p.z());
        }
        return new Vector3D(maxX, maxY, maxZ);
    }

    private static int[] loadUnsignedShortBlob(Path path, int maxCount) {
        byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (IOException e) {
            return null;
        }
        if (data.length < 2) {
            return null;
        }
        int available = data.length / 2;
        int count = Math.min(Math.max(maxCount, 0), available);
        int[] out = new int[count];
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            out[i] = bb.getShort() & 0xFFFF;
        }
        return out;
    }

    private static void enqueueLog(BlockingQueue<String> logQueue, String message) {
        try {
            logQueue.put(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FatalErrorHandler.fail(Path.of("/tmp/output"), "Interrupted while queuing log message");
        }
    }

    private static void parseOrFail(Path filePath, String normalized) {
        GlTraceLexer lexer = new GlTraceLexer(CharStreams.fromString(normalized));
        GlTraceParser parser = new GlTraceParser(new CommonTokenStream(lexer));

        BaseErrorListener fatalListener = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
                FatalErrorHandler.fail(filePath, "line " + line + ":" + charPositionInLine + " " + msg);
            }
        };

        lexer.removeErrorListeners();
        parser.removeErrorListeners();
        lexer.addErrorListener(fatalListener);
        parser.addErrorListener(fatalListener);
        parser.traceFile();
    }

    private static void warnOnce(Set<String> warned, String message) {
        if (warned.add(message)) {
            System.out.println("[dumpAnalyzer] " + message);
        }
    }

    private record TileGeometry(Vector3D min, Vector3D max, List<Vector3D> points) {
    }

    private static final class TileBuilder {
        private final int textureId;
        private final List<List<Vector3D>> strips = new ArrayList<>();
        private final List<Vector3D> flatPoints = new ArrayList<>();
        private Vector3D min;
        private Vector3D max;

        private TileBuilder(int textureId) {
            this.textureId = textureId;
        }

        private void addStrip(List<Vector3D> stripPoints, Vector3D stripMin, Vector3D stripMax) {
            if (stripPoints == null || stripPoints.isEmpty()) {
                return;
            }
            strips.add(List.copyOf(stripPoints));
            flatPoints.addAll(stripPoints);

            if (min == null || max == null) {
                min = Vector3D.copyOf(stripMin);
                max = Vector3D.copyOf(stripMax);
                return;
            }

            min = new Vector3D(
                Math.min(min.x(), stripMin.x()),
                Math.min(min.y(), stripMin.y()),
                Math.min(min.z(), stripMin.z())
            );
            max = new Vector3D(
                Math.max(max.x(), stripMax.x()),
                Math.max(max.y(), stripMax.y()),
                Math.max(max.z(), stripMax.z())
            );
        }

        private TileInstance build() {
            return new TileInstance(
                textureId,
                null,
                null,
                null,
                null,
                min,
                max,
                flatPoints,
                strips
            );
        }
    }
}
