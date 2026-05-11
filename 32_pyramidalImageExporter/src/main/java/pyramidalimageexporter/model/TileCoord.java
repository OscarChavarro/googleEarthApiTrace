package pyramidalimageexporter.model;

import java.util.List;
import java.util.ArrayList;
import pyramidalimageexporter.processing.uncles.ToUncleRelationship;

public final class TileCoord {
    private String id;
    private Integer numericTileId;
    private int i;
    private int j;
    private String textureFile;
    private List<ToUncleRelationship> uncles = new ArrayList<>();

    public String getId() {
        if (id != null && !id.isBlank()) {
            return id.trim();
        }
        if (numericTileId != null) {
            return Integer.toString(numericTileId);
        }
        return "";
    }

    public void setId(String id) {
        this.id = id == null ? null : id.trim();
    }

    public Integer getNumericTileId() {
        if (numericTileId != null) {
            return numericTileId;
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
