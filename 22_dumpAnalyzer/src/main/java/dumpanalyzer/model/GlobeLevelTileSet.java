package dumpanalyzer.model;

import vsdk.toolkit.common.linealAlgebra.Vector3D;

public final class GlobeLevelTileSet {
    private final String contentId;
    private final Vector3D center;
    private final Vector3D northBorderCenter;
    private final Vector3D southBorderCenter;
    private final Vector3D eastBorderCenter;
    private final Vector3D westBorderCenter;
    private final int triangleStripCount;
    private final boolean drawSourceTile;
    private volatile String detectedNorthNeighborContentId;
    private volatile String detectedSouthNeighborContentId;
    private volatile String detectedEastNeighborContentId;
    private volatile String detectedWestNeighborContentId;

    public GlobeLevelTileSet(
        String contentId,
        Vector3D center,
        Vector3D northBorderCenter,
        Vector3D southBorderCenter,
        Vector3D eastBorderCenter,
        Vector3D westBorderCenter,
        int triangleStripCount,
        boolean drawSourceTile
    ) {
        this.contentId = contentId;
        this.center = center == null ? null : Vector3D.copyOf(center);
        this.northBorderCenter = northBorderCenter == null ? null : Vector3D.copyOf(northBorderCenter);
        this.southBorderCenter = southBorderCenter == null ? null : Vector3D.copyOf(southBorderCenter);
        this.eastBorderCenter = eastBorderCenter == null ? null : Vector3D.copyOf(eastBorderCenter);
        this.westBorderCenter = westBorderCenter == null ? null : Vector3D.copyOf(westBorderCenter);
        this.triangleStripCount = triangleStripCount;
        this.drawSourceTile = drawSourceTile;
    }

    public String getContentId() {
        return contentId;
    }

    public Vector3D getCenter() {
        return center == null ? null : Vector3D.copyOf(center);
    }

    public Vector3D getNorthBorderCenter() {
        return northBorderCenter == null ? null : Vector3D.copyOf(northBorderCenter);
    }

    public Vector3D getSouthBorderCenter() {
        return southBorderCenter == null ? null : Vector3D.copyOf(southBorderCenter);
    }

    public Vector3D getEastBorderCenter() {
        return eastBorderCenter == null ? null : Vector3D.copyOf(eastBorderCenter);
    }

    public Vector3D getWestBorderCenter() {
        return westBorderCenter == null ? null : Vector3D.copyOf(westBorderCenter);
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
