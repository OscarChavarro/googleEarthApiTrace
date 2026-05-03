package matrixmerger.io;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.ArrayList;
import java.util.List;

public final class TileMatrix {
    private int frameId;
    private int rows;
    private int cols;
    private List<TileCoord> tiles = new ArrayList<>();

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

    public static final class TileCoord {
        private String id;
        @JsonAlias("tileId")
        private Integer legacyTileId;
        private int i;
        private int j;
        private String textureFile;

        public String getId() {
            if (id != null && !id.isBlank()) {
                return id;
            }
            if (legacyTileId != null) {
                return Integer.toString(legacyTileId);
            }
            return "";
        }

        public void setId(String id) {
            this.id = id;
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
