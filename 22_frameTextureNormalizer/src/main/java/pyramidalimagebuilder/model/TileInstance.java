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
    private final Integer matrixI;
    private final Integer matrixJ;

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
        this(tileId, frameId, textureFile, southNeighbor, northNeighbor, eastNeighbor, westNeighbor, triangleStrip, null, null);
    }

    public TileInstance(
        int tileId,
        int frameId,
        String textureFile,
        Integer southNeighbor,
        Integer northNeighbor,
        Integer eastNeighbor,
        Integer westNeighbor,
        TriangleStripGeometry triangleStrip,
        Integer matrixI,
        Integer matrixJ
    ) {
        this.tileId = tileId;
        this.frameId = frameId;
        this.textureFile = textureFile;
        this.southNeighbor = southNeighbor;
        this.northNeighbor = northNeighbor;
        this.eastNeighbor = eastNeighbor;
        this.westNeighbor = westNeighbor;
        this.triangleStrip = triangleStrip;
        this.matrixI = matrixI;
        this.matrixJ = matrixJ;
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

    public Integer getMatrixI() {
        return matrixI;
    }

    public Integer getMatrixJ() {
        return matrixJ;
    }

    public record TriangleStripGeometry(int vertexCount, List<TriangleStripVertex> vertices) {}
    public record TriangleStripVertex(double x, double y, double z, double u, double v) {}
}
