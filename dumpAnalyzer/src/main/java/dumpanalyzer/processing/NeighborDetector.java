package dumpanalyzer.processing;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import dumpanalyzer.model.AxisAlignedBoundingBox;
import dumpanalyzer.model.Frame;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;
import vsdk.toolkit.common.linealAlgebra.Vector3D;

public final class NeighborDetector {
    private static final double MIN_SIZE = 1e-12;
    private static final double LOG2_CLUSTER_TOLERANCE = 0.20;

    private NeighborDetector() {
    }

    public static void populateNeighbors(
        Frame frame,
        Matrix4x4 projection,
        int viewportWidth,
        int viewportHeight,
        double[] frameModelView,
        boolean useGoogleCameraView
    ) {
        if (frame == null || projection == null || viewportWidth <= 0 || viewportHeight <= 0) {
            return;
        }
        List<AxisAlignedBoundingBox> aabbs = frame.getAxisAlignedBoundingBoxes();
        if (aabbs.isEmpty()) {
            return;
        }
        List<ScaleCluster> clusters = clusterByApparentXWidth(
            aabbs,
            projection,
            viewportWidth,
            viewportHeight,
            frameModelView,
            useGoogleCameraView
        );
        clusters.sort(Comparator.comparingInt((ScaleCluster c) -> c.items().size()).reversed());
        for (int i = 0; i < clusters.size(); i++) {
            Color color = i == 0 ? Color.YELLOW : randomColor(frame.getId(), i);
            for (AxisAlignedBoundingBox aabb : clusters.get(i).items()) {
                aabb.setColor(color);
            }
        }
    }

    private static List<ScaleCluster> clusterByApparentXWidth(
        List<AxisAlignedBoundingBox> aabbs,
        Matrix4x4 projection,
        int viewportWidth,
        int viewportHeight,
        double[] frameModelView,
        boolean useGoogleCameraView
    ) {
        List<ScaleCluster> clusters = new ArrayList<>();
        for (AxisAlignedBoundingBox aabb : aabbs) {
            Size2D projectedSize = projectedSize2D(
                aabb,
                projection,
                viewportWidth,
                viewportHeight,
                frameModelView,
                useGoogleCameraView
            );
            if (projectedSize == null || !(projectedSize.deltaX() > MIN_SIZE) || !(projectedSize.deltaY() > MIN_SIZE)) {
                continue;
            }
            double log2Width = Math.log(projectedSize.deltaX()) / Math.log(2.0);
            double log2Height = Math.log(projectedSize.deltaY()) / Math.log(2.0);
            ScaleCluster found = null;
            for (ScaleCluster c : clusters) {
                if (Math.abs(log2Width - c.log2RepresentativeX()) <= LOG2_CLUSTER_TOLERANCE
                    && Math.abs(log2Height - c.log2RepresentativeY()) <= LOG2_CLUSTER_TOLERANCE) {
                    found = c;
                    break;
                }
            }
            if (found == null) {
                found = new ScaleCluster(log2Width, log2Height);
                clusters.add(found);
            }
            found.add(aabb);
        }
        return clusters;
    }

    private static Size2D projectedSize2D(
        AxisAlignedBoundingBox aabb,
        Matrix4x4 projection,
        int viewportWidth,
        int viewportHeight,
        double[] frameModelView,
        boolean useGoogleCameraView
    ) {
        if (aabb == null || aabb.getMin() == null || aabb.getMax() == null) {
            return null;
        }
        double[] modelView = resolveModelView(aabb, frameModelView, useGoogleCameraView);
        if (modelView == null || modelView.length != 16 || aabb.getGeometryPoints().isEmpty()) {
            aabb.setProjectedBounds(null, null, null, null);
            return null;
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        boolean any = false;

        for (Vector3D p : aabb.getGeometryPoints()) {
            int[] px = projectToViewportPixel(p, modelView, projection, viewportWidth, viewportHeight);
            if (px == null) {
                continue;
            }
            any = true;
            minX = Math.min(minX, px[0]);
            minY = Math.min(minY, px[1]);
            maxX = Math.max(maxX, px[0]);
            maxY = Math.max(maxY, px[1]);
        }
        if (!any) {
            aabb.setProjectedBounds(null, null, null, null);
            return null;
        }
        aabb.setProjectedBounds(minX, minY, maxX, maxY);
        return new Size2D(Math.abs((double)maxX - (double)minX), Math.abs((double)maxY - (double)minY));
    }

    private static double[] resolveModelView(AxisAlignedBoundingBox aabb, double[] frameModelView, boolean useGoogleCameraView) {
        if (useGoogleCameraView) {
            double[] local = aabb.getModelViewMatrix();
            if (local != null && local.length == 16) {
                return local;
            }
        }
        return frameModelView;
    }

    private static int[] projectToViewportPixel(
        Vector3D p,
        double[] modelView,
        Matrix4x4 projection,
        int viewportWidth,
        int viewportHeight
    ) {
        if (p == null || modelView == null || modelView.length != 16 || projection == null) {
            return null;
        }
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
        int px = (int)Math.round((ndcX * 0.5 + 0.5) * (viewportWidth - 1));
        int py = (int)Math.round((ndcY * 0.5 + 0.5) * (viewportHeight - 1));
        if (px < 0 || px >= viewportWidth || py < 0 || py >= viewportHeight) {
            return null;
        }
        return new int[] { px, py };
    }

    private static Color randomColor(int frameId, int clusterIndex) {
        Random rnd = new Random(31L * frameId + 131L * clusterIndex + 17L);
        float r = 0.25f + rnd.nextFloat() * 0.75f;
        float g = 0.25f + rnd.nextFloat() * 0.75f;
        float b = 0.25f + rnd.nextFloat() * 0.75f;
        return new Color(r, g, b);
    }

    private static final class ScaleCluster {
        private final double log2RepresentativeX;
        private final double log2RepresentativeY;
        private final List<AxisAlignedBoundingBox> items = new ArrayList<>();

        private ScaleCluster(double log2RepresentativeX, double log2RepresentativeY) {
            this.log2RepresentativeX = log2RepresentativeX;
            this.log2RepresentativeY = log2RepresentativeY;
        }

        private double log2RepresentativeX() {
            return log2RepresentativeX;
        }

        private double log2RepresentativeY() {
            return log2RepresentativeY;
        }

        private List<AxisAlignedBoundingBox> items() {
            return items;
        }

        private void add(AxisAlignedBoundingBox aabb) {
            items.add(aabb);
        }
    }

    private record Size2D(double deltaX, double deltaY) {
    }
}
