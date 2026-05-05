package frametexturenormalizer.io;

import java.util.List;
import frametexturenormalizer.model.TileMatrix;

public final class TileMatrixExporter {
    public void export(List<TileMatrix> matrices) {
        if (matrices == null || matrices.isEmpty()) {
            return;
        }
        for (TileMatrix matrix : matrices) {
            MatrixWriter.writeMatrixJson(matrix);
        }
    }
}
