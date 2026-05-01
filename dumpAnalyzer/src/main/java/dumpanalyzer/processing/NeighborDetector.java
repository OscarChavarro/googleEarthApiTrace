package dumpanalyzer.processing;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import dumpanalyzer.model.AxisAlignedBoundingBox;
import dumpanalyzer.model.Frame;

public final class NeighborDetector {
    private static final double MIN_WIDTH = 1e-12;
    private static final double LOG2_CLUSTER_TOLERANCE = 0.20;

    private NeighborDetector() {
    }

    public static void populateNeighbors(Frame frame) {
        if (frame == null) {
            return;
        }
        List<AxisAlignedBoundingBox> aabbs = frame.getAxisAlignedBoundingBoxes();
        if (aabbs.isEmpty()) {
            return;
        }
        List<ScaleCluster> clusters = clusterByApparentXWidth(aabbs, frame.getModelViewMatrix());
        clusters.sort(Comparator.comparingInt((ScaleCluster c) -> c.items().size()).reversed());
        for (int i = 0; i < clusters.size(); i++) {
            Color color = i == 0 ? Color.YELLOW : randomColor(frame.getId(), i);
            for (AxisAlignedBoundingBox aabb : clusters.get(i).items()) {
                aabb.setColor(color);
            }
        }
    }

    private static List<ScaleCluster> clusterByApparentXWidth(List<AxisAlignedBoundingBox> aabbs, double[] frameModelView) {
        List<ScaleCluster> clusters = new ArrayList<>();
        for (AxisAlignedBoundingBox aabb : aabbs) {
            double width = apparentWidthX(aabb, frameModelView);
            if (!(width > MIN_WIDTH)) {
                continue;
            }
            double log2Width = Math.log(width) / Math.log(2.0);
            ScaleCluster found = null;
            for (ScaleCluster c : clusters) {
                if (Math.abs(log2Width - c.log2Representative()) <= LOG2_CLUSTER_TOLERANCE) {
                    found = c;
                    break;
                }
            }
            if (found == null) {
                found = new ScaleCluster(log2Width);
                clusters.add(found);
            }
            found.add(aabb);
        }
        return clusters;
    }

    private static double apparentWidthX(AxisAlignedBoundingBox aabb, double[] frameModelView) {
        if (aabb == null || aabb.getMin() == null || aabb.getMax() == null) {
            return 0.0;
        }
        double[] modelView = aabb.getModelViewMatrix();
        if (modelView == null || modelView.length != 16) {
            modelView = frameModelView;
        }
        if (modelView == null || modelView.length != 16) {
            return Math.abs(aabb.getMax().x() - aabb.getMin().x());
        }
        double tx0 = transformedX(modelView, aabb.getMin());
        double tx1 = transformedX(modelView, aabb.getMax());
        return Math.abs(tx1 - tx0);
    }

    private static double transformedX(double[] m, vsdk.toolkit.common.linealAlgebra.Vector3D p) {
        return m[0] * p.x() + m[4] * p.y() + m[8] * p.z() + m[12];
    }

    private static Color randomColor(int frameId, int clusterIndex) {
        Random rnd = new Random(31L * frameId + 131L * clusterIndex + 17L);
        float r = 0.25f + rnd.nextFloat() * 0.75f;
        float g = 0.25f + rnd.nextFloat() * 0.75f;
        float b = 0.25f + rnd.nextFloat() * 0.75f;
        return new Color(r, g, b);
    }

    private static final class ScaleCluster {
        private final double log2Representative;
        private final List<AxisAlignedBoundingBox> items = new ArrayList<>();

        private ScaleCluster(double log2Representative) {
            this.log2Representative = log2Representative;
        }

        private double log2Representative() {
            return log2Representative;
        }

        private List<AxisAlignedBoundingBox> items() {
            return items;
        }

        private void add(AxisAlignedBoundingBox aabb) {
            items.add(aabb);
        }
    }
}
