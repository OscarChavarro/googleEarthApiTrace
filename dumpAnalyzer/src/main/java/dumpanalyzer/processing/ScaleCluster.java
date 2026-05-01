package dumpanalyzer.processing;

import dumpanalyzer.model.AxisAlignedBoundingBox;
import java.util.ArrayList;
import java.util.List;

public final class ScaleCluster {
    private final double log2RepresentativeX;
    private final double log2RepresentativeY;
    private final List<AxisAlignedBoundingBox> items = new ArrayList<>();

    public ScaleCluster(double log2RepresentativeX, double log2RepresentativeY) {
        this.log2RepresentativeX = log2RepresentativeX;
        this.log2RepresentativeY = log2RepresentativeY;
    }

    public double log2RepresentativeX() {
        return log2RepresentativeX;
    }

    public double log2RepresentativeY() {
        return log2RepresentativeY;
    }

    public List<AxisAlignedBoundingBox> items() {
        return items;
    }

    public void add(AxisAlignedBoundingBox aabb) {
        items.add(aabb);
    }
}
