package dumpanalyzer.processing;

import dumpanalyzer.model.TileInstance;
import java.util.ArrayList;
import java.util.List;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;

public final class TriangleMeshVertexComparator {
    private static final int REQUIRED_SHARED_VERTICES = 3;
    private static final double VERTEX_EPSILON = 1e-4;
    private static final double MIN_DIRECTION_DELTA_PIXELS = 0.25;
    private static final double EDGE_BAND_RATIO = 0.12;
    private static final int EDGE_VERTEX_TARGET = 3;

    public enum Direction {
        NORTH,
        SOUTH,
        EAST,
        WEST
    }

    public record ComparisonResult(boolean areNeighbors, Direction directionFromAtoB, double distanceSquared) {}

    public ComparisonResult compare(
        TileInstance a,
        TileInstance b,
        Matrix4x4 projection,
        int viewportWidth,
        int viewportHeight,
        double[] frameModelView,
        boolean useGoogleCameraView
    ) {
        TileInstance.TriangleStripGeometry ga = a == null ? null : a.getTriangleStrip();
        TileInstance.TriangleStripGeometry gb = b == null ? null : b.getTriangleStrip();
        if (ga == null || gb == null || ga.vertices().isEmpty() || gb.vertices().isEmpty()) {
            return new ComparisonResult(false, null, Double.POSITIVE_INFINITY);
        }

        List<TileInstance.TriangleStripVertex> uniqueA = deduplicateVertices(ga.vertices(), VERTEX_EPSILON);
        List<TileInstance.TriangleStripVertex> uniqueB = deduplicateVertices(gb.vertices(), VERTEX_EPSILON);
        if (uniqueA.isEmpty() || uniqueB.isEmpty()) {
            return new ComparisonResult(false, null, Double.POSITIVE_INFINITY);
        }

        if (countSharedVertices(uniqueA, uniqueB, VERTEX_EPSILON) < REQUIRED_SHARED_VERTICES) {
            return new ComparisonResult(false, null, Double.POSITIVE_INFINITY);
        }

        List<TileInstance.TriangleStripVertex> exclusiveA = exclusiveVertices(uniqueA, uniqueB, VERTEX_EPSILON);
        List<TileInstance.TriangleStripVertex> exclusiveB = exclusiveVertices(uniqueB, uniqueA, VERTEX_EPSILON);
        List<TileInstance.TriangleStripVertex> directionSetA = exclusiveA.isEmpty() ? uniqueA : exclusiveA;
        List<TileInstance.TriangleStripVertex> directionSetB = exclusiveB.isEmpty() ? uniqueB : exclusiveB;

        double[] aCenterViewport = centerInViewport(
            directionSetA,
            resolveModelView(a, frameModelView, useGoogleCameraView),
            projection,
            viewportWidth,
            viewportHeight
        );
        double[] bCenterViewport = centerInViewport(
            directionSetB,
            resolveModelView(b, frameModelView, useGoogleCameraView),
            projection,
            viewportWidth,
            viewportHeight
        );

        Direction direction = null;
        double distanceSquared;
        if (aCenterViewport != null && bCenterViewport != null) {
            EdgeSets aEdges = buildEdgeSets(directionSetA, resolveModelView(a, frameModelView, useGoogleCameraView), projection, viewportWidth, viewportHeight);
            EdgeSets bEdges = buildEdgeSets(directionSetB, resolveModelView(b, frameModelView, useGoogleCameraView), projection, viewportWidth, viewportHeight);
            direction = directionFromEdgeOverlap(aEdges, bEdges, VERTEX_EPSILON);
        }

        if (aCenterViewport != null && bCenterViewport != null) {
            double dx = bCenterViewport[0] - aCenterViewport[0];
            double dy = bCenterViewport[1] - aCenterViewport[1];
            distanceSquared = dx * dx + dy * dy;
            if (direction == null) {
                direction = directionFromDelta(dx, dy);
            }
        }
        else {
            double[] aCenterWorld = centerInWorld(directionSetA);
            double[] bCenterWorld = centerInWorld(directionSetB);
            double dx = bCenterWorld[0] - aCenterWorld[0];
            double dy = bCenterWorld[1] - aCenterWorld[1];
            double dz = bCenterWorld[2] - aCenterWorld[2];
            distanceSquared = dx * dx + dy * dy + dz * dz;
            direction = directionFromDelta(dx, dy);
        }

        if (direction == null) {
            double[] aCenterWorld = centerInWorld(uniqueA);
            double[] bCenterWorld = centerInWorld(uniqueB);
            double dx = bCenterWorld[0] - aCenterWorld[0];
            double dy = bCenterWorld[1] - aCenterWorld[1];
            double dz = bCenterWorld[2] - aCenterWorld[2];
            distanceSquared = dx * dx + dy * dy + dz * dz;
            direction = directionFromDelta(dx, dy);
        }

        return new ComparisonResult(true, direction, distanceSquared);
    }

