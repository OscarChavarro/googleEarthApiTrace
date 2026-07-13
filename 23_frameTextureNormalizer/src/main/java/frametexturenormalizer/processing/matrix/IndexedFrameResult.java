package frametexturenormalizer.processing.matrix;

import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.TileMatrix;
import java.util.List;

public record IndexedFrameResult(int index, FrameData frame, List<TileMatrix> matrices) {
}
