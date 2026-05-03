package pyramidalimagebuilder.processing.matrix;

import java.util.List;
import pyramidalimagebuilder.model.FrameData;
import pyramidalimagebuilder.model.TileMatrix;

public record TileMatrixProcessingResult(
    List<FrameData> frames,
    List<TileMatrix> matrices
) {
}