    private static Direction directionFromEdgeOverlap(EdgeSets a, EdgeSets b, double epsilon) {
        int east = countSharedProjected(a.east, b.west, epsilon);
        int west = countSharedProjected(a.west, b.east, epsilon);
        int north = countSharedProjected(a.north, b.south, epsilon);
        int south = countSharedProjected(a.south, b.north, epsilon);
        int best = Math.max(Math.max(east, west), Math.max(north, south));
        if (best <= 0) {
            return null;
        }
        if (best == east) return Direction.EAST;
        if (best == west) return Direction.WEST;
        if (best == north) return Direction.NORTH;
        return Direction.SOUTH;
    }

    private static Direction directionFromDelta(double dx, double dy) {
        if (Math.abs(dx) < MIN_DIRECTION_DELTA_PIXELS && Math.abs(dy) < MIN_DIRECTION_DELTA_PIXELS) {
            return null;
        }
        return Math.abs(dx) >= Math.abs(dy)
            ? (dx >= 0.0 ? Direction.EAST : Direction.WEST)
            : (dy >= 0.0 ? Direction.NORTH : Direction.SOUTH);
    }

    private static int countSharedVertices(
        List<TileInstance.TriangleStripVertex> a,
        List<TileInstance.TriangleStripVertex> b,
        double epsilon
    ) {
        int shared = 0;
        boolean[] usedB = new boolean[b.size()];
        for (TileInstance.TriangleStripVertex va : a) {
            for (int i = 0; i < b.size(); i++) {
                if (usedB[i]) {
                    continue;
                }
                TileInstance.TriangleStripVertex vb = b.get(i);
                if (Math.abs(va.x() - vb.x()) <= epsilon
                    && Math.abs(va.y() - vb.y()) <= epsilon
                    && Math.abs(va.z() - vb.z()) <= epsilon) {
                    usedB[i] = true;
                    shared++;
                    break;
                }
            }
        }
        return shared;
    }

    private static List<TileInstance.TriangleStripVertex> deduplicateVertices(
        List<TileInstance.TriangleStripVertex> vertices,
        double epsilon
    ) {
        List<TileInstance.TriangleStripVertex> out = new ArrayList<>();
        for (TileInstance.TriangleStripVertex v : vertices) {
            boolean exists = false;
            for (TileInstance.TriangleStripVertex e : out) {
                if (Math.abs(v.x() - e.x()) <= epsilon
                    && Math.abs(v.y() - e.y()) <= epsilon
                    && Math.abs(v.z() - e.z()) <= epsilon) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                out.add(v);
            }
        }
        return out;
    }

    private static List<TileInstance.TriangleStripVertex> exclusiveVertices(
        List<TileInstance.TriangleStripVertex> source,
        List<TileInstance.TriangleStripVertex> other,
        double epsilon
    ) {
        List<TileInstance.TriangleStripVertex> out = new ArrayList<>();
        for (TileInstance.TriangleStripVertex s : source) {
            boolean shared = false;
            for (TileInstance.TriangleStripVertex o : other) {
                if (Math.abs(s.x() - o.x()) <= epsilon
                    && Math.abs(s.y() - o.y()) <= epsilon
                    && Math.abs(s.z() - o.z()) <= epsilon) {
                    shared = true;
                    break;
                }
            }
            if (!shared) {
                out.add(s);
            }
        }
        return out;
    }

    private static double[] centerInWorld(List<TileInstance.TriangleStripVertex> vertices) {
        double sx = 0.0;
        double sy = 0.0;
        double sz = 0.0;
        for (TileInstance.TriangleStripVertex v : vertices) {
            sx += v.x();
            sy += v.y();
            sz += v.z();
        }
        double inv = 1.0 / vertices.size();
        return new double[] {sx * inv, sy * inv, sz * inv};
    }

    private static double[] centerInViewport(
        List<TileInstance.TriangleStripVertex> vertices,
        double[] modelView,
        Matrix4x4 projection,
        int viewportWidth,
        int viewportHeight
    ) {
        if (modelView == null || modelView.length != 16 || projection == null || viewportWidth <= 0 || viewportHeight <= 0) {
            return null;
        }
        double sx = 0.0;
        double sy = 0.0;
        int count = 0;
        for (TileInstance.TriangleStripVertex v : vertices) {
            double[] p = projectToViewport(v, modelView, projection, viewportWidth, viewportHeight);
            if (p == null) {
                continue;
            }
            sx += p[0];
            sy += p[1];
            count++;
        }
        if (count == 0) {
            return null;
        }
        double inv = 1.0 / count;
        return new double[] {sx * inv, sy * inv};
    }

