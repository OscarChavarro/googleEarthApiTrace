package dumpanalyzer;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

public class TileInstance {
    private final int contentId;
    private final Integer southNeighbor;
    private final Integer northNeighbor;
    private final Integer eastNeighbor;
    private final Integer westNeighbor;
    private Double minX;
    private Double minY;
    private Double minZ;
    private Double maxX;
    private Double maxY;
    private Double maxZ;

    public TileInstance(
        int contentId,
        Integer southNeighbor,
        Integer northNeighbor,
        Integer eastNeighbor,
        Integer westNeighbor
    ) {
        this.contentId = contentId;
        this.southNeighbor = southNeighbor;
        this.northNeighbor = northNeighbor;
        this.eastNeighbor = eastNeighbor;
        this.westNeighbor = westNeighbor;
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

    public List<Double> getMin() {
        if (minX == null || minY == null || minZ == null) {
            return null;
        }
        return List.of(minX, minY, minZ);
    }

    public List<Double> getMax() {
        if (maxX == null || maxY == null || maxZ == null) {
            return null;
        }
        return List.of(maxX, maxY, maxZ);
    }

    @JsonIgnore
    public Double getMinX() {
        return minX;
    }

    @JsonIgnore
    public Double getMinY() {
        return minY;
    }

    @JsonIgnore
    public Double getMinZ() {
        return minZ;
    }

    @JsonIgnore
    public Double getMaxX() {
        return maxX;
    }

    @JsonIgnore
    public Double getMaxY() {
        return maxY;
    }

    @JsonIgnore
    public Double getMaxZ() {
        return maxZ;
    }

    public void mergeBounds(double bxMin, double byMin, double bzMin, double bxMax, double byMax, double bzMax) {
        if (minX == null || bxMin < minX) {
            minX = bxMin;
        }
        if (minY == null || byMin < minY) {
            minY = byMin;
        }
        if (minZ == null || bzMin < minZ) {
            minZ = bzMin;
        }
        if (maxX == null || bxMax > maxX) {
            maxX = bxMax;
        }
        if (maxY == null || byMax > maxY) {
            maxY = byMax;
        }
        if (maxZ == null || bzMax > maxZ) {
            maxZ = bzMax;
        }
    }

    @Override
    public String toString() {
        return "TileInstance{" +
            "contentId=" + contentId +
            ", southNeighbor=" + southNeighbor +
            ", northNeighbor=" + northNeighbor +
            ", eastNeighbor=" + eastNeighbor +
            ", westNeighbor=" + westNeighbor +
            ", minX=" + minX +
            ", minY=" + minY +
            ", minZ=" + minZ +
            ", maxX=" + maxX +
            ", maxY=" + maxY +
            ", maxZ=" + maxZ +
            '}';
    }
}
