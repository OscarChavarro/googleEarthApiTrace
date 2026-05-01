package dumpanalyzer.model;

import java.awt.Color;
import java.util.List;
import vsdk.toolkit.common.linealAlgebra.Vector3D;

public final class AxisAlignedBoundingBox {
    private final Vector3D min;
    private final Vector3D max;
    private final int textureId;
    private final double[] modelViewMatrix;
    private final List<Vector3D> geometryPoints;
    private volatile Color color = Color.YELLOW;
    private volatile Integer projectedMinX;
    private volatile Integer projectedMinY;
    private volatile Integer projectedMaxX;
    private volatile Integer projectedMaxY;

    public AxisAlignedBoundingBox(
        Vector3D min,
        Vector3D max,
        int textureId,
        double[] modelViewMatrix,
        List<Vector3D> geometryPoints
    ) {
        this.min = min == null ? null : Vector3D.copyOf(min);
        this.max = max == null ? null : Vector3D.copyOf(max);
        this.textureId = textureId;
        this.modelViewMatrix = modelViewMatrix == null ? null : modelViewMatrix.clone();
        this.geometryPoints = geometryPoints == null ? List.of() : List.copyOf(geometryPoints);
    }

    public Vector3D getMin() {
        return min;
    }

    public Vector3D getMax() {
        return max;
    }

    public int getTextureId() {
        return textureId;
    }

    public double[] getModelViewMatrix() {
        return modelViewMatrix == null ? null : modelViewMatrix.clone();
    }

    public List<Vector3D> getGeometryPoints() {
        return geometryPoints;
    }

    public Color getColor() {
        return color;
    }

    public void setColor(Color color) {
        this.color = color == null ? Color.YELLOW : color;
    }

    public void setProjectedBounds(Integer minX, Integer minY, Integer maxX, Integer maxY) {
        this.projectedMinX = minX;
        this.projectedMinY = minY;
        this.projectedMaxX = maxX;
        this.projectedMaxY = maxY;
    }

    public Integer getProjectedMinX() {
        return projectedMinX;
    }

    public Integer getProjectedMinY() {
        return projectedMinY;
    }

    public Integer getProjectedMaxX() {
        return projectedMaxX;
    }

    public Integer getProjectedMaxY() {
        return projectedMaxY;
    }
}
