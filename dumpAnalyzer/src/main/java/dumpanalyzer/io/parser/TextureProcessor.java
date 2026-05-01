package dumpanalyzer.io.parser;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import vsdk.toolkit.common.linealAlgebra.Vector3D;

public final class TextureProcessor {
    private static final Pattern MATRIX_VALUES_PATTERN = Pattern.compile("m\\s*=\\s*\\{([^}]*)\\}");

    private TextureProcessor() {
    }

    public static List<Vector3D> buildTexCoords(
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

    public static List<Vector3D> computeTexCoords(byte[] texCoordBlob, int texCoordSize, int texCoordStride, int[] indices) {
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

    public static double[] applyTextureMatrix(double[] m, double u, double v) {
        double x = m[0] * u + m[4] * v + m[8] * 0.0 + m[12] * 1.0;
        double y = m[1] * u + m[5] * v + m[9] * 0.0 + m[13] * 1.0;
        double w = m[3] * u + m[7] * v + m[11] * 0.0 + m[15] * 1.0;
        if (Math.abs(w) > 1.0e-12) {
            return new double[] { x / w, y / w };
        }
        return new double[] { x, y };
    }

    public static double[] parseMatrix4(String args) {
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
            }
            catch (NumberFormatException ex) {
                return null;
            }
        }
        return out;
    }

    public static double[] identityTextureMatrix() {
        return new double[] {
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0
        };
    }

    public static <T> int getOrCreateTileNumber(
        Map<Integer, T> tilesByTextureId,
        int textureId,
        IntFunction<T> builderFactory,
        ToIntFunction<T> tileNumberExtractor
    ) {
        T existing = tilesByTextureId.get(textureId);
        if (existing != null) {
            return tileNumberExtractor.applyAsInt(existing);
        }
        int tileNumber = tilesByTextureId.size() + 1;
        tilesByTextureId.put(textureId, builderFactory.apply(tileNumber));
        return tileNumber;
    }
}
