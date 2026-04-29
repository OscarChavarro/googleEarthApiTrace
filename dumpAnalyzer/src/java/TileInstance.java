package dumpanalyzer;

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

    public TileInstance(
        int contentId,
        Integer southNeighbor,
        Integer northNeighbor,
        Integer eastNeighbor,
        Integer westNeighbor,
        Vector3D min,
        Vector3D max,
        List<Vector3D> points
    ) {
        this.contentId = contentId;
        this.southNeighbor = southNeighbor;
        this.northNeighbor = northNeighbor;
        this.eastNeighbor = eastNeighbor;
        this.westNeighbor = westNeighbor;
        this.min = min == null ? null : Vector3D.copyOf(min);
        this.max = max == null ? null : Vector3D.copyOf(max);
        this.points = points == null ? List.of() : List.copyOf(points);
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
}
