package frametexturenormalizer.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class FrameData {
    private final int id;
    private final List<TileInstance> tiles;
    private final List<Line> lines;
    private final GoogleCameraState cameraState;
    private boolean withMatrixErrors;

    public FrameData(int id, List<TileInstance> tiles, GoogleCameraState cameraState) {
        this(id, tiles, List.of(), cameraState, false);
    }

    public FrameData(int id, List<TileInstance> tiles, List<Line> lines, GoogleCameraState cameraState) {
        this(id, tiles, lines, cameraState, false);
    }

    public FrameData(int id, List<TileInstance> tiles, GoogleCameraState cameraState, boolean withMatrixErrors) {
        this(id, tiles, List.of(), cameraState, withMatrixErrors);
    }

    public FrameData(int id, List<TileInstance> tiles, List<Line> lines, GoogleCameraState cameraState, boolean withMatrixErrors) {
        this.id = id;
        List<TileInstance> copy = new ArrayList<>(tiles == null ? List.of() : tiles);
        copy.sort(Comparator.comparingInt(TileInstance::getTileId));
        this.tiles = Collections.unmodifiableList(copy);
        this.lines = lines == null ? List.of() : List.copyOf(lines);
        this.cameraState = cameraState;
        this.withMatrixErrors = withMatrixErrors;
    }

    public int getId() {
        return id;
    }

    public List<TileInstance> getTiles() {
        return tiles;
    }

    public List<Line> getLines() {
        return lines;
    }

    public GoogleCameraState getCameraState() {
        return cameraState;
    }

    public boolean isWithMatrixErrors() {
        return withMatrixErrors;
    }

    public void setWithMatrixErrors(boolean withMatrixErrors) {
        this.withMatrixErrors = withMatrixErrors;
    }
}
