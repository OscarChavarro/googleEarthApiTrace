package matrixmerger.io;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.ArrayList;
import java.util.List;
import processing.uncles.ToUncleRelationship;

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
        private List<ToUncleRelationship> uncles = new ArrayList<>();

        public String getId() {
            if (id != null && !id.isBlank()) {
                String normalized = WestCutterReader.normalizeScopedTileId(id);
                return normalized == null ? id : normalized;
            }
            if (legacyTileId != null) {
                return Integer.toString(legacyTileId);
            }
            return "";
        }

        public void setId(String id) {
            this.id = WestCutterReader.normalizeScopedTileId(id);
        }

        public Integer getNumericTileId() {
            if (legacyTileId != null) {
                return legacyTileId;
            }
            String resolved = getId();
            if (resolved == null || resolved.isBlank()) {
                return null;
            }
            int separator = resolved.lastIndexOf('_');
            String numericPart = separator >= 0 && separator < resolved.length() - 1
                ? resolved.substring(separator + 1)
                : resolved;
            try {
                return Integer.parseInt(numericPart);
            }
            catch (NumberFormatException ex) {
                return null;
            }
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

        public List<ToUncleRelationship> getUncles() {
            return uncles;
        }

        public void setUncles(List<ToUncleRelationship> uncles) {
            this.uncles = uncles == null ? new ArrayList<>() : new ArrayList<>(uncles);
        }
    }
}
