package matrixmerger.model.contract;

import java.util.ArrayList;
import java.util.List;

public final class FrameMatrixSet {
    private int frameId;
    private List<FrameTileMatrix> matrices = new ArrayList<>();

    public int getFrameId() {
        return frameId;
    }

    public void setFrameId(int frameId) {
        this.frameId = frameId;
    }

    public List<FrameTileMatrix> getMatrices() {
        return matrices;
    }

    public void setMatrices(List<FrameTileMatrix> matrices) {
        this.matrices = matrices == null ? new ArrayList<>() : new ArrayList<>(matrices);
    }
}
