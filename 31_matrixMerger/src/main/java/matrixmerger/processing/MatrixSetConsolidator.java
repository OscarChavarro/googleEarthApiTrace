package matrixmerger.processing;

import java.util.List;
import matrixmerger.model.contract.FrameTileMatrix;

public final class MatrixSetConsolidator {
    private final PairwiseMatrixMerger matrixMerger = new PairwiseMatrixMerger();

    public boolean merge(List<FrameTileMatrix> matrices) {
        if (matrices == null || matrices.size() < 2) {
            return false;
        }

        boolean mergedAny = false;
        int indexA = 0;
        while (matrices.size() > 1 && indexA < matrices.size() - 1) {
            FrameTileMatrix a = matrices.get(indexA);
            FrameTileMatrix b = matrices.get(indexA + 1);
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
