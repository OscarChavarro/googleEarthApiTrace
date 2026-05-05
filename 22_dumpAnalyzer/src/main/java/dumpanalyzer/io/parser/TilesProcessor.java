package dumpanalyzer.io.parser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dumpanalyzer.model.Line;
import dumpanalyzer.model.TileInstance;
import vsdk.toolkit.common.linealAlgebra.Vector3D;

final class TilesProcessor {
    private static final String GL_TRIANGLE_STRIP = "GL_TRIANGLE_STRIP";
    private static final String GL_TRIANGLES = "GL_TRIANGLES";
    private static final String GL_LINES = "GL_LINES";
    private static final String GL_LINE_STRIP = "GL_LINE_STRIP";
    private static final String GL_LINE_LOOP = "GL_LINE_LOOP";
    private static final Pattern BIND_TEXTURE_LINE_PATTERN = Pattern.compile("\\bglBindTexture\\s*\\((.*)\\)");
    private static final Pattern ACTIVE_TEXTURE_LINE_PATTERN = Pattern.compile("\\bglActiveTextureARB\\s*\\((.*)\\)");
    private static final Pattern VERTEX_ATTRIB_LINE_PATTERN = Pattern.compile("\\bglVertexAttribPointer\\s*\\((.*)\\)");
    private static final Pattern DRAW_ELEMENTS_LINE_PATTERN = Pattern.compile("\\bglDrawElements\\s*\\((.*)\\)");
    private static final Pattern DRAW_ARRAYS_LINE_PATTERN = Pattern.compile("\\bglDrawArrays\\s*\\((.*)\\)");
    private static final Pattern MATRIX_MODE_LINE_PATTERN = Pattern.compile("\\bglMatrixMode\\s*\\((.*)\\)");
    private static final Pattern LOAD_IDENTITY_LINE_PATTERN = Pattern.compile("\\bglLoadIdentity\\s*\\((.*)\\)");
    private static final Pattern LOAD_MATRIXF_LINE_PATTERN = Pattern.compile("\\bglLoadMatrixf\\s*\\((.*)\\)");
    private static final Pattern TEXTURE_VALUE_PATTERN = Pattern.compile("texture\\s*=\\s*(\\d+)");
    private static final Pattern TEXTURE_TARGET_PATTERN = Pattern.compile("target\\s*=\\s*([A-Z0-9_]+)");
    private static final Pattern ACTIVE_TEXTURE_VALUE_PATTERN = Pattern.compile("texture\\s*=\\s*GL_TEXTURE(\\d+)");
    private static final Pattern MATRIX_MODE_VALUE_PATTERN = Pattern.compile("mode\\s*=\\s*([A-Z0-9_]+)");
    private static final Pattern ATTRIB_INDEX_PATTERN = Pattern.compile("index\\s*=\\s*(\\d+)");
    private static final Pattern ATTRIB_SIZE_PATTERN = Pattern.compile("size\\s*=\\s*(\\d+)");
    private static final Pattern ATTRIB_STRIDE_PATTERN = Pattern.compile("stride\\s*=\\s*(\\d+)");
    private static final Pattern ATTRIB_TYPE_PATTERN = Pattern.compile("type\\s*=\\s*([A-Z0-9_]+)");
    private static final Pattern ATTRIB_POINTER_BLOB_PATTERN = Pattern.compile("pointer\\s*=\\s*blob\\(\\d+\\)");
    private static final Pattern DRAW_MODE_PATTERN = Pattern.compile("mode\\s*=\\s*([A-Z0-9_]+)");
    private static final Pattern DRAW_COUNT_PATTERN = Pattern.compile("count\\s*=\\s*(\\d+)");
    private static final Pattern DRAW_TYPE_PATTERN = Pattern.compile("type\\s*=\\s*([A-Z0-9_]+)");
    private static final Pattern DRAW_FIRST_PATTERN = Pattern.compile("first\\s*=\\s*(\\d+)");
    private static final Pattern GL_CALL_NUMBER_PATTERN = Pattern.compile("^\\s*(\\d+)\\s+gl[A-Za-z0-9_]+\\s*\\(");

    private TilesProcessor() {
    }

