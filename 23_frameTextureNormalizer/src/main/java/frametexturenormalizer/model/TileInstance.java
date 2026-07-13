package frametexturenormalizer.model;

import frametexturenormalizer.model.contract.ScopedTileIds;

import java.util.List;
import frametexturenormalizer.processing.uncles.ToUncleRelationship;

public final class TileInstance {
    private static final double FULL_RESOLUTION_TOLERANCE = 1.0e-6;
    private final int tileId;
    private final int frameId;
    private final String textureFile;
    private Integer southNeighbor;
    private Integer northNeighbor;
    private Integer eastNeighbor;
    private Integer westNeighbor;
    private final TriangleStripGeometry triangleStrip;
    private final double[] modelViewMatrix;
    private final Integer matrixI;
    private final Integer matrixJ;
    private final boolean incorrectMatrixMapping;
    private final List<ToUncleRelationship> uncles;
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
        TriangleStripGeometry triangleStrip,
        double[] modelViewMatrix
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
            modelViewMatrix,
            null,
            null,
            false,
            List.of()
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
        double[] modelViewMatrix,
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
            modelViewMatrix,
            matrixI,
            matrixJ,
            false,
            List.of()
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
        double[] modelViewMatrix,
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
            modelViewMatrix,
            matrixI,
            matrixJ,
            incorrectMatrixMapping,
            List.of(),
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
        double[] modelViewMatrix,
        Integer matrixI,
        Integer matrixJ,
        boolean incorrectMatrixMapping,
        List<ToUncleRelationship> uncles
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
            modelViewMatrix,
            matrixI,
            matrixJ,
            incorrectMatrixMapping,
            uncles,
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
        double[] modelViewMatrix,
        Integer matrixI,
        Integer matrixJ,
        boolean incorrectMatrixMapping,
        List<ToUncleRelationship> uncles,
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
        this.modelViewMatrix = modelViewMatrix == null ? null : modelViewMatrix.clone();
        this.matrixI = matrixI;
        this.matrixJ = matrixJ;
        this.incorrectMatrixMapping = incorrectMatrixMapping;
        this.uncles = uncles == null ? List.of() : List.copyOf(uncles);
        this.westCuttingCell = westCuttingCell;
        this.selected = selected;
    }

    public int getTileId() {
        return tileId;
    }

    public int getFrameId() {
        return frameId;
    }

    public String getScopedId() {
        return ScopedTileIds.formatFromTextureFile(textureFile, frameId, tileId);
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

    public boolean isFullResolutionWithRespectToTexture() {
        TriangleStripGeometry strip = triangleStrip;
        if (strip == null || strip.vertices() == null || strip.vertices().isEmpty()) {
            return false;
        }
        boolean hasAny = false;
        double minU = Double.POSITIVE_INFINITY;
        double maxU = Double.NEGATIVE_INFINITY;
        double minV = Double.POSITIVE_INFINITY;
        double maxV = Double.NEGATIVE_INFINITY;
        for (TriangleStripVertex uv : strip.vertices()) {
            if (uv == null || !Double.isFinite(uv.u()) || !Double.isFinite(uv.v())) {
                continue;
            }
            hasAny = true;
            minU = Math.min(minU, uv.u());
            maxU = Math.max(maxU, uv.u());
            minV = Math.min(minV, uv.v());
            maxV = Math.max(maxV, uv.v());
        }
        if (!hasAny) {
            return false;
        }
        boolean coversU = minU <= FULL_RESOLUTION_TOLERANCE && maxU >= (1.0 - FULL_RESOLUTION_TOLERANCE);
        boolean coversV = minV <= FULL_RESOLUTION_TOLERANCE && maxV >= (1.0 - FULL_RESOLUTION_TOLERANCE);
        return coversU && coversV;
    }

    public double[] getModelViewMatrix() {
        return modelViewMatrix == null ? null : modelViewMatrix.clone();
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

    public List<ToUncleRelationship> getUncles() {
        return uncles;
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
