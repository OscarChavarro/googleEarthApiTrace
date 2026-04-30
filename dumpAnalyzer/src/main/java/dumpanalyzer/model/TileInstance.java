package dumpanalyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import vsdk.toolkit.common.linealAlgebra.Vector3D;

public final class TileInstance {
    private final int contentId;
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

    public TileInstance(
        int contentId,
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
        String skipReason
    ) {
        this.contentId = contentId;
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
    }

    public int getContentId() {
        return contentId;
    }

    public Integer getSouthNeighbor() {
        return southNeighbor;
    }

    public Integer getNorthNeighbor() {
        return northNeighbor;
    }

    public Integer getEastNeighbor() {
        return eastNeighbor;
    }

    public Integer getWestNeighbor() {
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
}
