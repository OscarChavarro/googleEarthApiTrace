package pyramidalimagebuilder.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class FrameData {
    private final int id;
    private final List<TileInstance> tiles;
    private final GoogleCameraState cameraState;

    public FrameData(int id, List<TileInstance> tiles, GoogleCameraState cameraState) {
        this.id = id;
        List<TileInstance> copy = new ArrayList<>(tiles == null ? List.of() : tiles);
        copy.sort(Comparator.comparingInt(TileInstance::getTileId));
        this.tiles = Collections.unmodifiableList(copy);
        this.cameraState = cameraState;
    }

    public int getId() {
        return id;
    }

    public List<TileInstance> getTiles() {
        return tiles;
    }

    public GoogleCameraState getCameraState() {
        return cameraState;
    }
}
