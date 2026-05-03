package matrixmerger.io;

import java.util.ArrayList;
import java.util.List;

public class TileMatrix {
    private int frameId;
    private int rows;
    private int cols;
    private List<TileCoord> tiles = new ArrayList<>();

    public TileMatrix() {
    }

    public int getFrameId() {
        return frameId;
    }

    public void setFrameId(int frameId) {
        this.frameId = frameId;
    }

    public int getRows() {
        return rows;
    }

    public void setRows(int rows) {
        this.rows = rows;
    }

    public int getCols() {
        return cols;
    }

    public void setCols(int cols) {
        this.cols = cols;
    }

    public List<TileCoord> getTiles() {
        return tiles;
    }

    public void setTiles(List<TileCoord> tiles) {
        this.tiles = tiles == null ? new ArrayList<>() : tiles;
    }

    public static class TileCoord {
        private int tileId;
        private int i;
        private int j;
        private String textureFile;

        public TileCoord() {
        }

        public int getTileId() {
            return tileId;
        }

        public void setTileId(int tileId) {
            this.tileId = tileId;
        }

        public int getI() {
            return i;
        }

        public void setI(int i) {
            this.i = i;
        }

        public int getJ() {
            return j;
        }

        public void setJ(int j) {
            this.j = j;
        }

        public String getTextureFile() {
            return textureFile;
        }

        public void setTextureFile(String textureFile) {
            this.textureFile = textureFile;
        }
    }
}
