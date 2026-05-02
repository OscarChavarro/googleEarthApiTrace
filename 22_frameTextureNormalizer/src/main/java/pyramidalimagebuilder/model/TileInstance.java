package pyramidalimagebuilder.model;

import java.util.List;

public final class TileInstance {
    private final int tileId;
    private final int frameId;
    private final String textureFile;
    private final Integer southNeighbor;
    private final Integer northNeighbor;
    private final Integer eastNeighbor;
    private final Integer westNeighbor;
    private final TriangleStripGeometry triangleStrip;

    public TileInstance(
        int tileId,
        int frameId,
        String textureFile,
        Integer southNeighbor,
        Integer northNeighbor,
        Integer eastNeighbor,
        Integer westNeighbor,
        TriangleStripGeometry triangleStrip
    ) {
        this.tileId = tileId;
        this.frameId = frameId;
        this.textureFile = textureFile;
        this.southNeighbor = southNeighbor;
        this.northNeighbor = northNeighbor;
        this.eastNeighbor = eastNeighbor;
        this.westNeighbor = westNeighbor;
        this.triangleStrip = triangleStrip;
    }

    public int getTileId() {
        return tileId;
    }

    public int getFrameId() {
        return frameId;
    }

    public String getTextureFile() {
        return textureFile;
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

    public TriangleStripGeometry getTriangleStrip() {
        return triangleStrip;
    }

    public record TriangleStripGeometry(int vertexCount, List<TriangleStripVertex> vertices) {}
    public record TriangleStripVertex(double x, double y, double z, double u, double v) {}
}
