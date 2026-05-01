package dumpanalyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import vsdk.toolkit.common.linealAlgebra.Vector3D;

public final class TileInstance {
    public static final int NO_NEIGHBOR = -1;
    private static final double FULL_RESOLUTION_TOLERANCE = 1e-3;

    private final int contentId;
    private volatile String textureFile;
    private final Integer southNeighbor;
    private final Integer northNeighbor;
    private final Integer eastNeighbor;
    private final Integer westNeighbor;
    private final Vector3D min;
    private final Vector3D max;
    private final List<Vector3D> points;
    private final List<List<Vector3D>> strips;
    private final List<List<Vector3D>> stripTexCoords;
    private final String primitive;
    private final int parserCall;
    private final long glCall;
    private final int vertexArraySize;
    private final int indexArraySize;
    private final boolean skipped;
    private final String skipReason;
    private final double[] projectionMatrix;
    private final double[] modelViewMatrix;
    private volatile int detectedSouthNeighborIndex = NO_NEIGHBOR;
    private volatile int detectedNorthNeighborIndex = NO_NEIGHBOR;
    private volatile int detectedEastNeighborIndex = NO_NEIGHBOR;
    private volatile int detectedWestNeighborIndex = NO_NEIGHBOR;

    public TileInstance(
        int contentId,
        String textureFile,
        Integer southNeighbor,
        Integer northNeighbor,
        Integer eastNeighbor,
        Integer westNeighbor,
        Vector3D min,
        Vector3D max,
        List<Vector3D> points,
        List<List<Vector3D>> strips,
        List<List<Vector3D>> stripTexCoords,
        String primitive,
        int parserCall,
        long glCall,
        int vertexArraySize,
        int indexArraySize,
        boolean skipped,
        String skipReason,
        double[] projectionMatrix,
        double[] modelViewMatrix
    ) {
        this.contentId = contentId;
        this.textureFile = textureFile;
        this.southNeighbor = southNeighbor;
        this.northNeighbor = northNeighbor;
        this.eastNeighbor = eastNeighbor;
        this.westNeighbor = westNeighbor;
        this.min = min == null ? null : Vector3D.copyOf(min);
        this.max = max == null ? null : Vector3D.copyOf(max);
        this.points = points == null ? List.of() : List.copyOf(points);
        this.strips = strips == null ? List.of() : strips.stream().map(List::copyOf).toList();
        this.stripTexCoords = stripTexCoords == null ? List.of() : stripTexCoords.stream().map(List::copyOf).toList();
        this.primitive = primitive == null ? "n/a" : primitive;
        this.parserCall = parserCall;
        this.glCall = glCall;
        this.vertexArraySize = vertexArraySize;
        this.indexArraySize = indexArraySize;
        this.skipped = skipped;
        this.skipReason = skipReason == null ? "" : skipReason;
        this.projectionMatrix = projectionMatrix == null ? null : projectionMatrix.clone();
        this.modelViewMatrix = modelViewMatrix == null ? null : modelViewMatrix.clone();
    }

    public int getContentId() {
        return contentId;
    }

    public String getTextureFile() {
        return textureFile;
    }

    public void setTextureFile(String textureFile) {
        this.textureFile = textureFile;
    }

    public Integer getSouthNeighbor() {
        if (southNeighbor != null) {
            return southNeighbor;
        }
        return detectedSouthNeighborIndex;
    }

    public Integer getNorthNeighbor() {
        if (northNeighbor != null) {
            return northNeighbor;
        }
        return detectedNorthNeighborIndex;
    }

    public Integer getEastNeighbor() {
        if (eastNeighbor != null) {
            return eastNeighbor;
        }
        return detectedEastNeighborIndex;
    }

    public Integer getWestNeighbor() {
        if (westNeighbor != null) {
            return westNeighbor;
        }
        return detectedWestNeighborIndex;
    }

    public Vector3D getMin() {
        return min;
    }

    public Vector3D getMax() {
        return max;
    }

    @JsonIgnore
    public List<Vector3D> getPoints() {
        return points;
    }

    public int getNumberOfPoints() {
        return points.size();
    }

    @JsonIgnore
    public List<List<Vector3D>> getStrips() {
        return strips;
    }

    @JsonIgnore
    public List<List<Vector3D>> getStripTexCoords() {
        return stripTexCoords;
    }

    public String getPrimitive() {
        return primitive;
    }

    public int getParserCall() {
        return parserCall;
    }

    public long getGlCall() {
        return glCall;
    }

    public int getVertexArraySize() {
        return vertexArraySize;
    }

    public int getIndexArraySize() {
        return indexArraySize;
    }

    public boolean isSkipped() {
        return skipped;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public double[] getProjectionMatrix() {
        return projectionMatrix == null ? null : projectionMatrix.clone();
    }

    public double[] getModelViewMatrix() {
        return modelViewMatrix == null ? null : modelViewMatrix.clone();
    }

    @JsonIgnore
    public int getDetectedSouthNeighborIndex() {
        return detectedSouthNeighborIndex;
    }

    @JsonIgnore
    public int getDetectedNorthNeighborIndex() {
        return detectedNorthNeighborIndex;
    }

    @JsonIgnore
    public int getDetectedEastNeighborIndex() {
        return detectedEastNeighborIndex;
    }

    @JsonIgnore
    public int getDetectedWestNeighborIndex() {
        return detectedWestNeighborIndex;
    }

    public void setDetectedNeighbors(int south, int north, int east, int west) {
        this.detectedSouthNeighborIndex = south;
        this.detectedNorthNeighborIndex = north;
        this.detectedEastNeighborIndex = east;
        this.detectedWestNeighborIndex = west;
    }

    public boolean isFullResolutionWithRespectToTexture() {
        if (stripTexCoords.isEmpty()) {
            return false;
        }
        boolean hasAny = false;
        double minU = Double.POSITIVE_INFINITY;
        double maxU = Double.NEGATIVE_INFINITY;
        double minV = Double.POSITIVE_INFINITY;
        double maxV = Double.NEGATIVE_INFINITY;

        for (List<Vector3D> strip : stripTexCoords) {
            if (strip == null || strip.isEmpty()) {
                continue;
            }
            for (Vector3D uv : strip) {
                if (uv == null || !Double.isFinite(uv.x()) || !Double.isFinite(uv.y())) {
                    continue;
                }
                hasAny = true;
                minU = Math.min(minU, uv.x());
                maxU = Math.max(maxU, uv.x());
                minV = Math.min(minV, uv.y());
                maxV = Math.max(maxV, uv.y());
            }
        }
        if (!hasAny) {
            return false;
        }

        boolean coversU = minU <= FULL_RESOLUTION_TOLERANCE && maxU >= (1.0 - FULL_RESOLUTION_TOLERANCE);
        boolean coversV = minV <= FULL_RESOLUTION_TOLERANCE && maxV >= (1.0 - FULL_RESOLUTION_TOLERANCE);
        return coversU && coversV;
    }
}
