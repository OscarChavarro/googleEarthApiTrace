package frametexturenormalizer.processing.filtering;

import java.util.ArrayList;
import java.util.List;
import frametexturenormalizer.model.FrameData;

public final class TileFilteringByErrored {
    public List<FrameData> removeErroredFrames(List<FrameData> frames) {
        if (frames == null || frames.isEmpty()) {
            return List.of();
        }

        List<FrameData> out = new ArrayList<>(frames.size());
        List<Integer> removedIds = new ArrayList<>();
        for (FrameData frame : frames) {
            if (frame == null) {
                continue;
            }
            if (frame.isWithMatrixErrors()) {
                removedIds.add(frame.getId());
                continue;
            }
            out.add(frame);
        }

        if (!removedIds.isEmpty()) {
            System.out.println("Removed errored frames: " + removedIds);
        }
        return out;
    }
}
