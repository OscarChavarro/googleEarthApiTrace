package dumpanalyzer.model;

import java.awt.Color;
import java.util.List;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;

public final class AxisAlignedBoundingBox {
    private final Vector3Dd min;
    private final Vector3Dd max;
    private final String textureId;
    private final int sourceTileIndex;
    private final double[] modelViewMatrix;
    private final List<Vector3Dd> geometryPoints;
    private volatile Color color = Color.YELLOW;
    private volatile Integer projectedMinX;
    private volatile Integer projectedMinY;
    private volatile Integer projectedMaxX;
    private volatile Integer projectedMaxY;

    public AxisAlignedBoundingBox(
        Vector3Dd min,
        Vector3Dd max,
        String textureId,
        int sourceTileIndex,
        double[] modelViewMatrix,
        List<Vector3Dd> geometryPoints
    ) {
        this.min = min == null ? null : Vector3Dd.copyOf(min);
        this.max = max == null ? null : Vector3Dd.copyOf(max);
        this.textureId = textureId;
        this.sourceTileIndex = sourceTileIndex;
        this.modelViewMatrix = modelViewMatrix == null ? null : modelViewMatrix.clone();
        this.geometryPoints = geometryPoints == null ? List.of() : List.copyOf(geometryPoints);
    }

    public Vector3Dd getMin() {
        return min;
    }

    public Vector3Dd getMax() {
        return max;
    }

    public String getTextureId() {
        return textureId;
    }

    public int getSourceTileIndex() {
        return sourceTileIndex;
    }

    public double[] getModelViewMatrix() {
        return modelViewMatrix == null ? null : modelViewMatrix.clone();
    }

    public List<Vector3Dd> getGeometryPoints() {
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
