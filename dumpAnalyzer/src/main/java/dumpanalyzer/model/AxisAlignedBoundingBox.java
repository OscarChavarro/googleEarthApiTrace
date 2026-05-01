package dumpanalyzer.model;

import vsdk.toolkit.common.linealAlgebra.Vector3D;

public final class AxisAlignedBoundingBox {
    private final Vector3D min;
    private final Vector3D max;
    private final int textureId;
    private final double[] modelViewMatrix;

    public AxisAlignedBoundingBox(Vector3D min, Vector3D max, int textureId, double[] modelViewMatrix) {
        this.min = min == null ? null : Vector3D.copyOf(min);
        this.max = max == null ? null : Vector3D.copyOf(max);
        this.textureId = textureId;
        this.modelViewMatrix = modelViewMatrix == null ? null : modelViewMatrix.clone();
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
}
