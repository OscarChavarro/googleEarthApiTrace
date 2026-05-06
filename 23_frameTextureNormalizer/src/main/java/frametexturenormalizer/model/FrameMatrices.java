package frametexturenormalizer.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FrameMatrices {
    private final int frameId;
    private final List<TileMatrix> matrices;

    public FrameMatrices(int frameId, List<TileMatrix> matrices) {
        this.frameId = frameId;
        this.matrices = Collections.unmodifiableList(new ArrayList<>(matrices == null ? List.of() : matrices));
    }

    public int getFrameId() {
        return frameId;
    }

    public List<TileMatrix> getMatrices() {
        return matrices;
    }
}
