package dumpanalyzer.processing.bigtiles;

import dumpanalyzer.processing.TriangleMeshVertexComparator;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;

public final class GlobeLevelTileIdentity implements Comparable<GlobeLevelTileIdentity> {
    private final int id;
    private final List<Integer> pathFromRoot;
    private final int row;
    private final int col;
    private final List<FrameAppearance> appearances;
    private final Vector3Dd[] deDuplicatedVertices;

    public GlobeLevelTileIdentity(
        int id,
        List<Integer> pathFromRoot,
        int row,
        int col,
        List<Vector3Dd> deDuplicatedVertices
    ) {
        this.id = id;
        this.pathFromRoot = pathFromRoot == null ? List.of() : List.copyOf(pathFromRoot);
        this.row = row;
        this.col = col;
        this.appearances = new java.util.ArrayList<>();
        if (deDuplicatedVertices == null || deDuplicatedVertices.isEmpty()) {
            this.deDuplicatedVertices = new Vector3Dd[0];
            return;
        }
        this.deDuplicatedVertices = new Vector3Dd[deDuplicatedVertices.size()];
        for (int i = 0; i < deDuplicatedVertices.size(); i++) {
            Vector3Dd v = deDuplicatedVertices.get(i);
            this.deDuplicatedVertices[i] = v == null ? null : Vector3Dd.copyOf(v);
        }
    }

    public int getId() {
        return id;
    }

    public List<Integer> getPathFromRoot() {
        return pathFromRoot;
    }

    public int getRow() {
        return row;
    }

    public int getCol() {
        return col;
    }

    public List<FrameAppearance> getAppearances() {
        synchronized (appearances) {
            return List.copyOf(appearances);
        }
    }

    public void addAppearance(int frameId, String imageId, String imagePath, TexCoordRange texCoord) {
        if (frameId < 0 || texCoord == null) {
            return;
        }
        synchronized (appearances) {
            for (FrameAppearance appearance : appearances) {
                if (sameImage(appearance, imageId, imagePath)) {
                    return;
                }
            }
            appearances.add(new FrameAppearance(frameId, imageId, imagePath, texCoord));
        }
    }

    public Vector3Dd[] getDeDuplicatedVertices() {
        Vector3Dd[] copy = new Vector3Dd[deDuplicatedVertices.length];
        for (int i = 0; i < deDuplicatedVertices.length; i++) {
            Vector3Dd v = deDuplicatedVertices[i];
            copy[i] = v == null ? null : Vector3Dd.copyOf(v);
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
        Vector3Dd thisCenter = averagePoint(deDuplicatedVertices);
        Vector3Dd otherCenter = averagePoint(other.deDuplicatedVertices);
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
        Vector3Dd center = averagePoint(deDuplicatedVertices);
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
            + ", pathFromRoot=" + pathFromRoot
            + ", row=" + row
            + ", col=" + col
            + ", appearances=" + appearances
            + ", deDuplicatedVertices=" + Arrays.toString(deDuplicatedVertices)
            + '}';
    }

    private static boolean sameImage(FrameAppearance appearance, String imageId, String imagePath) {
        if (appearance == null) {
            return false;
        }
        String currentId = imageIdNormalize(imageId);
        String existingId = imageIdNormalize(appearance.imageId());
        String currentPath = imagePathNormalize(imagePath);
        String existingPath = imagePathNormalize(appearance.imagePath());

        if (currentId != null && existingId != null && currentPath != null && existingPath != null) {
            return currentId.equals(existingId) && currentPath.equals(existingPath);
        }
        if (currentPath != null && existingPath != null) {
            return currentPath.equals(existingPath);
        }
        if (currentId != null && existingId != null) {
            return currentId.equals(existingId);
        }
        return false;
    }

    private static String imageIdNormalize(String value) {
        return value == null ? null : value.trim();
    }

    private static String imagePathNormalize(String value) {
        return value == null ? null : value.trim();
    }

    private boolean sameVertices(GlobeLevelTileIdentity other) {
        if (deDuplicatedVertices.length != other.deDuplicatedVertices.length) {
            return false;
        }
        for (int i = 0; i < deDuplicatedVertices.length; i++) {
            Vector3Dd a = deDuplicatedVertices[i];
            Vector3Dd b = other.deDuplicatedVertices[i];
            if (!samePoint(a, b)) {
                return false;
            }
        }
        return true;
    }

    private static boolean samePoint(Vector3Dd a, Vector3Dd b) {
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

    private static Vector3Dd averagePoint(Vector3Dd[] vertices) {
        if (vertices == null || vertices.length == 0) {
            return null;
        }
        double sx = 0.0;
        double sy = 0.0;
        double sz = 0.0;
        int count = 0;
        for (Vector3Dd vertex : vertices) {
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
        return new Vector3Dd(sx * inv, sy * inv, sz * inv);
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

    public record TexCoordRange(double u0, double v0, double u1, double v1) {}
    public record FrameAppearance(int frameId, String imageId, String imagePath, TexCoordRange texCoord) {}
}
