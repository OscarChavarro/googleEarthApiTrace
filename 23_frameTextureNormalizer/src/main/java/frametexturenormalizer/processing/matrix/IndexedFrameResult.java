package frametexturenormalizer.processing.matrix;

import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.TileMatrix;

public record IndexedFrameResult(int index, FrameData frame, TileMatrix matrix) {
}
