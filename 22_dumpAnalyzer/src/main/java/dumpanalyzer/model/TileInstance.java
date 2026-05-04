package dumpanalyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import vsdk.toolkit.common.linealAlgebra.Vector3D;

public final class TileInstance {
    public static final int NO_NEIGHBOR = -1;
    private static final double FULL_RESOLUTION_TOLERANCE = 1e-3;

    private final String contentId;
    private volatile String textureFile;
    private final String southNeighbor;
    private final String northNeighbor;
    private final String eastNeighbor;
    private final String westNeighbor;
    private final Vector3D min;
    private final Vector3D max;
    private final List<Vector3D> points;
    private final List<List<Vector3D>> strips;
    private final List<List<Vector3D>> stripTexCoords;
    private final List<List<Vector3D>> lineStrips;
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
        String contentId,
        String textureFile,
        String southNeighbor,
        String northNeighbor,
        String eastNeighbor,
        String westNeighbor,
        Vector3D min,
        Vector3D max,
        List<Vector3D> points,
        List<List<Vector3D>> strips,
        List<List<Vector3D>> stripTexCoords,
        List<List<Vector3D>> lineStrips,
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
        this.lineStrips = lineStrips == null ? List.of() : lineStrips.stream().map(List::copyOf).toList();
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

    public String getContentId() {
        return contentId;
    }

    public String getTextureFile() {
        return textureFile;
    }

    public void setTextureFile(String textureFile) {
        this.textureFile = textureFile;
    }

    public String getSouthNeighbor() {
        return southNeighbor;
    }

    public String getNorthNeighbor() {
        return northNeighbor;
    }

    public String getEastNeighbor() {
        return eastNeighbor;
    }

    public String getWestNeighbor() {
        return westNeighbor;
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

    @JsonIgnore
    public List<List<Vector3D>> getLineStrips() {
        return lineStrips;
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

    @JsonIgnore
    public double[] getProjectionMatrix() {
        return projectionMatrix == null ? null : projectionMatrix.clone();
    }

    @JsonIgnore
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

    public TriangleStripGeometry getTriangleStrip() {
        if (skipped || !"GL_TRIANGLE_STRIP".equals(primitive)) {
            return null;
        }
        if (strips.size() != 1 || stripTexCoords.size() != 1) {
            return null;
        }
        List<Vector3D> strip = strips.get(0);
        List<Vector3D> uv = stripTexCoords.get(0);
        if (strip == null || uv == null || strip.size() < 3 || strip.size() != uv.size()) {
            return null;
        }
        List<TriangleStripVertex> vertices = new java.util.ArrayList<>(strip.size());
        for (int i = 0; i < strip.size(); i++) {
            Vector3D p = strip.get(i);
            Vector3D t = uv.get(i);
            vertices.add(new TriangleStripVertex(p.x(), p.y(), p.z(), t.x(), t.y()));
        }
        return new TriangleStripGeometry(vertices.size(), List.copyOf(vertices));
    }

    public record TriangleStripGeometry(int vertexCount, List<TriangleStripVertex> vertices) {}
    public record TriangleStripVertex(double x, double y, double z, double u, double v) {}
}
