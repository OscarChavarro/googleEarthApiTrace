package dumpanalyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import dumpanalyzer.processing.Direction;
import dumpanalyzer.processing.TriangleMeshVertexComparator;
import dumpanalyzer.processing.TriangleStripTileClassifier;
import dumpanalyzer.processing.TriangleStripTileTopology;
import dumpanalyzer.processing.TriangleStripTileTopology2DirectionMapper;
import dumpanalyzer.processing.uncles.ToUncleRelationship;
import java.util.List;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;

public final class TileInstance {
    public static final int NO_NEIGHBOR = -1;
    public static final String SYNTHETIC_GLOBE_LEVEL_TILE_SKIP_REASON = "synthetic globe-level tile";
    private static final double FULL_RESOLUTION_TOLERANCE = 1e-3;
    private static final TriangleStripTileClassifier TRIANGLE_STRIP_TILE_CLASSIFIER =
        new TriangleStripTileClassifier();
    private static final TriangleStripTileTopology2DirectionMapper TRIANGLE_STRIP_TOPOLOGY_MAPPER =
        new TriangleStripTileTopology2DirectionMapper();

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
    private volatile List<TriangleStripGeometry> cachedTriangleStripGeometries;
    private volatile TriangleStripGeometry cachedTriangleStrip;
    private volatile List<TriangleStripVertex> cachedTriangleStripDeduplicatedVertices;
    private volatile TriangleStripTileTopology cachedTriangleStripTopology;
    private volatile List<Vector3Dd> cachedTriangleStripNorthBorderPoints;
    private volatile List<Vector3Dd> cachedTriangleStripSouthBorderPoints;
    private volatile List<Vector3Dd> cachedTriangleStripEastBorderPoints;
    private volatile List<Vector3Dd> cachedTriangleStripWestBorderPoints;
    private volatile double[] cachedTriangleStripCenter;
    private volatile double cachedTriangleStripVertexEpsilon = Double.NaN;
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
        TriangleStripGeometry cached = cachedTriangleStrip;
        if (cached != null) {
            return cached;
        }
        List<TriangleStripGeometry> geometries = getTriangleStripGeometries();
        if (geometries.size() != 1) {
            return null;
        }
        TriangleStripGeometry geometry = geometries.get(0);
        cachedTriangleStrip = geometry;
        return geometry;
    }

    @JsonIgnore
    public double getTriangleStripVertexEpsilon() {
        double cached = cachedTriangleStripVertexEpsilon;
        if (Double.isFinite(cached)) {
            return cached;
        }
        TriangleStripGeometry geometry = getTriangleStrip();
        double computed = TriangleMeshVertexComparator.vertexEpsilon(geometry);
        cachedTriangleStripVertexEpsilon = computed;
        return computed;
    }

    @JsonIgnore
    public List<TriangleStripVertex> getTriangleStripDeduplicatedVertices() {
        List<TriangleStripVertex> cached = cachedTriangleStripDeduplicatedVertices;
        if (cached != null) {
            return cached;
        }
        TriangleStripGeometry geometry = getTriangleStrip();
        if (geometry == null) {
            return List.of();
        }
        List<TriangleStripVertex> built = List.copyOf(
            TRIANGLE_STRIP_TILE_CLASSIFIER.deduplicateVertices(geometry.vertices(), getTriangleStripVertexEpsilon())
        );
        cachedTriangleStripDeduplicatedVertices = built;
        return built;
    }

    @JsonIgnore
    public TriangleStripTileTopology getTriangleStripTopology() {
        TriangleStripTileTopology cached = cachedTriangleStripTopology;
        if (cached != null) {
            return cached;
        }
        TriangleStripTileTopology built = TRIANGLE_STRIP_TILE_CLASSIFIER.classifyDeduplicatedVertices(
            getTriangleStripDeduplicatedVertices()
        );
        cachedTriangleStripTopology = built;
        return built;
    }

    @JsonIgnore
    public List<Vector3Dd> getTriangleStripBorderPoints(Direction direction) {
        if (direction == null) {
            return List.of();
        }
        return switch (direction) {
            case NORTH -> getOrBuildTriangleStripNorthBorderPoints();
            case SOUTH -> getOrBuildTriangleStripSouthBorderPoints();
            case EAST -> getOrBuildTriangleStripEastBorderPoints();
            case WEST -> getOrBuildTriangleStripWestBorderPoints();
        };
    }

    @JsonIgnore
    public double[] getTriangleStripCenter() {
        double[] cached = cachedTriangleStripCenter;
        if (cached != null) {
            return cached;
        }
        TriangleStripGeometry geometry = getTriangleStrip();
        if (geometry == null || geometry.vertices().isEmpty()) {
            return new double[] {0.0, 0.0, 0.0};
        }
        double sx = 0.0;
        double sy = 0.0;
        double sz = 0.0;
        for (TriangleStripVertex vertex : geometry.vertices()) {
            sx += vertex.x();
            sy += vertex.y();
            sz += vertex.z();
        }
        double inv = 1.0 / geometry.vertices().size();
        double[] built = new double[] {sx * inv, sy * inv, sz * inv};
        cachedTriangleStripCenter = built;
        return built;
    }

    /**
     * Releases derived geometry used while processing a frame. The source strips remain
     * available, so render and analysis code can rebuild these values on demand.
     */
    public void clearTriangleStripProcessingCaches() {
        cachedTriangleStripNorthBorderPoints = null;
        cachedTriangleStripSouthBorderPoints = null;
        cachedTriangleStripEastBorderPoints = null;
        cachedTriangleStripWestBorderPoints = null;
        cachedTriangleStripDeduplicatedVertices = null;
        cachedTriangleStripTopology = null;
        cachedTriangleStripCenter = null;
        cachedTriangleStripVertexEpsilon = Double.NaN;
        cachedTriangleStrip = null;
        cachedTriangleStripGeometries = null;
    }

    @JsonIgnore
    public List<TriangleStripGeometry> getTriangleStripGeometries() {
        List<TriangleStripGeometry> cached = cachedTriangleStripGeometries;
        if (cached != null) {
            return cached;
        }
        if (!"GL_TRIANGLE_STRIP".equals(primitive)) {
            return List.of();
        }
        int count = Math.min(strips.size(), stripTexCoords.size());
        if (count <= 0) {
            return List.of();
        }
        java.util.ArrayList<TriangleStripGeometry> geometries = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            TriangleStripGeometry geometry = buildTriangleStripGeometry(strips.get(i), stripTexCoords.get(i));
            if (geometry != null) {
                geometries.add(geometry);
            }
        }
        List<TriangleStripGeometry> built = List.copyOf(geometries);
        cachedTriangleStripGeometries = built;
        if (built.size() == 1) {
            cachedTriangleStrip = built.get(0);
        }
        return built;
    }

    public static TriangleStripGeometry buildTriangleStripGeometry(List<Vector3Dd> strip, List<Vector3Dd> uv) {
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

    private List<Vector3Dd> getOrBuildTriangleStripNorthBorderPoints() {
        List<Vector3Dd> cached = cachedTriangleStripNorthBorderPoints;
        if (cached != null) {
            return cached;
        }
        List<Vector3Dd> built = buildTriangleStripBorderPoints(Direction.NORTH);
        cachedTriangleStripNorthBorderPoints = built;
        return built;
    }

    private List<Vector3Dd> getOrBuildTriangleStripSouthBorderPoints() {
        List<Vector3Dd> cached = cachedTriangleStripSouthBorderPoints;
        if (cached != null) {
            return cached;
        }
        List<Vector3Dd> built = buildTriangleStripBorderPoints(Direction.SOUTH);
        cachedTriangleStripSouthBorderPoints = built;
        return built;
    }

    private List<Vector3Dd> getOrBuildTriangleStripEastBorderPoints() {
        List<Vector3Dd> cached = cachedTriangleStripEastBorderPoints;
        if (cached != null) {
            return cached;
        }
        List<Vector3Dd> built = buildTriangleStripBorderPoints(Direction.EAST);
        cachedTriangleStripEastBorderPoints = built;
        return built;
    }

    private List<Vector3Dd> getOrBuildTriangleStripWestBorderPoints() {
        List<Vector3Dd> cached = cachedTriangleStripWestBorderPoints;
        if (cached != null) {
            return cached;
        }
        List<Vector3Dd> built = buildTriangleStripBorderPoints(Direction.WEST);
        cachedTriangleStripWestBorderPoints = built;
        return built;
    }

    private List<Vector3Dd> buildTriangleStripBorderPoints(Direction direction) {
        return TRIANGLE_STRIP_TOPOLOGY_MAPPER.directionBorderPoints(
            getTriangleStripDeduplicatedVertices(),
            getTriangleStripTopology(),
            direction
        );
    }
}
