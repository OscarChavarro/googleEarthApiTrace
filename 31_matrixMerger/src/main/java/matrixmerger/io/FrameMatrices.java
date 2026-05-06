package matrixmerger.io;

import java.util.ArrayList;
import java.util.List;

public final class FrameMatrices {
    private int frameId;
    private List<TileMatrix> matrices = new ArrayList<>();

    public int getFrameId() {
        return frameId;
    }

    public void setFrameId(int frameId) {
        this.frameId = frameId;
    }

    public List<TileMatrix> getMatrices() {
        return matrices;
    }

    public void setMatrices(List<TileMatrix> matrices) {
        this.matrices = matrices == null ? new ArrayList<>() : new ArrayList<>(matrices);
    }
}
