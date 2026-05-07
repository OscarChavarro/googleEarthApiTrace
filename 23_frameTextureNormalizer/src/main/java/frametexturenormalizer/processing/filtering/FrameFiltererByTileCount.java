package frametexturenormalizer.processing.filtering;

import java.util.ArrayList;
import java.util.List;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.TileInstance;

public final class FrameFiltererByTileCount {
    public List<FrameData> keepFramesWithMoreThanTiles(List<FrameData> frames, int minExclusive) {
        if (frames == null || frames.isEmpty()) {
            return List.of();
        }
        List<FrameData> out = new ArrayList<>(frames.size());
        for (FrameData frame : frames) {
            if (frame == null) {
                continue;
            }
            int count = 0;
            List<TileInstance> tiles = frame.getTiles();
            if (tiles != null) {
                for (TileInstance tile : tiles) {
                    if (tile != null) {
                        count++;
                    }
                }
            }
            if (count > minExclusive) {
                out.add(frame);
            }
        }
        return out;
    }
}
