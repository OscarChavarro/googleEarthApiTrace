package dumpanalyzer.parser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private static final Pattern MATRIX_MODE_LINE_PATTERN = Pattern.compile("\\bglMatrixMode\\s*\\((.*)\\)");
    private static final Pattern LOAD_IDENTITY_LINE_PATTERN = Pattern.compile("\\bglLoadIdentity\\s*\\((.*)\\)");
    private static final Pattern LOAD_MATRIXF_LINE_PATTERN = Pattern.compile("\\bglLoadMatrixf\\s*\\((.*)\\)");
    private static final Pattern TEXTURE_VALUE_PATTERN = Pattern.compile("texture\\s*=\\s*(\\d+)");
    private static final Pattern MATRIX_MODE_VALUE_PATTERN = Pattern.compile("mode\\s*=\\s*([A-Z0-9_]+)");
    private static final Pattern MATRIX_VALUES_PATTERN = Pattern.compile("m\\s*=\\s*\\{([^}]*)\\}");
    private static final Pattern ATTRIB_INDEX_PATTERN = Pattern.compile("index\\s*=\\s*(\\d+)");
    private static final Pattern ATTRIB_SIZE_PATTERN = Pattern.compile("size\\s*=\\s*(\\d+)");
    private static final Pattern ATTRIB_STRIDE_PATTERN = Pattern.compile("stride\\s*=\\s*(\\d+)");
    private static final Pattern ATTRIB_TYPE_PATTERN = Pattern.compile("type\\s*=\\s*([A-Z0-9_]+)");
    private static final Pattern ATTRIB_POINTER_BLOB_PATTERN = Pattern.compile("pointer\\s*=\\s*blob\\(\\d+\\)");
    private static final Pattern DRAW_MODE_PATTERN = Pattern.compile("mode\\s*=\\s*([A-Z0-9_]+)");
    private static final Pattern DRAW_COUNT_PATTERN = Pattern.compile("count\\s*=\\s*(\\d+)");
    private static final Pattern DRAW_TYPE_PATTERN = Pattern.compile("type\\s*=\\s*([A-Z0-9_]+)");
    private static final Pattern GL_CALL_NUMBER_PATTERN = Pattern.compile("^\\s*(\\d+)\\s+gl[A-Za-z0-9_]+\\s*\\(");

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
        return new Frame(frameId, processFrameCalls(frameId, normalizedContent, frameDirectory));
    }

    private static List<TileInstance> processFrameCalls(int frameId, String normalizedContent, Path frameDirectory) {
        Map<Integer, TileBuilder> tilesByTextureId = new HashMap<>();
        ManifestIndex manifest = loadManifestIndex(frameDirectory);
        String[] lines = normalizedContent.split("\\n");
        int drawCount = 0;
        int vertexAttribExportCallCount = 0;
        int lastTextureId = -1;
        Long currentPositionVertexAttribCall = null;
        int currentVertexSize = 3;
        int currentVertexStride = 0;
        Long currentTexCoordVertexAttribCall = null;
        int currentTexCoordSize = 2;
        int currentTexCoordStride = 0;
        boolean textureMatrixMode = false;
        double[] currentTextureMatrix = identityTextureMatrix();

        for (String line : lines) {
            Matcher matrixModeMatcher = MATRIX_MODE_LINE_PATTERN.matcher(line);
            if (matrixModeMatcher.find()) {
                String mode = extractToken(MATRIX_MODE_VALUE_PATTERN, matrixModeMatcher.group(1));
                textureMatrixMode = "GL_TEXTURE".equals(mode);
                continue;
            }

            Matcher loadIdentityMatcher = LOAD_IDENTITY_LINE_PATTERN.matcher(line);
            if (loadIdentityMatcher.find()) {
                if (textureMatrixMode) {
                    currentTextureMatrix = identityTextureMatrix();
                }
                continue;
            }

            Matcher loadMatrixMatcher = LOAD_MATRIXF_LINE_PATTERN.matcher(line);
            if (loadMatrixMatcher.find()) {
                if (textureMatrixMode) {
                    double[] parsed = parseMatrix4(loadMatrixMatcher.group(1));
                    if (parsed != null) {
                        currentTextureMatrix = parsed;
                    }
                }
                continue;
            }

            Matcher bindMatcher = BIND_TEXTURE_LINE_PATTERN.matcher(line);
            if (bindMatcher.find()) {
                Integer textureId = extractInt(TEXTURE_VALUE_PATTERN, bindMatcher.group(1));
                if (textureId != null) {
                    lastTextureId = textureId;
                }
                continue;
            }

            Matcher attribMatcher = VERTEX_ATTRIB_LINE_PATTERN.matcher(line);
            if (attribMatcher.find()) {
                String args = attribMatcher.group(1);
                long attribGlCall = extractGlCallNumber(line);

                Integer index = extractInt(ATTRIB_INDEX_PATTERN, args);
                Integer size = extractInt(ATTRIB_SIZE_PATTERN, args);
                Integer stride = extractInt(ATTRIB_STRIDE_PATTERN, args);
                String type = extractToken(ATTRIB_TYPE_PATTERN, args);
                boolean hasBlobPointer = ATTRIB_POINTER_BLOB_PATTERN.matcher(args).find();
                if (index != null && index == 0 && "GL_FLOAT".equals(type) && size != null && size >= 3 && hasBlobPointer) {
                    vertexAttribExportCallCount++;
                    currentPositionVertexAttribCall = attribGlCall >= 0 ? attribGlCall : null;
                    currentVertexSize = size;
                    currentVertexStride = stride == null ? 0 : stride;
                }
                if (index != null && index == 3 && "GL_FLOAT".equals(type) && size != null && size >= 2 && hasBlobPointer) {
                    currentTexCoordVertexAttribCall = attribGlCall >= 0 ? attribGlCall : null;
                    currentTexCoordSize = size;
                    currentTexCoordStride = stride == null ? 0 : stride;
                }
                continue;
            }

            Matcher drawMatcher = DRAW_ELEMENTS_LINE_PATTERN.matcher(line);
            if (!drawMatcher.find()) {
                continue;
            }

            drawCount++;
            long glCallNumber = extractGlCallNumber(line);
            if (lastTextureId < 0) {
                continue;
            }
            int tileNumber = getOrCreateTileNumber(tilesByTextureId, lastTextureId);

            String drawArgs = drawMatcher.group(1);
            String mode = extractToken(DRAW_MODE_PATTERN, drawArgs);
            Integer count = extractInt(DRAW_COUNT_PATTERN, drawArgs);
            String type = extractToken(DRAW_TYPE_PATTERN, drawArgs);
            if (mode == null || type == null || count == null) {
                continue;
            }
            if ((!GL_TRIANGLE_STRIP.equals(mode) && !GL_TRIANGLES.equals(mode)) || !"GL_UNSIGNED_SHORT".equals(type) || count == null || count <= 0) {
                continue;
            }

            Path indicesPath = resolveIndicesBlobPath(frameDirectory, manifest, glCallNumber, drawCount);
            int[] drawIndices = loadUnsignedShortBlob(indicesPath, count);
            if (drawIndices == null || drawIndices.length == 0) {
                String reason = classifyBlobFailure(drawArgs.contains("indices = NULL"), indicesPath, "index");
                TileBuilder skippedBuilder = tilesByTextureId.get(lastTextureId);
                if (skippedBuilder != null) {
                    skippedBuilder.noteDraw(mode, drawCount, glCallNumber, 0, 0, true, reason);
                }
                logMissingGeometry(frameId, tileNumber, mode, drawCount, glCallNumber, reason);
                continue;
            }
            if (currentPositionVertexAttribCall == null && vertexAttribExportCallCount <= 0) {
                TileBuilder skippedBuilder = tilesByTextureId.get(lastTextureId);
                if (skippedBuilder != null) {
                    skippedBuilder.noteDraw(mode, drawCount, glCallNumber, 0, drawIndices.length, true, "null blob");
                }
                logMissingGeometry(
                    frameId,
                    tileNumber,
                    mode,
                    drawCount,
                    glCallNumber,
                    "position vertex attribute points to VBO/NULL (no blob pointer)"
                );
                continue;
            }
            Path vertexPath = resolveVertexBlobPath(frameDirectory, manifest, currentPositionVertexAttribCall, vertexAttribExportCallCount, 0);
            byte[] positionBlob = loadBlob(vertexPath);
            if (positionBlob == null || positionBlob.length == 0) {
                TileBuilder skippedBuilder = tilesByTextureId.get(lastTextureId);
                String reason = classifyBlobFailure(false, vertexPath, "vertex");
                if (skippedBuilder != null) {
                    skippedBuilder.noteDraw(mode, drawCount, glCallNumber, 0, drawIndices.length, true, reason);
                }
                logMissingGeometry(frameId, tileNumber, mode, drawCount, glCallNumber, reason);
                continue;
            }
            int vertexArraySize = estimateVertexArraySize(positionBlob, currentVertexSize, currentVertexStride);
            if (vertexArraySize <= 0) {
                TileBuilder skippedBuilder = tilesByTextureId.get(lastTextureId);
                if (skippedBuilder != null) {
                    skippedBuilder.noteDraw(mode, drawCount, glCallNumber, 0, drawIndices.length, true, "zero array length blob");
                }
                continue;
            }
            TileGeometry candidate = computeGeometry(positionBlob, currentVertexSize, currentVertexStride, drawIndices);
            if (candidate == null) {
                TileBuilder skippedBuilder = tilesByTextureId.get(lastTextureId);
                if (skippedBuilder != null) {
                    skippedBuilder.noteDraw(mode, drawCount, glCallNumber, vertexArraySize, drawIndices.length, true, "invalid geometry data");
                }
                continue;
            }
            TileBuilder builder = tilesByTextureId.get(lastTextureId);
            if (builder == null) {
                tileNumber = getOrCreateTileNumber(tilesByTextureId, lastTextureId);
                builder = tilesByTextureId.get(lastTextureId);
                if (builder == null) {
                    logMissingGeometry(frameId, tileNumber, mode, drawCount, glCallNumber, "internal tile mapping error");
                    continue;
                }
            }
            Path texCoordPath = resolveVertexBlobPath(frameDirectory, manifest, currentTexCoordVertexAttribCall, -1, 3);
            byte[] texCoordBlob = loadBlob(texCoordPath);
            List<Vector3D> baseTexCoords = computeTexCoords(texCoordBlob, currentTexCoordSize, currentTexCoordStride, drawIndices);
            List<Vector3D> transformedTexCoords = buildTexCoords(baseTexCoords, candidate.points(), candidate.min(), candidate.max(), currentTextureMatrix);

            if (GL_TRIANGLES.equals(mode)) {
                addTrianglesAsStrips(builder, candidate.points(), transformedTexCoords);
            }
            else {
                builder.addStrip(
                    candidate.points(),
                    transformedTexCoords,
                    candidate.min(),
                    candidate.max()
                );
            }
            builder.noteDraw(mode, drawCount, glCallNumber, vertexArraySize, drawIndices.length, false, "");
        }
        List<TileInstance> tiles = new ArrayList<>(tilesByTextureId.size());
        for (TileBuilder builder : tilesByTextureId.values()) {
            tiles.add(builder.build());
        }
        return tiles;
    }

    private static Path resolveIndicesBlobPath(Path frameDirectory, ManifestIndex manifest, long glCallNumber, int drawCount) {
        if (glCallNumber >= 0) {
            Path fromManifest = manifest.drawByCall.get(glCallNumber);
            if (fromManifest != null) {
                return fromManifest;
            }
            Path stableName = frameDirectory.resolve("drawElements_indices_call_" + glCallNumber + ".bin");
            if (Files.exists(stableName)) {
                return stableName;
            }
        }
        return frameDirectory.resolve("drawElements_indices_" + drawCount + ".bin");
    }

    private static Path resolveVertexBlobPath(
        Path frameDirectory,
        ManifestIndex manifest,
        Long vertexAttribCall,
        int vertexAttribExportCallCount,
        int expectedAttribIndex
    ) {
        if (vertexAttribCall != null) {
            long[] candidates = {vertexAttribCall, vertexAttribCall + 1L, vertexAttribCall - 1L};
            for (long candidateCall : candidates) {
                VertexManifestEntry fromManifest = manifest.vertexByCallAndIndex.get(vertexKey(candidateCall, expectedAttribIndex));
                if (fromManifest != null) {
                    return fromManifest.filePath();
                }
                if (expectedAttribIndex >= 0) {
                    VertexManifestEntry fromAnyIndex = manifest.vertexByCallAndIndex.get(vertexKey(candidateCall, -1));
                    if (fromAnyIndex != null) {
                        return fromAnyIndex.filePath();
                    }
                }
                Path stableName = frameDirectory.resolve("glVertexAttribPointer_vertexAttrib_call_" + candidateCall + ".bin");
                if (Files.exists(stableName)) {
                    return stableName;
                }
            }
        }
        if (vertexAttribExportCallCount < 0) {
            return frameDirectory.resolve("__missing__.bin");
        }
        return frameDirectory.resolve("glVertexAttribPointer_vertexAttrib_" + vertexAttribExportCallCount + ".bin");
    }

    private static ManifestIndex loadManifestIndex(Path frameDirectory) {
        ManifestIndex out = new ManifestIndex();
        if (frameDirectory == null) {
            return out;
        }
        for (Path dir : candidateManifestDirectories(frameDirectory)) {
            Path manifestPath = dir.resolve("manifest.txt");
            if (!Files.exists(manifestPath)) {
                continue;
            }
            List<String> lines;
            try {
                lines = Files.readAllLines(manifestPath, StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            for (String line : lines) {
                Map<String, String> kv = parseManifestLine(line);
                String kind = kv.get("kind");
                String callText = kv.get("call");
                String fileText = kv.get("file");
                if (kind == null || callText == null || fileText == null) {
                    continue;
                }
                long call;
                try {
                    call = Long.parseLong(callText);
                } catch (NumberFormatException ex) {
                    continue;
                }
                Path filePath = Paths.get(fileText);
                if (!filePath.isAbsolute()) {
                    filePath = dir.resolve(fileText);
                }
                if ("draw_elements".equals(kind)) {
                    out.drawByCall.putIfAbsent(call, filePath);
                }
                else if ("vertex_attrib".equals(kind)) {
                    Integer attribIndex = tryParseInt(kv.get("attribIndex"));
                    if (attribIndex != null) {
                        out.vertexByCallAndIndex.putIfAbsent(vertexKey(call, attribIndex), new VertexManifestEntry(filePath, attribIndex));
                        out.vertexByCallAndIndex.putIfAbsent(vertexKey(call, -1), new VertexManifestEntry(filePath, attribIndex));
                    }
                }
            }
        }
        return out;
    }

    private static List<Path> candidateManifestDirectories(Path frameDirectory) {
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        out.add(frameDirectory);
        Path parent = frameDirectory.getParent();
        if (parent == null) {
            return new ArrayList<>(out);
        }
        String name = frameDirectory.getFileName().toString();
        Integer current = tryParseInt(name);
        if (current == null) {
            return new ArrayList<>(out);
        }
        out.add(parent.resolve(String.format("%05d", current + 1)));
        if (current > 0) {
            out.add(parent.resolve(String.format("%05d", current - 1)));
        }
        return new ArrayList<>(out);
    }

    private static Map<String, String> parseManifestLine(String line) {
        Map<String, String> kv = new HashMap<>();
        if (line == null || line.isBlank()) {
            return kv;
        }
        String[] parts = line.trim().split("\\s+");
        for (String part : parts) {
            int eq = part.indexOf('=');
            if (eq <= 0 || eq >= part.length() - 1) {
                continue;
            }
            String k = part.substring(0, eq);
            String v = part.substring(eq + 1);
            kv.put(k, v);
        }
        return kv;
    }

    private static Integer tryParseInt(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String classifyBlobFailure(boolean isNullPointerInTrace, Path path, String blobKind) {
        if (isNullPointerInTrace) {
            return "null blob";
        }
        if (path == null || !Files.exists(path)) {
            return "null blob";
        }
        try {
            long size = Files.size(path);
            if (size <= 0) {
                return "zero array length blob";
            }
            if ("index".equals(blobKind) && (size % 2L) != 0L) {
                return "invalid index blob";
            }
            return "blob decode failure";
        } catch (IOException ex) {
            return "null blob";
        }
    }

    private static int getOrCreateTileNumber(Map<Integer, TileBuilder> tilesByTextureId, int textureId) {
        TileBuilder existing = tilesByTextureId.get(textureId);
        if (existing != null) {
            return existing.tileNumber();
        }
        int tileNumber = tilesByTextureId.size() + 1;
        tilesByTextureId.put(textureId, new TileBuilder(textureId, tileNumber));
        return tileNumber;
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

    private static void addTrianglesAsStrips(TileBuilder builder, List<Vector3D> points, List<Vector3D> texCoords) {
        if (builder == null || points == null || points.size() < 3) {
            return;
        }
        int triangleCount = points.size() / 3;
        for (int i = 0; i < triangleCount; i++) {
            int base = i * 3;
            List<Vector3D> triangle = List.of(
                points.get(base),
                points.get(base + 1),
                points.get(base + 2)
            );
            List<Vector3D> triangleUv = texCoords != null && texCoords.size() >= base + 3
                ? List.of(texCoords.get(base), texCoords.get(base + 1), texCoords.get(base + 2))
                : List.of();
            Vector3D min = minOf(triangle);
            Vector3D max = maxOf(triangle);
            builder.addStrip(
                triangle,
                triangleUv,
                min,
                max
            );
        }
    }

    private static List<Vector3D> buildTexCoords(
        List<Vector3D> baseTexCoords,
        List<Vector3D> points,
        Vector3D min,
        Vector3D max,
        double[] textureMatrix
    ) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        boolean useBase = baseTexCoords != null && baseTexCoords.size() == points.size();
        double dx = Math.max(1.0e-9, max.x() - min.x());
        double dy = Math.max(1.0e-9, max.y() - min.y());
        double[] m = textureMatrix == null ? identityTextureMatrix() : textureMatrix;
        List<Vector3D> uv = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            Vector3D p = points.get(i);
            double u0;
            double v0;
            if (useBase) {
                Vector3D t = baseTexCoords.get(i);
                u0 = t.x();
                v0 = t.y();
            }
            else {
                u0 = (p.x() - min.x()) / dx;
                v0 = (p.y() - min.y()) / dy;
            }
            double[] t = applyTextureMatrix(m, u0, v0);
            uv.add(new Vector3D(t[0], t[1], 0.0));
        }
        return uv;
    }

    private static List<Vector3D> computeTexCoords(byte[] texCoordBlob, int texCoordSize, int texCoordStride, int[] indices) {
        if (texCoordBlob == null || texCoordBlob.length == 0 || texCoordSize < 2 || indices == null || indices.length == 0) {
            return List.of();
        }
        int minStride = texCoordSize * 4;
        int strideBytes = texCoordStride > 0 ? texCoordStride : minStride;
        if (strideBytes < minStride || texCoordBlob.length < minStride) {
            return List.of();
        }
        int vertexCount = texCoordBlob.length / strideBytes;
        if (vertexCount <= 0) {
            return List.of();
        }
        ByteBuffer bb = ByteBuffer.wrap(texCoordBlob).order(ByteOrder.LITTLE_ENDIAN);
        List<Vector3D> out = new ArrayList<>(indices.length);
        for (int index : indices) {
            if (index < 0 || index >= vertexCount) {
                out.add(new Vector3D(0.0, 0.0, 0.0));
                continue;
            }
            int baseBytes = index * strideBytes;
            if (baseBytes + 8 > texCoordBlob.length) {
                out.add(new Vector3D(0.0, 0.0, 0.0));
                continue;
            }
            double u = bb.getFloat(baseBytes);
            double v = bb.getFloat(baseBytes + 4);
            out.add(new Vector3D(u, v, 0.0));
        }
        return out;
    }

    private static double[] applyTextureMatrix(double[] m, double u, double v) {
        double x = m[0] * u + m[4] * v + m[8] * 0.0 + m[12] * 1.0;
        double y = m[1] * u + m[5] * v + m[9] * 0.0 + m[13] * 1.0;
        double w = m[3] * u + m[7] * v + m[11] * 0.0 + m[15] * 1.0;
        if (Math.abs(w) > 1.0e-12) {
            return new double[] { x / w, y / w };
        }
        return new double[] { x, y };
    }

    private static double[] parseMatrix4(String args) {
        Matcher mm = MATRIX_VALUES_PATTERN.matcher(args);
        if (!mm.find()) {
            return null;
        }
        String[] tokens = mm.group(1).split(",");
        if (tokens.length != 16) {
            return null;
        }
        double[] out = new double[16];
        for (int i = 0; i < 16; i++) {
            try {
                out[i] = Double.parseDouble(tokens[i].trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return out;
    }

    private static double[] identityTextureMatrix() {
        return new double[] {
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0
        };
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

    private static int estimateVertexArraySize(byte[] vertexBlob, int vertexSize, int vertexStride) {
        if (vertexBlob == null || vertexSize < 3) {
            return 0;
        }
        int minStride = vertexSize * 4;
        int strideBytes = vertexStride > 0 ? vertexStride : minStride;
        if (strideBytes < minStride || strideBytes <= 0) {
            return 0;
        }
        return vertexBlob.length / strideBytes;
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

    private static long extractGlCallNumber(String line) {
        Matcher matcher = GL_CALL_NUMBER_PATTERN.matcher(line);
        if (!matcher.find()) {
            return -1L;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    private static void logMissingGeometry(
        int frameId,
        int tileNumber,
        String primitive,
        int parserCallNumber,
        long glCallNumber,
        String reason
    ) {
        // Intentionally silent: per-tile diagnostics are emitted by the viewer
        // only when user changes frame/tile selection.
    }

    private record TileGeometry(Vector3D min, Vector3D max, List<Vector3D> points) {
    }

    private static final class TileBuilder {
        private final int textureId;
        private final int tileNumber;
        private final List<List<Vector3D>> strips = new ArrayList<>();
        private final List<List<Vector3D>> stripTexCoords = new ArrayList<>();
        private final List<Vector3D> flatPoints = new ArrayList<>();
        private String primitive = "n/a";
        private int parserCall = -1;
        private long glCall = -1L;
        private int vertexArraySize = 0;
        private int indexArraySize = 0;
        private boolean skipped = false;
        private String skipReason = "";
        private Vector3D min;
        private Vector3D max;

        private TileBuilder(int textureId, int tileNumber) {
            this.textureId = textureId;
            this.tileNumber = tileNumber;
        }

        private int tileNumber() {
            return tileNumber;
        }

        private void noteDraw(
            String primitive,
            int parserCall,
            long glCall,
            int vertexArraySize,
            int indexArraySize,
            boolean skipped,
            String skipReason
        ) {
            this.primitive = primitive == null ? "n/a" : primitive;
            this.parserCall = parserCall;
            this.glCall = glCall;
            this.vertexArraySize = Math.max(0, vertexArraySize);
            this.indexArraySize = Math.max(0, indexArraySize);
            // If tile already has valid geometry, keep it as non-skipped even
            // if a later primitive in the same texture bucket fails.
            if (!strips.isEmpty() || !flatPoints.isEmpty()) {
                this.skipped = false;
                this.skipReason = "";
                return;
            }
            this.skipped = skipped;
            this.skipReason = skipReason == null ? "" : skipReason;
        }

        private void addStrip(List<Vector3D> stripPoints, List<Vector3D> uvCoords, Vector3D stripMin, Vector3D stripMax) {
            if (stripPoints == null || stripPoints.isEmpty()) {
                return;
            }
            strips.add(List.copyOf(stripPoints));
            stripTexCoords.add(uvCoords == null ? List.of() : List.copyOf(uvCoords));
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
                strips,
                stripTexCoords,
                primitive,
                parserCall,
                glCall,
                vertexArraySize,
                indexArraySize,
                skipped,
                skipReason
            );
        }
    }

    private static final class ManifestIndex {
        private final Map<Long, Path> drawByCall = new HashMap<>();
        private final Map<String, VertexManifestEntry> vertexByCallAndIndex = new HashMap<>();
    }

    private record VertexManifestEntry(Path filePath, int attribIndex) {
    }

    private static String vertexKey(long call, int attribIndex) {
        return call + ":" + attribIndex;
    }
}