    static FrameGeometry processFrameCalls(int frameId, String normalizedContent, Path frameDirectory) {
        Map<TileKey, TileBuilder> tilesByKey = new LinkedHashMap<>();
        List<Line> frameLines = new ArrayList<>();
        ManifestProcessor.ManifestIndex manifest = ManifestProcessor.loadManifestIndex(frameDirectory);
        String[] lines = normalizedContent.split("\\n");
        int drawCount = 0;
        int drawElementsCount = 0;
        int lineDrawsSeen = 0;
        int lineDrawsWithIndices = 0;
        int lineDrawsWithGeometry = 0;
        int vertexAttribExportCallCount = 0;
        int activeTextureUnit = 0;
        Map<Integer, Integer> boundTexture2DByUnit = new HashMap<>();
        Long currentPositionVertexAttribCall = null;
        int currentVertexSize = 3;
        int currentVertexStride = 0;
        Long currentTexCoordVertexAttribCall = null;
        int currentTexCoordSize = 2;
        int currentTexCoordStride = 0;
        boolean textureMatrixMode = false;
        double[] currentTextureMatrix = TextureProcessor.identityTextureMatrix();
        boolean projectionMatrixMode = false;
        boolean modelViewMatrixMode = false;
        double[] currentProjectionMatrix = null;
        double[] currentModelViewMatrix = null;

        for (String line : lines) {
            Matcher matrixModeMatcher = MATRIX_MODE_LINE_PATTERN.matcher(line);
            if (matrixModeMatcher.find()) {
                String mode = extractToken(MATRIX_MODE_VALUE_PATTERN, matrixModeMatcher.group(1));
                textureMatrixMode = "GL_TEXTURE".equals(mode);
                projectionMatrixMode = "GL_PROJECTION".equals(mode);
                modelViewMatrixMode = "GL_MODELVIEW".equals(mode);
                continue;
            }

            Matcher loadIdentityMatcher = LOAD_IDENTITY_LINE_PATTERN.matcher(line);
            if (loadIdentityMatcher.find()) {
                if (textureMatrixMode) {
                    currentTextureMatrix = TextureProcessor.identityTextureMatrix();
                }
                continue;
            }

            Matcher loadMatrixMatcher = LOAD_MATRIXF_LINE_PATTERN.matcher(line);
            if (loadMatrixMatcher.find()) {
                if (textureMatrixMode) {
                    double[] parsed = TextureProcessor.parseMatrix4(loadMatrixMatcher.group(1));
                    if (parsed != null) {
                        currentTextureMatrix = parsed;
                    }
                }
                if (projectionMatrixMode) {
                    double[] parsed = TextureProcessor.parseMatrix4(loadMatrixMatcher.group(1));
                    if (parsed != null) {
                        currentProjectionMatrix = parsed;
                    }
                }
                if (modelViewMatrixMode) {
                    double[] parsed = TextureProcessor.parseMatrix4(loadMatrixMatcher.group(1));
                    if (parsed != null) {
                        currentModelViewMatrix = parsed;
                    }
                }
                continue;
            }

            Matcher bindMatcher = BIND_TEXTURE_LINE_PATTERN.matcher(line);
            Matcher activeTextureMatcher = ACTIVE_TEXTURE_LINE_PATTERN.matcher(line);
            if (activeTextureMatcher.find()) {
                Integer unit = extractInt(ACTIVE_TEXTURE_VALUE_PATTERN, activeTextureMatcher.group(1));
                if (unit != null && unit >= 0) {
                    activeTextureUnit = unit;
                }
                continue;
            }
            if (bindMatcher.find()) {
                String target = extractToken(TEXTURE_TARGET_PATTERN, bindMatcher.group(1));
                Integer textureId = extractInt(TEXTURE_VALUE_PATTERN, bindMatcher.group(1));
                if ("GL_TEXTURE_2D".equals(target) && textureId != null) {
                    boundTexture2DByUnit.put(activeTextureUnit, textureId);
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
            Matcher drawArraysMatcher = DRAW_ARRAYS_LINE_PATTERN.matcher(line);
            boolean isDrawElements = drawMatcher.find();
            boolean isDrawArrays = drawArraysMatcher.find();
            if (!isDrawElements && !isDrawArrays) {
                continue;
            }
            int drawElementsOrdinal = -1;
            if (isDrawElements) {
                drawElementsOrdinal = ++drawElementsCount;
            }

            drawCount++;
            long glCallNumber = extractGlCallNumber(line);
            Integer boundTextureId = boundTexture2DByUnit.get(0);
            if (boundTextureId == null) {
                boundTextureId = boundTexture2DByUnit.get(activeTextureUnit);
            }
            if (boundTextureId == null || boundTextureId < 0) {
                boundTextureId = -1;
            }

            String drawArgs = isDrawElements ? drawMatcher.group(1) : drawArraysMatcher.group(1);
            String mode = extractToken(DRAW_MODE_PATTERN, drawArgs);
            Integer count = extractInt(DRAW_COUNT_PATTERN, drawArgs);
            String type = isDrawElements ? extractToken(DRAW_TYPE_PATTERN, drawArgs) : "GL_UNSIGNED_SHORT";
            if (mode == null || type == null || count == null) {
                continue;
            }
            boolean trianglePrimitive = GL_TRIANGLE_STRIP.equals(mode) || GL_TRIANGLES.equals(mode);
            boolean linePrimitive = GL_LINES.equals(mode) || GL_LINE_STRIP.equals(mode) || GL_LINE_LOOP.equals(mode);
            if ((!trianglePrimitive && !linePrimitive) || !"GL_UNSIGNED_SHORT".equals(type) || count <= 0) {
                continue;
            }
            if (linePrimitive) {
                lineDrawsSeen++;
            }

            int[] drawIndices;
            if (isDrawElements) {
                Path indicesPath = ManifestProcessor.resolveIndicesBlobPath(frameDirectory, manifest, glCallNumber, drawElementsOrdinal);
                drawIndices = loadUnsignedShortBlob(indicesPath, count);
                if (drawIndices == null || drawIndices.length == 0) {
                    String reason = classifyBlobFailure(drawArgs.contains("indices = NULL"), indicesPath, "index");
                    TileBuilder skippedBuilder = findLastTileByTexture(tilesByKey, boundTextureId);
                    if (skippedBuilder != null) {
                        skippedBuilder.noteDraw(mode, drawCount, glCallNumber, 0, 0, true, reason);
                    }
                    continue;
                }
            }
            else {
                Integer first = extractInt(DRAW_FIRST_PATTERN, drawArgs);
                if (first == null || first < 0) {
                    continue;
                }
                drawIndices = new int[count];
                for (int i = 0; i < count; i++) {
                    drawIndices[i] = first + i;
                }
            }
            if (linePrimitive) {
                lineDrawsWithIndices++;
            }
            if (currentPositionVertexAttribCall == null && vertexAttribExportCallCount <= 0) {
                TileBuilder skippedBuilder = findLastTileByTexture(tilesByKey, boundTextureId);
                if (skippedBuilder != null) {
                    skippedBuilder.noteDraw(mode, drawCount, glCallNumber, 0, drawIndices.length, true, "null blob");
                }
                continue;
            }
            Path vertexPath = ManifestProcessor.resolveVertexBlobPath(frameDirectory, manifest, currentPositionVertexAttribCall, vertexAttribExportCallCount, 0);
            byte[] positionBlob = loadBlob(vertexPath);
            if (positionBlob == null || positionBlob.length == 0) {
                TileBuilder skippedBuilder = findLastTileByTexture(tilesByKey, boundTextureId);
                String reason = classifyBlobFailure(false, vertexPath, "vertex");
                if (skippedBuilder != null) {
                    skippedBuilder.noteDraw(mode, drawCount, glCallNumber, 0, drawIndices.length, true, reason);
                }
                continue;
            }
            int vertexArraySize = estimateVertexArraySize(positionBlob, currentVertexSize, currentVertexStride);
            if (vertexArraySize <= 0) {
                TileBuilder skippedBuilder = findLastTileByTexture(tilesByKey, boundTextureId);
                if (skippedBuilder != null) {
                    skippedBuilder.noteDraw(mode, drawCount, glCallNumber, 0, drawIndices.length, true, "zero array length blob");
                }
                continue;
            }
            TileKey key = new TileKey(boundTextureId, matrixSignature(currentProjectionMatrix), matrixSignature(currentModelViewMatrix));
            TileBuilder builder = tilesByKey.get(key);
            if (builder == null) {
                builder = new TileBuilder(
                    frameId,
                    boundTextureId,
                    currentProjectionMatrix,
                    currentModelViewMatrix
                );
                tilesByKey.put(key, builder);
            }
            if (trianglePrimitive) {
                TileGeometry candidate = computeGeometry(positionBlob, currentVertexSize, currentVertexStride, drawIndices);
                if (candidate == null) {
                    TileBuilder skippedBuilder = findLastTileByTexture(tilesByKey, boundTextureId);
                    if (skippedBuilder != null) {
                        skippedBuilder.noteDraw(mode, drawCount, glCallNumber, vertexArraySize, drawIndices.length, true, "invalid geometry data");
                    }
                    continue;
                }
                Path texCoordPath = ManifestProcessor.resolveVertexBlobPath(frameDirectory, manifest, currentTexCoordVertexAttribCall, -1, 3);
                byte[] texCoordBlob = loadBlob(texCoordPath);
                List<Vector3D> baseTexCoords = TextureProcessor.computeTexCoords(texCoordBlob, currentTexCoordSize, currentTexCoordStride, drawIndices);
                List<Vector3D> transformedTexCoords = TextureProcessor.buildTexCoords(baseTexCoords, candidate.points(), candidate.min(), candidate.max(), currentTextureMatrix);

                if (GL_TRIANGLES.equals(mode)) {
                    TrianglesProcessor.addTrianglesAsStrips(
                        candidate.points(),
                        transformedTexCoords,
                        builder::addStrip
                    );
                }
                else {
                    builder.addStrip(candidate.points(), transformedTexCoords, candidate.min(), candidate.max());
                }
            }
            else {
                LineGeometry lineGeometry = computeLineGeometry(positionBlob, currentVertexSize, currentVertexStride, drawIndices, mode);
                if (lineGeometry == null || lineGeometry.strips().isEmpty()) {
                    TileBuilder skippedBuilder = findLastTileByTexture(tilesByKey, boundTextureId);
                    if (skippedBuilder != null) {
                        skippedBuilder.noteDraw(mode, drawCount, glCallNumber, vertexArraySize, drawIndices.length, true, "invalid line data");
                    }
                    continue;
                }
                lineDrawsWithGeometry++;
                for (List<Vector3D> strip : lineGeometry.strips()) {
                    frameLines.add(new Line(mode, strip, currentModelViewMatrix));
                }
            }
            /*
            if (GL_TRIANGLES.equals(mode)) {
                TrianglesProcessor.addTrianglesAsStrips(
                    candidate.points(),
                    transformedTexCoords,
                    builder::addStrip
                );
            }
            else if (GL_TRIANGLE_STRIP.equals(mode)) {
                builder.addStrip(candidate.points(), transformedTexCoords, candidate.min(), candidate.max());
            }
            else {
                builder.addLineStrip(mode, candidate.points(), candidate.min(), candidate.max());
            }
            */
            builder.noteDraw(mode, drawCount, glCallNumber, vertexArraySize, drawIndices.length, false, "");
        }

        List<TileInstance> tiles = new ArrayList<>(tilesByKey.size());
        for (TileBuilder builder : tilesByKey.values()) {
            tiles.add(builder.build());
        }
        return new FrameGeometry(tiles, frameLines);
    }

    private static TileBuilder findLastTileByTexture(Map<TileKey, TileBuilder> tilesByKey, int textureId) {
        TileBuilder out = null;
        for (Map.Entry<TileKey, TileBuilder> e : tilesByKey.entrySet()) {
            if (e.getKey().textureId == textureId) {
                out = e.getValue();
            }
        }
        return out;
    }

    private static String matrixSignature(double[] m) {
        if (m == null || m.length != 16) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(16 * 12);
        for (int i = 0; i < 16; i++) {
            double q = Math.rint(m[i] * 10000.0) / 10000.0;
            if (i > 0) sb.append('|');
            sb.append(q);
        }
        return sb.toString();
    }

    private static String classifyBlobFailure(boolean isNullPointerInTrace, Path path, String blobKind) {
        if (isNullPointerInTrace) return "null blob";
        if (path == null || !Files.exists(path)) return "null blob";
        try {
            long size = Files.size(path);
            if (size <= 0) return "zero array length blob";
            if ("index".equals(blobKind) && (size % 2L) != 0L) return "invalid index blob";
            return "blob decode failure";
        }
        catch (IOException ex) {
            return "null blob";
        }
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
        try { return Files.readAllBytes(path); } catch (IOException e) { return null; }
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

    private static LineGeometry computeLineGeometry(
        byte[] vertexBlob,
        int vertexSize,
        int vertexStride,
        int[] indices,
        String mode
    ) {
        if (vertexSize < 3 || vertexBlob == null || indices == null || indices.length == 0) return null;
        int minStride = vertexSize * 4;
        int strideBytes = vertexStride > 0 ? vertexStride : minStride;
        if (strideBytes < minStride || vertexBlob.length < minStride) return null;
        int vertexCount = vertexBlob.length / strideBytes;
        if (vertexCount <= 0) return null;
        ByteBuffer bb = ByteBuffer.wrap(vertexBlob).order(ByteOrder.LITTLE_ENDIAN);

        boolean hasAny = false;
        double minX = 0, minY = 0, minZ = 0, maxX = 0, maxY = 0, maxZ = 0;
        List<List<Vector3D>> strips = new ArrayList<>();
        List<Vector3D> currentStrip = new ArrayList<>();

        for (int index : indices) {
            if (index < 0 || index >= vertexCount) {
                if (currentStrip.size() >= 2) {
                    strips.add(List.copyOf(currentStrip));
                }
                currentStrip.clear();
                continue;
            }
            int baseBytes = index * strideBytes;
            if (baseBytes + 12 > vertexBlob.length) {
                continue;
            }
            double x = bb.getFloat(baseBytes);
            double y = bb.getFloat(baseBytes + 4);
            double z = bb.getFloat(baseBytes + 8);
            Vector3D p = new Vector3D(x, y, z);
            currentStrip.add(p);

            if (!hasAny) {
                minX = maxX = x;
                minY = maxY = y;
                minZ = maxZ = z;
                hasAny = true;
            }
            else {
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
            }
        }
        if (currentStrip.size() >= 2) {
            strips.add(List.copyOf(currentStrip));
        }
        if (GL_LINES.equals(mode)) {
            List<List<Vector3D>> pairs = new ArrayList<>();
            for (List<Vector3D> strip : strips) {
                for (int i = 0; i + 1 < strip.size(); i += 2) {
                    pairs.add(List.of(strip.get(i), strip.get(i + 1)));
                }
            }
            strips = pairs;
        }
        if (GL_LINE_LOOP.equals(mode)) {
            List<List<Vector3D>> closed = new ArrayList<>();
            for (List<Vector3D> strip : strips) {
                if (strip.size() < 2) continue;
                List<Vector3D> loop = new ArrayList<>(strip.size() + 1);
                loop.addAll(strip);
                loop.add(strip.get(0));
                closed.add(List.copyOf(loop));
            }
            strips = closed;
        }
        if (!hasAny || strips.isEmpty()) return null;
        return new LineGeometry(new Vector3D(minX, minY, minZ), new Vector3D(maxX, maxY, maxZ), strips);
    }

    private static int[] loadUnsignedShortBlob(Path path, int maxCount) {
        byte[] data;
        try {
            data = Files.readAllBytes(path);
        }
        catch (IOException e) {
            return null;
        }
        if (data.length < 2) return null;
        int available = data.length / 2;
        int count = Math.min(Math.max(maxCount, 0), available);
        int[] out = new int[count];
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) out[i] = bb.getShort() & 0xFFFF;
        return out;
    }

    private static int estimateVertexArraySize(byte[] vertexBlob, int vertexSize, int vertexStride) {
        if (vertexBlob == null || vertexSize < 3) return 0;
        int minStride = vertexSize * 4;
        int strideBytes = vertexStride > 0 ? vertexStride : minStride;
        if (strideBytes < minStride || strideBytes <= 0) return 0;
        return vertexBlob.length / strideBytes;
    }

    private static long extractGlCallNumber(String line) {
        Matcher matcher = GL_CALL_NUMBER_PATTERN.matcher(line);
        if (!matcher.find()) return -1L;
        try {
            return Long.parseLong(matcher.group(1));
        }
        catch (NumberFormatException ex) {
            return -1L;
        }
    }

    record FrameGeometry(List<TileInstance> tiles, List<Line> lines) {}

    private record TileGeometry(Vector3D min, Vector3D max, List<Vector3D> points) {}
    private record LineGeometry(Vector3D min, Vector3D max, List<List<Vector3D>> strips) {}

    private record TileKey(int textureId, String projectionSignature, String modelViewSignature) {}

    private static final class TileBuilder {
        private final int frameId;
        private final int textureId;
        private final double[] projectionMatrix;
        private final double[] modelViewMatrix;
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

        private TileBuilder(int frameId, int textureId, double[] projectionMatrix, double[] modelViewMatrix) {
            this.frameId = frameId;
            this.textureId = textureId;
            this.projectionMatrix = projectionMatrix == null ? null : projectionMatrix.clone();
            this.modelViewMatrix = modelViewMatrix == null ? null : modelViewMatrix.clone();
        }

        private void noteDraw(String primitive, int parserCall, long glCall, int vertexArraySize, int indexArraySize, boolean skipped, String skipReason) {
            this.primitive = primitive == null ? "n/a" : primitive;
            this.parserCall = parserCall;
            this.glCall = glCall;
            this.vertexArraySize = Math.max(0, vertexArraySize);
            this.indexArraySize = Math.max(0, indexArraySize);
            if (!strips.isEmpty() || !flatPoints.isEmpty()) {
                this.skipped = false;
                this.skipReason = "";
                return;
            }
            this.skipped = skipped;
            this.skipReason = skipReason == null ? "" : skipReason;
        }

        private void addStrip(List<Vector3D> stripPoints, List<Vector3D> uvCoords, Vector3D stripMin, Vector3D stripMax) {
            if (stripPoints == null || stripPoints.isEmpty()) return;
            strips.add(List.copyOf(stripPoints));
            stripTexCoords.add(uvCoords == null ? List.of() : List.copyOf(uvCoords));
            flatPoints.addAll(stripPoints);

            if (min == null || max == null) {
                min = Vector3D.copyOf(stripMin);
                max = Vector3D.copyOf(stripMax);
                return;
            }

            min = new Vector3D(Math.min(min.x(), stripMin.x()), Math.min(min.y(), stripMin.y()), Math.min(min.z(), stripMin.z()));
            max = new Vector3D(Math.max(max.x(), stripMax.x()), Math.max(max.y(), stripMax.y()), Math.max(max.z(), stripMax.z()));
        }

        private TileInstance build() {
            boolean exportableSimpleStrip =
                GL_TRIANGLE_STRIP.equals(primitive) &&
                strips.size() == 1 &&
                stripTexCoords.size() == 1 &&
                stripTexCoords.get(0) != null &&
                stripTexCoords.get(0).size() == strips.get(0).size() &&
                strips.get(0).size() >= 3;
            boolean finalSkipped = skipped || !exportableSimpleStrip;
            String finalSkipReason = skipReason;
            if (!skipped && !exportableSimpleStrip) {
                if (!GL_TRIANGLE_STRIP.equals(primitive)) {
                    finalSkipReason = "unsupported primitive";
                }
                else if (strips.size() != 1) {
                    finalSkipReason = "multiple strips";
                }
                else {
                    finalSkipReason = "invalid strip data";
                }
            }
            return new TileInstance(
                frameScopedContentId(),
                null,
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
                finalSkipped,
                finalSkipReason,
                projectionMatrix,
                modelViewMatrix
            );
        }

        private String frameScopedContentId() {
            return frameId + "_" + textureId;
        }
    }

}
