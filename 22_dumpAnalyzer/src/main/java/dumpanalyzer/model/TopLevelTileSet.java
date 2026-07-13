package dumpanalyzer.model;

import vsdk.toolkit.common.linealAlgebra.Vector3Dd;

public final class TopLevelTileSet {
    private final String contentId;
    private final Vector3Dd center;
    private final Vector3Dd northBorderCenter;
    private final Vector3Dd southBorderCenter;
    private final Vector3Dd eastBorderCenter;
    private final Vector3Dd westBorderCenter;
    private final int triangleStripCount;
    private final boolean drawSourceTile;
    private volatile String detectedNorthNeighborContentId;
    private volatile String detectedSouthNeighborContentId;
    private volatile String detectedEastNeighborContentId;
    private volatile String detectedWestNeighborContentId;

    public TopLevelTileSet(
        String contentId,
        Vector3Dd center,
        Vector3Dd northBorderCenter,
        Vector3Dd southBorderCenter,
        Vector3Dd eastBorderCenter,
        Vector3Dd westBorderCenter,
        int triangleStripCount,
        boolean drawSourceTile
    ) {
        this.contentId = contentId;
        this.center = center == null ? null : Vector3Dd.copyOf(center);
        this.northBorderCenter = northBorderCenter == null ? null : Vector3Dd.copyOf(northBorderCenter);
        this.southBorderCenter = southBorderCenter == null ? null : Vector3Dd.copyOf(southBorderCenter);
        this.eastBorderCenter = eastBorderCenter == null ? null : Vector3Dd.copyOf(eastBorderCenter);
        this.westBorderCenter = westBorderCenter == null ? null : Vector3Dd.copyOf(westBorderCenter);
        this.triangleStripCount = triangleStripCount;
        this.drawSourceTile = drawSourceTile;
    }

    public String getContentId() {
        return contentId;
    }

    public Vector3Dd getCenter() {
        return center == null ? null : Vector3Dd.copyOf(center);
    }

    public Vector3Dd getNorthBorderCenter() {
        return northBorderCenter == null ? null : Vector3Dd.copyOf(northBorderCenter);
    }

    public Vector3Dd getSouthBorderCenter() {
        return southBorderCenter == null ? null : Vector3Dd.copyOf(southBorderCenter);
    }

    public Vector3Dd getEastBorderCenter() {
        return eastBorderCenter == null ? null : Vector3Dd.copyOf(eastBorderCenter);
    }

    public Vector3Dd getWestBorderCenter() {
        return westBorderCenter == null ? null : Vector3Dd.copyOf(westBorderCenter);
    }

    public int getTriangleStripCount() {
        return triangleStripCount;
    }

    public boolean shouldDrawSourceTile() {
        return drawSourceTile;
    }

    public String getDetectedNorthNeighborContentId() {
        return detectedNorthNeighborContentId;
    }

    public String getDetectedSouthNeighborContentId() {
        return detectedSouthNeighborContentId;
    }

    public String getDetectedEastNeighborContentId() {
        return detectedEastNeighborContentId;
    }

    public String getDetectedWestNeighborContentId() {
        return detectedWestNeighborContentId;
    }

    public void setDetectedNeighborContentIds(String south, String north, String east, String west) {
        this.detectedSouthNeighborContentId = south;
        this.detectedNorthNeighborContentId = north;
        this.detectedEastNeighborContentId = east;
        this.detectedWestNeighborContentId = west;
    }
}
