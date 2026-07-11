package dumpanalyzer.io.parser;

import java.util.List;

import vsdk.toolkit.common.linealAlgebra.Vector3Dd;

final class TrianglesProcessor {
    private TrianglesProcessor() {
    }

    @FunctionalInterface
    interface StripConsumer {
        void accept(List<Vector3Dd> stripPoints, List<Vector3Dd> uvCoords, Vector3Dd min, Vector3Dd max);
    }

    static void addTrianglesAsStrips(List<Vector3Dd> points, List<Vector3Dd> texCoords, StripConsumer consumer) {
        if (consumer == null || points == null || points.size() < 3) return;
        int triangleCount = points.size() / 3;
        for (int i = 0; i < triangleCount; i++) {
            int base = i * 3;
            List<Vector3Dd> triangle = List.of(points.get(base), points.get(base + 1), points.get(base + 2));
            List<Vector3Dd> triangleUv = texCoords != null && texCoords.size() >= base + 3
                ? List.of(texCoords.get(base), texCoords.get(base + 1), texCoords.get(base + 2))
                : List.of();
            Vector3Dd min = minOf(triangle);
            Vector3Dd max = maxOf(triangle);
            consumer.accept(triangle, triangleUv, min, max);
        }
    }

    private static Vector3Dd minOf(List<Vector3Dd> points) {
        Vector3Dd p0 = points.get(0);
        double minX = p0.x();
        double minY = p0.y();
        double minZ = p0.z();
        for (int i = 1; i < points.size(); i++) {
            Vector3Dd p = points.get(i);
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            minZ = Math.min(minZ, p.z());
        }
        return new Vector3Dd(minX, minY, minZ);
    }

    private static Vector3Dd maxOf(List<Vector3Dd> points) {
        Vector3Dd p0 = points.get(0);
        double maxX = p0.x();
        double maxY = p0.y();
        double maxZ = p0.z();
        for (int i = 1; i < points.size(); i++) {
            Vector3Dd p = points.get(i);
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
            maxZ = Math.max(maxZ, p.z());
        }
        return new Vector3Dd(maxX, maxY, maxZ);
    }
}
