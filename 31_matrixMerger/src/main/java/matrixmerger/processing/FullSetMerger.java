package matrixmerger.processing;

import java.util.List;
import matrixmerger.io.TileMatrix;

public final class FullSetMerger {
    private final MatrixMerger matrixMerger = new MatrixMerger();

    public boolean merge(List<TileMatrix> matrices) {
        if (matrices == null || matrices.size() < 2) {
            return false;
        }

        boolean mergedAny = false;
        int indexA = 0;
        while (matrices.size() > 1 && indexA < matrices.size() - 1) {
            TileMatrix a = matrices.get(indexA);
            TileMatrix b = matrices.get(indexA + 1);
            if (matrixMerger.merge(a, b)) {
                matrices.remove(indexA + 1);
                mergedAny = true;
            } else {
                indexA++;
            }
        }
        return mergedAny;
    }
}
