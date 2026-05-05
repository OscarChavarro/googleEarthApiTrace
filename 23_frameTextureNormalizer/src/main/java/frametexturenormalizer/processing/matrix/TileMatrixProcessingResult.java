package frametexturenormalizer.processing.matrix;

import java.util.List;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.TileMatrix;

public record TileMatrixProcessingResult(
    List<FrameData> frames,
    List<TileMatrix> matrices
) {
}
