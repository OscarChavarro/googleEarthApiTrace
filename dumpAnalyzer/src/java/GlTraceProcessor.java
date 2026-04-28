package dumpanalyzer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.BlockingQueue;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

public class GlTraceProcessor {
    private static final Pattern BIND_TEXTURE_PATTERN = Pattern.compile(
        "\\bglBindTexture\\s*\\(\\s*[^,]+,\\s*(\\d+)\\s*\\)"
    );
    private static final Pattern BIND_TEXTURE_LINE_PATTERN = Pattern.compile("\\bglBindTexture\\s*\\((.*)\\)");
    private static final Pattern VERTEX_ATTRIB_LINE_PATTERN = Pattern.compile("\\bglVertexAttribPointer\\s*\\((.*)\\)");
    private static final Pattern DRAW_ELEMENTS_LINE_PATTERN = Pattern.compile("\\bglDrawElements\\s*\\((.*)\\)");
    private static final Pattern TEXTURE_VALUE_PATTERN = Pattern.compile("texture\\s*=\\s*(\\d+)");
    private static final Pattern ATTRIB_INDEX_PATTERN = Pattern.compile("index\\s*=\\s*(\\d+)");
    private static final Pattern ATTRIB_SIZE_PATTERN = Pattern.compile("size\\s*=\\s*(\\d+)");
    private static final Pattern ATTRIB_TYPE_PATTERN = Pattern.compile("type\\s*=\\s*([A-Z0-9_]+)");
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
            return new Frame(frame);
        }

        String normalized = LogicalLineNormalizer.normalize(content);
        parseOrFail(filePath, normalized);
        functionCounter.addFromContent(normalized);

        return buildFrameFromGlTrace(frame, normalized, filePath.getParent());
    }

    private static Frame buildFrameFromGlTrace(int frameId, String normalizedContent, Path frameDirectory) {
        Frame frame = new Frame(frameId);
        Set<Integer> ids = new LinkedHashSet<>();
        Matcher matcher = BIND_TEXTURE_PATTERN.matcher(normalizedContent);

        while (matcher.find()) {
            ids.add(Integer.parseInt(matcher.group(1)));
        }

        for (Integer contentId : ids) {
            frame.getOrCreateTile(contentId);
        }

        processFrameCalls(frame, normalizedContent, frameDirectory);

        frame.sortTilesByContentId();
        return frame;
    }

    private static void processFrameCalls(Frame frame, String normalizedContent, Path frameDirectory) {
        String[] lines = normalizedContent.split("\\n");
        int definitionCount = 0;
        int drawCount = 0;
        int lastTextureId = -1;
        float[] currentPositionVertices = null;
        int currentVertexSize = 3;
        Map<Integer, float[]> loadedVertexBlobsByDefinitionId = new HashMap<>();

        for (String line : lines) {
            Matcher bindMatcher = BIND_TEXTURE_LINE_PATTERN.matcher(line);
            if (bindMatcher.find()) {
                Integer textureId = extractInt(TEXTURE_VALUE_PATTERN, bindMatcher.group(1));
                if (textureId != null) {
                    lastTextureId = textureId;
                    frame.getOrCreateTile(textureId);
                }
                continue;
            }

            Matcher attribMatcher = VERTEX_ATTRIB_LINE_PATTERN.matcher(line);
            if (attribMatcher.find()) {
                definitionCount++;
                String args = attribMatcher.group(1);

                float[] blobVertices = loadedVertexBlobsByDefinitionId.computeIfAbsent(
                    definitionCount,
                    id -> loadFloatBlob(frameDirectory.resolve("drawElements_" + id + ".bin"))
                );

                Integer index = extractInt(ATTRIB_INDEX_PATTERN, args);
                Integer size = extractInt(ATTRIB_SIZE_PATTERN, args);
                String type = extractToken(ATTRIB_TYPE_PATTERN, args);
                if (index != null && index == 0 && "GL_FLOAT".equals(type) && size != null && size >= 3 && blobVertices != null) {
                    currentPositionVertices = blobVertices;
                    currentVertexSize = size;
                }
                continue;
            }

            Matcher drawMatcher = DRAW_ELEMENTS_LINE_PATTERN.matcher(line);
            if (!drawMatcher.find()) {
                continue;
            }

            drawCount++;
            if (lastTextureId < 0 || currentPositionVertices == null) {
                continue;
            }

            String args = drawMatcher.group(1);
            Integer count = extractInt(DRAW_COUNT_PATTERN, args);
            String type = extractToken(DRAW_TYPE_PATTERN, args);
            if (count == null || count <= 0 || type == null) {
                continue;
            }

            Path drawBlobPath = frameDirectory.resolve("drawElements_" + drawCount + ".bin");
            int[] vertexIndices = loadIndices(drawBlobPath, type, count);
            if (vertexIndices.length == 0) {
                continue;
            }

            Bounds bounds = computeBounds(currentPositionVertices, currentVertexSize, vertexIndices);
            if (bounds == null) {
                continue;
            }

            TileInstance tile = frame.getOrCreateTile(lastTextureId);
            tile.mergeBounds(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
        }
    }

    private static Integer extractInt(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find()) {
            return null;
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static String extractToken(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }

    private static float[] loadFloatBlob(Path path) {
        byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (IOException e) {
            return null;
        }

        if (data.length < 12) {
            return null;
        }

        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[data.length / 4];
        for (int i = 0; i < out.length; i++) {
            out[i] = bb.getFloat();
        }
        return out;
    }

    private static int[] loadIndices(Path path, String glType, int requestedCount) {
        byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (IOException e) {
            return new int[0];
        }

        if ("GL_UNSIGNED_SHORT".equals(glType)) {
            int available = data.length / 2;
            int count = Math.min(requestedCount, available);
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int[] out = new int[count];
            for (int i = 0; i < count; i++) {
                out[i] = Short.toUnsignedInt(bb.getShort());
            }
            return out;
        }

        if ("GL_UNSIGNED_INT".equals(glType)) {
            int available = data.length / 4;
            int count = Math.min(requestedCount, available);
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int[] out = new int[count];
            for (int i = 0; i < count; i++) {
                out[i] = bb.getInt();
            }
            return out;
        }

        if ("GL_UNSIGNED_BYTE".equals(glType)) {
            int count = Math.min(requestedCount, data.length);
            int[] out = new int[count];
            for (int i = 0; i < count; i++) {
                out[i] = Byte.toUnsignedInt(data[i]);
            }
            return out;
        }

        return new int[0];
    }

    private static Bounds computeBounds(float[] vertices, int vertexSize, int[] indices) {
        if (vertexSize < 3) {
            return null;
        }

        int vertexCount = vertices.length / vertexSize;
        if (vertexCount <= 0) {
            return null;
        }

        boolean hasAny = false;
        double minX = 0;
        double minY = 0;
        double minZ = 0;
        double maxX = 0;
        double maxY = 0;
        double maxZ = 0;

        for (int index : indices) {
            if (index < 0 || index >= vertexCount) {
                continue;
            }

            int base = index * vertexSize;
            double x = vertices[base];
            double y = vertices[base + 1];
            double z = vertices[base + 2];

            if (!hasAny) {
                minX = x;
                minY = y;
                minZ = z;
                maxX = x;
                maxY = y;
                maxZ = z;
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

        if (!hasAny) {
            return null;
        }
        return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
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
            public void syntaxError(
                Recognizer<?, ?> recognizer,
                Object offendingSymbol,
                int line,
                int charPositionInLine,
                String msg,
                RecognitionException e
            ) {
                FatalErrorHandler.fail(filePath, "line " + line + ":" + charPositionInLine + " " + msg);
            }
        };

        lexer.removeErrorListeners();
        parser.removeErrorListeners();
        lexer.addErrorListener(fatalListener);
        parser.addErrorListener(fatalListener);

        parser.traceFile();
    }
}