    private static EdgeSets buildEdgeSets(
        List<TileInstance.TriangleStripVertex> vertices,
        double[] modelView,
        Matrix4x4 projection,
        int viewportWidth,
        int viewportHeight
    ) {
        List<ProjectedVertex> points = new ArrayList<>();
        for (TileInstance.TriangleStripVertex v : vertices) {
            double[] p = projectToViewport(v, modelView, projection, viewportWidth, viewportHeight);
            if (p != null) {
                points.add(new ProjectedVertex(v.x(), v.y(), v.z(), p[0], p[1]));
            }
        }
        if (points.isEmpty()) {
            return new EdgeSets(List.of(), List.of(), List.of(), List.of());
        }
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (ProjectedVertex p : points) {
            minX = Math.min(minX, p.px);
            maxX = Math.max(maxX, p.px);
            minY = Math.min(minY, p.py);
            maxY = Math.max(maxY, p.py);
        }
        double xBand = Math.max(1.0, (maxX - minX) * EDGE_BAND_RATIO);
        double yBand = Math.max(1.0, (maxY - minY) * EDGE_BAND_RATIO);
        final double maxXf = maxX;
        final double minXf = minX;
        final double maxYf = maxY;
        final double minYf = minY;
        final double xBandf = xBand;
        final double yBandf = yBand;
        return new EdgeSets(
            selectEdge(points, p -> p.py >= maxYf - yBandf, true, true),
            selectEdge(points, p -> p.py <= minYf + yBandf, true, false),
            selectEdge(points, p -> p.px >= maxXf - xBandf, false, true),
            selectEdge(points, p -> p.px <= minXf + xBandf, false, false)
        );
    }

    private interface ProjectedFilter { boolean keep(ProjectedVertex p); }

    private static List<ProjectedVertex> selectEdge(List<ProjectedVertex> points, ProjectedFilter filter, boolean sortByX, boolean descending) {
        List<ProjectedVertex> out = new ArrayList<>();
        for (ProjectedVertex p : points) {
            if (filter.keep(p)) out.add(p);
        }
        out.sort((a, b) -> {
            double av = sortByX ? a.px : a.py;
            double bv = sortByX ? b.px : b.py;
            return descending ? Double.compare(bv, av) : Double.compare(av, bv);
        });
        if (out.size() > EDGE_VERTEX_TARGET) {
            return List.copyOf(out.subList(0, EDGE_VERTEX_TARGET));
        }
        return List.copyOf(out);
    }

    private static int countSharedProjected(List<ProjectedVertex> a, List<ProjectedVertex> b, double epsilon) {
        int shared = 0;
        boolean[] usedB = new boolean[b.size()];
        for (ProjectedVertex va : a) {
            for (int i = 0; i < b.size(); i++) {
                if (usedB[i]) continue;
                ProjectedVertex vb = b.get(i);
                if (Math.abs(va.x - vb.x) <= epsilon
                    && Math.abs(va.y - vb.y) <= epsilon
                    && Math.abs(va.z - vb.z) <= epsilon) {
                    usedB[i] = true;
                    shared++;
                    break;
                }
            }
        }
        return shared;
    }

    private static double[] projectToViewport(
        TileInstance.TriangleStripVertex p,
        double[] modelView,
        Matrix4x4 projection,
        int viewportWidth,
        int viewportHeight
    ) {
        double ex = modelView[0] * p.x() + modelView[4] * p.y() + modelView[8] * p.z() + modelView[12];
        double ey = modelView[1] * p.x() + modelView[5] * p.y() + modelView[9] * p.z() + modelView[13];
        double ez = modelView[2] * p.x() + modelView[6] * p.y() + modelView[10] * p.z() + modelView[14];
        double ew = modelView[3] * p.x() + modelView[7] * p.y() + modelView[11] * p.z() + modelView[15];

        double[] proj = projection.exportToDoubleArrayColumnOrder();
        if (proj == null || proj.length != 16) {
            return null;
        }
        double cx = proj[0] * ex + proj[4] * ey + proj[8] * ez + proj[12] * ew;
        double cy = proj[1] * ex + proj[5] * ey + proj[9] * ez + proj[13] * ew;
        double cw = proj[3] * ex + proj[7] * ey + proj[11] * ez + proj[15] * ew;
        if (Math.abs(cw) < 1e-12) {
            return null;
        }
        double ndcX = cx / cw;
        double ndcY = cy / cw;
        if (!Double.isFinite(ndcX) || !Double.isFinite(ndcY)) {
            return null;
        }
        double px = (ndcX * 0.5 + 0.5) * (viewportWidth - 1);
        double py = (ndcY * 0.5 + 0.5) * (viewportHeight - 1);
        return new double[] {px, py};
    }

    private static double[] resolveModelView(TileInstance tile, double[] frameModelView, boolean useGoogleCameraView) {
        if (useGoogleCameraView && tile != null) {
            double[] local = tile.getModelViewMatrix();
            if (local != null && local.length == 16) {
                return local;
            }
        }
        return frameModelView;
    }

    private record ProjectedVertex(double x, double y, double z, double px, double py) {}
    private record EdgeSets(List<ProjectedVertex> north, List<ProjectedVertex> south, List<ProjectedVertex> east, List<ProjectedVertex> west) {}
}
