package pyramidalimagebuilder.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class TileMatrix {
    private final int frameId;
    private final int rows;
    private final int cols;
    private final List<TileCoord> tiles;

    public TileMatrix(int frameId, int rows, int cols, List<TileCoord> tiles) {
        this.frameId = frameId;
        this.rows = Math.max(0, rows);
        this.cols = Math.max(0, cols);
        List<TileCoord> copy = new ArrayList<>(tiles == null ? List.of() : tiles);
        copy.sort(Comparator.comparingInt(TileCoord::i).thenComparingInt(TileCoord::j).thenComparingInt(TileCoord::tileId));
        this.tiles = Collections.unmodifiableList(copy);
    }

    public int getFrameId() {
        return frameId;
    }

    public int getRows() {
        return rows;
    }

    public int getCols() {
        return cols;
    }

    public List<TileCoord> getTiles() {
        return tiles;
    }

    public record TileCoord(int tileId, int i, int j, String textureFile) {
    }
}
