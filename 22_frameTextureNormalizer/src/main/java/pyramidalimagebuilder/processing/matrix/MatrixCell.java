package pyramidalimagebuilder.processing.matrix;

import com.fasterxml.jackson.annotation.JsonIgnore;
import pyramidalimagebuilder.model.TileInstance;

public final class MatrixCell {
    private int i;
    private int j;
    @JsonIgnore
    private final TileInstance tile;

    public MatrixCell(int i, int j, TileInstance tile) {
        this.i = i;
        this.j = j;
        this.tile = tile;
    }

    public int i() {
        return i;
    }

    public int j() {
        return j;
    }

    public int tileId() {
        return tile.getTileId();
    }

    public TileInstance tile() {
        return tile;
    }

    public void shift(int di, int dj) {
        i += di;
        j += dj;
    }
}
