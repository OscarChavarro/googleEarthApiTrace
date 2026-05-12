package dumpanalyzer.processing.bigtiles;

import dumpanalyzer.processing.TriangleMeshVertexComparator;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import vsdk.toolkit.common.linealAlgebra.Vector3D;

public final class GlobeLevelTileIdentity implements Comparable<GlobeLevelTileIdentity> {
    private final int id;
    private final Vector3D[] deDuplicatedVertices;

    public GlobeLevelTileIdentity(int id, List<Vector3D> deDuplicatedVertices) {
        this.id = id;
        if (deDuplicatedVertices == null || deDuplicatedVertices.isEmpty()) {
            this.deDuplicatedVertices = new Vector3D[0];
            return;
        }
        this.deDuplicatedVertices = new Vector3D[deDuplicatedVertices.size()];
        for (int i = 0; i < deDuplicatedVertices.size(); i++) {
            Vector3D v = deDuplicatedVertices.get(i);
            this.deDuplicatedVertices[i] = v == null ? null : Vector3D.copyOf(v);
        }
    }

    public int getId() {
        return id;
    }

    public Vector3D[] getDeDuplicatedVertices() {
        Vector3D[] copy = new Vector3D[deDuplicatedVertices.length];
        for (int i = 0; i < deDuplicatedVertices.length; i++) {
            Vector3D v = deDuplicatedVertices[i];
            copy[i] = v == null ? null : Vector3D.copyOf(v);
        }
        return copy;
    }

    @Override
    public int compareTo(GlobeLevelTileIdentity other) {
        if (other == null) {
            return 1;
        }
        if (sameVertices(other)) {
            return 0;
        }
        Vector3D thisCenter = averagePoint(deDuplicatedVertices);
        Vector3D otherCenter = averagePoint(other.deDuplicatedVertices);
        int byX = compareCoordinate(thisCenter == null ? 0.0 : thisCenter.x(), otherCenter == null ? 0.0 : otherCenter.x());
        if (byX != 0) {
            return byX;
        }
        int byY = compareCoordinate(thisCenter == null ? 0.0 : thisCenter.y(), otherCenter == null ? 0.0 : otherCenter.y());
        if (byY != 0) {
            return byY;
        }
        return compareCoordinate(thisCenter == null ? 0.0 : thisCenter.z(), otherCenter == null ? 0.0 : otherCenter.z());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GlobeLevelTileIdentity other)) return false;
        return compareTo(other) == 0;
    }

    @Override
    public int hashCode() {
        Vector3D center = averagePoint(deDuplicatedVertices);
        if (center == null) {
            return Objects.hash(0L, 0L, 0L);
        }
        long qx = quantize(center.x());
        long qy = quantize(center.y());
        long qz = quantize(center.z());
        return Objects.hash(qx, qy, qz, deDuplicatedVertices.length);
    }

    @Override
    public String toString() {
        return "GlobeLevelTileIdentity{"
            + "id=" + id
            + ", deDuplicatedVertices=" + Arrays.toString(deDuplicatedVertices)
            + '}';
    }

    private boolean sameVertices(GlobeLevelTileIdentity other) {
        if (deDuplicatedVertices.length != other.deDuplicatedVertices.length) {
            return false;
        }
        for (int i = 0; i < deDuplicatedVertices.length; i++) {
            Vector3D a = deDuplicatedVertices[i];
            Vector3D b = other.deDuplicatedVertices[i];
            if (!samePoint(a, b)) {
                return false;
            }
        }
        return true;
    }

    private static boolean samePoint(Vector3D a, Vector3D b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        double epsilon = TriangleMeshVertexComparator.VERTEX_EPSILON;
        return Math.abs(a.x() - b.x()) <= epsilon
            && Math.abs(a.y() - b.y()) <= epsilon
            && Math.abs(a.z() - b.z()) <= epsilon;
    }

    private static Vector3D averagePoint(Vector3D[] vertices) {
        if (vertices == null || vertices.length == 0) {
            return null;
        }
        double sx = 0.0;
        double sy = 0.0;
        double sz = 0.0;
        int count = 0;
        for (Vector3D vertex : vertices) {
            if (vertex == null) {
                continue;
            }
            sx += vertex.x();
            sy += vertex.y();
            sz += vertex.z();
            count++;
        }
        if (count <= 0) {
            return null;
        }
        double inv = 1.0 / count;
        return new Vector3D(sx * inv, sy * inv, sz * inv);
    }

    private static int compareCoordinate(double a, double b) {
        double epsilon = TriangleMeshVertexComparator.VERTEX_EPSILON;
        if (Math.abs(a - b) <= epsilon) {
            return 0;
        }
        return a < b ? -1 : 1;
    }

    private static long quantize(double value) {
        return Math.round(value / TriangleMeshVertexComparator.VERTEX_EPSILON);
    }
}
