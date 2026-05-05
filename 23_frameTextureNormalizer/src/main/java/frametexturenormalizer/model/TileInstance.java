package frametexturenormalizer.model;

import java.util.List;

public final class TileInstance {
    private final int tileId;
    private final int frameId;
    private final String textureFile;
    private Integer southNeighbor;
    private Integer northNeighbor;
    private Integer eastNeighbor;
    private Integer westNeighbor;
    private final TriangleStripGeometry triangleStrip;
    private final Integer matrixI;
    private final Integer matrixJ;
    private final boolean incorrectMatrixMapping;
    private boolean westCuttingCell;
    private boolean selected;

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
        this(
            tileId,
            frameId,
            textureFile,
            southNeighbor,
            northNeighbor,
            eastNeighbor,
            westNeighbor,
            triangleStrip,
            null,
            null,
            false
        );
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
        this(
            tileId,
            frameId,
            textureFile,
            southNeighbor,
            northNeighbor,
            eastNeighbor,
            westNeighbor,
            triangleStrip,
            matrixI,
            matrixJ,
            false
        );
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
        Integer matrixJ,
        boolean incorrectMatrixMapping
    ) {
        this(
            tileId,
            frameId,
            textureFile,
            southNeighbor,
            northNeighbor,
            eastNeighbor,
            westNeighbor,
            triangleStrip,
            matrixI,
            matrixJ,
            incorrectMatrixMapping,
            false,
            false
        );
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
        Integer matrixJ,
        boolean incorrectMatrixMapping,
        boolean westCuttingCell,
        boolean selected
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
        this.incorrectMatrixMapping = incorrectMatrixMapping;
        this.westCuttingCell = westCuttingCell;
        this.selected = selected;
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

    public void setSouthNeighbor(Integer southNeighbor) {
        this.southNeighbor = southNeighbor;
    }

    public Integer getNorthNeighbor() {
        return northNeighbor;
    }

    public void setNorthNeighbor(Integer northNeighbor) {
        this.northNeighbor = northNeighbor;
    }

    public Integer getEastNeighbor() {
        return eastNeighbor;
    }

    public void setEastNeighbor(Integer eastNeighbor) {
        this.eastNeighbor = eastNeighbor;
    }

    public Integer getWestNeighbor() {
        return westNeighbor;
    }

    public void setWestNeighbor(Integer westNeighbor) {
        this.westNeighbor = westNeighbor;
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

    public boolean isIncorrectMatrixMapping() {
        return incorrectMatrixMapping;
    }

    public boolean isWestCuttingCell() {
        return westCuttingCell;
    }

    public void setWestCuttingCell(boolean westCuttingCell) {
        this.westCuttingCell = westCuttingCell;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public record TriangleStripGeometry(int vertexCount, List<TriangleStripVertex> vertices) {}
    public record TriangleStripVertex(double x, double y, double z, double u, double v) {}
}
