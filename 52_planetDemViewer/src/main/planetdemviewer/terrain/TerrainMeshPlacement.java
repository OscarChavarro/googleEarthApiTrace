package planetdemviewer.terrain;

/**
 * How a tile's mesh coordinates map to the world: the same scale and translation as
 * the model matrix of the terrain. Vertical exaggeration only affects z.
 */
public record TerrainMeshPlacement(double offsetX, double offsetY, double offsetZ,
                                   double scale, double heightExaggeration) {
    double worldX(double meshX) {
        return offsetX + scale * meshX;
    }

    double worldY(double meshY) {
        return offsetY + scale * meshY;
    }

    double worldZ(double meshZ) {
        return offsetZ + verticalScale() * meshZ;
    }

    double verticalScale() {
        return scale * heightExaggeration;
    }
}
