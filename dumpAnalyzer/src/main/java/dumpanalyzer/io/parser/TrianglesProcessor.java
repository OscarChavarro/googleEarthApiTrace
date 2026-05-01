package dumpanalyzer.io.parser;

import java.util.List;

import vsdk.toolkit.common.linealAlgebra.Vector3D;

final class TrianglesProcessor {
    private TrianglesProcessor() {
    }

    @FunctionalInterface
    interface StripConsumer {
        void accept(List<Vector3D> stripPoints, List<Vector3D> uvCoords, Vector3D min, Vector3D max);
    }

    static void addTrianglesAsStrips(List<Vector3D> points, List<Vector3D> texCoords, StripConsumer consumer) {
        if (consumer == null || points == null || points.size() < 3) return;
        int triangleCount = points.size() / 3;
        for (int i = 0; i < triangleCount; i++) {
            int base = i * 3;
            List<Vector3D> triangle = List.of(points.get(base), points.get(base + 1), points.get(base + 2));
            List<Vector3D> triangleUv = texCoords != null && texCoords.size() >= base + 3
                ? List.of(texCoords.get(base), texCoords.get(base + 1), texCoords.get(base + 2))
                : List.of();
            Vector3D min = minOf(triangle);
            Vector3D max = maxOf(triangle);
            consumer.accept(triangle, triangleUv, min, max);
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
}
