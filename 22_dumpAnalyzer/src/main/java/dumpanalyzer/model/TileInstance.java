package dumpanalyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import dumpanalyzer.processing.uncles.ToUncleRelationship;
import java.util.List;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;

public final class TileInstance {
    public static final int NO_NEIGHBOR = -1;
    public static final String SYNTHETIC_GLOBE_LEVEL_TILE_SKIP_REASON = "synthetic globe-level tile";
    private static final double FULL_RESOLUTION_TOLERANCE = 1e-3;

    private final String contentId;
    private volatile String textureFile;
    private final String southNeighbor;
    private final String northNeighbor;
    private final String eastNeighbor;
    private final String westNeighbor;
    private final Vector3Dd min;
    private final Vector3Dd max;
    private final List<Vector3Dd> points;
    private final List<List<Vector3Dd>> strips;
    private final List<List<Vector3Dd>> stripTexCoords;
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
    private volatile String detectedSouthNeighborContentId;
    private volatile String detectedNorthNeighborContentId;
    private volatile String detectedEastNeighborContentId;
    private volatile String detectedWestNeighborContentId;
    private volatile List<ToUncleRelationship> uncles;
    private volatile TopLevelTileSet globeLevelTileSet;

    public TileInstance(
        String contentId,
        String textureFile,
        String southNeighbor,
        String northNeighbor,
        String eastNeighbor,
        String westNeighbor,
        Vector3Dd min,
        Vector3Dd max,
        List<Vector3Dd> points,
        List<List<Vector3Dd>> strips,
        List<List<Vector3Dd>> stripTexCoords,
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
        this.min = min == null ? null : Vector3Dd.copyOf(min);
        this.max = max == null ? null : Vector3Dd.copyOf(max);
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
        this.uncles = List.of();
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
        return southNeighbor != null ? southNeighbor : detectedSouthNeighborContentId;
    }

    public String getNorthNeighbor() {
        return northNeighbor != null ? northNeighbor : detectedNorthNeighborContentId;
    }

    public String getEastNeighbor() {
        return eastNeighbor != null ? eastNeighbor : detectedEastNeighborContentId;
    }

    public String getWestNeighbor() {
        return westNeighbor != null ? westNeighbor : detectedWestNeighborContentId;
    }

    public List<ToUncleRelationship> getUncles() {
        return uncles;
    }

    public Vector3Dd getMin() {
        return min;
    }

    public Vector3Dd getMax() {
        return max;
    }

    @JsonIgnore
    public List<Vector3Dd> getPoints() {
        return points;
    }

    public int getNumberOfPoints() {
        return points.size();
    }

    @JsonIgnore
    public List<List<Vector3Dd>> getStrips() {
        return strips;
    }

    @JsonIgnore
    public List<List<Vector3Dd>> getStripTexCoords() {
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

    @JsonIgnore
    public double[] getProjectionMatrix() {
        return projectionMatrix == null ? null : projectionMatrix.clone();
    }

    @JsonIgnore
    public double[] getModelViewMatrix() {
        return modelViewMatrix == null ? null : modelViewMatrix.clone();
    }

    @JsonProperty("modelViewMatrix")
    public double[] getModelViewMatrixJson() {
        return getModelViewMatrix();
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

    public void setDetectedNeighborContentIds(String south, String north, String east, String west) {
        this.detectedSouthNeighborContentId = south;
        this.detectedNorthNeighborContentId = north;
        this.detectedEastNeighborContentId = east;
        this.detectedWestNeighborContentId = west;
    }

    public void setUncles(List<ToUncleRelationship> uncles) {
        this.uncles = uncles == null ? List.of() : List.copyOf(uncles);
    }

    @JsonIgnore
    public TopLevelTileSet getGlobeLevelTileSet() {
        return globeLevelTileSet;
    }

    public void setGlobeLevelTileSet(TopLevelTileSet globeLevelTileSet) {
        this.globeLevelTileSet = globeLevelTileSet;
    }

    public boolean isSyntheticGlobeLevelTile() {
        return SYNTHETIC_GLOBE_LEVEL_TILE_SKIP_REASON.equals(skipReason);
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

        for (List<Vector3Dd> strip : stripTexCoords) {
            if (strip == null || strip.isEmpty()) {
                continue;
            }
            for (Vector3Dd uv : strip) {
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
        List<TriangleStripGeometry> geometries = getTriangleStripGeometries();
        if (geometries.size() != 1) {
            return null;
        }
        return geometries.get(0);
    }

    @JsonIgnore
    public List<TriangleStripGeometry> getTriangleStripGeometries() {
        if (!"GL_TRIANGLE_STRIP".equals(primitive)) {
            return List.of();
        }
        int count = Math.min(strips.size(), stripTexCoords.size());
        if (count <= 0) {
            return List.of();
        }
        java.util.ArrayList<TriangleStripGeometry> geometries = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            TriangleStripGeometry geometry = toTriangleStripGeometry(strips.get(i), stripTexCoords.get(i));
            if (geometry != null) {
                geometries.add(geometry);
            }
        }
        return List.copyOf(geometries);
    }

    private static TriangleStripGeometry toTriangleStripGeometry(List<Vector3Dd> strip, List<Vector3Dd> uv) {
        if (strip == null || uv == null || strip.size() < 3 || strip.size() != uv.size()) {
            return null;
        }
        List<TriangleStripVertex> vertices = new java.util.ArrayList<>(strip.size());
        for (int i = 0; i < strip.size(); i++) {
            Vector3Dd p = strip.get(i);
            Vector3Dd t = uv.get(i);
            vertices.add(new TriangleStripVertex(p.x(), p.y(), p.z(), t.x(), t.y()));
        }
        return new TriangleStripGeometry(vertices.size(), List.copyOf(vertices));
    }

    public record TriangleStripGeometry(int vertexCount, List<TriangleStripVertex> vertices) {}
    public record TriangleStripVertex(double x, double y, double z, double u, double v) {}
}
