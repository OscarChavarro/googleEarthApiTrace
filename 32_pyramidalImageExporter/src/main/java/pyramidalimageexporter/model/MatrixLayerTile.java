package pyramidalimageexporter.model;

import java.util.List;
import java.util.ArrayList;
import pyramidalimageexporter.processing.uncles.ToUncleRelationship;

public final class MatrixLayerTile {
    private String id;
    private Integer numericTileId;
    private int i;
    private int j;
    private String textureFile;
    // Texture sub-rectangle in OpenGL convention (v = 0 at image bottom).
    // Defaults cover the whole texture, so tiles from matrixLayer.json are unaffected.
    private double texU0 = 0.0;
    private double texV0 = 0.0;
    private double texU1 = 1.0;
    private double texV1 = 1.0;
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

    public double getTexU0() {
        return texU0;
    }

    public double getTexV0() {
        return texV0;
    }

    public double getTexU1() {
        return texU1;
    }

    public double getTexV1() {
        return texV1;
    }

    public void setTextureSubRect(double u0, double v0, double u1, double v1) {
        this.texU0 = u0;
        this.texV0 = v0;
        this.texU1 = u1;
        this.texV1 = v1;
    }

    public List<ToUncleRelationship> getUncles() {
        return uncles;
    }

    public void setUncles(List<ToUncleRelationship> uncles) {
        this.uncles = uncles == null ? new ArrayList<>() : new ArrayList<>(uncles);
    }
}
