package pyramidalimageexporter.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class TopLevelTilesCatalog {
    private Map<String, TopLevelTile> byStripId = new LinkedHashMap<>();

    public Map<String, TopLevelTile> getByStripId() {
        return byStripId;
    }

    public void setByStripId(Map<String, TopLevelTile> byStripId) {
        this.byStripId = byStripId == null ? new LinkedHashMap<>() : new LinkedHashMap<>(byStripId);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class TopLevelTile {
        private int id;
        private List<Integer> pathFromRoot = new ArrayList<>();
        private int row;
        private int col;
        private List<DeDuplicatedVertex> deDuplicatedVertices = new ArrayList<>();
        private List<FrameAppearance> appearances = new ArrayList<>();

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public List<Integer> getPathFromRoot() {
            return pathFromRoot;
        }

        public void setPathFromRoot(List<Integer> pathFromRoot) {
            this.pathFromRoot = pathFromRoot == null ? new ArrayList<>() : new ArrayList<>(pathFromRoot);
        }

        public int getRow() {
            return row;
        }

        public void setRow(int row) {
            this.row = row;
        }

        public int getCol() {
            return col;
        }

        public void setCol(int col) {
            this.col = col;
        }

        public List<DeDuplicatedVertex> getDeDuplicatedVertices() {
            return deDuplicatedVertices;
        }

        public void setDeDuplicatedVertices(List<DeDuplicatedVertex> deDuplicatedVertices) {
            this.deDuplicatedVertices = deDuplicatedVertices == null ? new ArrayList<>() : new ArrayList<>(deDuplicatedVertices);
        }

        public List<FrameAppearance> getAppearances() {
            return appearances;
        }

        public void setAppearances(List<FrameAppearance> appearances) {
            this.appearances = appearances == null ? new ArrayList<>() : new ArrayList<>(appearances);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class DeDuplicatedVertex {
        private double x;
        private double y;
        private double z;
        private int sizeInBytes;

        public double getX() {
            return x;
        }

        public void setX(double x) {
            this.x = x;
        }

        public double getY() {
            return y;
        }

        public void setY(double y) {
            this.y = y;
        }

        public double getZ() {
            return z;
        }

        public void setZ(double z) {
            this.z = z;
        }

        public int getSizeInBytes() {
            return sizeInBytes;
        }

        public void setSizeInBytes(int sizeInBytes) {
            this.sizeInBytes = sizeInBytes;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class FrameAppearance {
        private int frameId;
        private String imageId;
        private String imagePath;
        private TexCoord texCoord;

        public int getFrameId() {
            return frameId;
        }

        public void setFrameId(int frameId) {
            this.frameId = frameId;
        }

        public String getImageId() {
            return imageId;
        }

        public void setImageId(String imageId) {
            this.imageId = imageId;
        }

        public String getImagePath() {
            return imagePath;
        }

        public void setImagePath(String imagePath) {
            this.imagePath = imagePath;
        }

        public TexCoord getTexCoord() {
            return texCoord;
        }

        public void setTexCoord(TexCoord texCoord) {
            this.texCoord = texCoord;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class TexCoord {
        private double u0;
        private double v0;
        private double u1;
        private double v1;

        public double getU0() {
            return u0;
        }

        public void setU0(double u0) {
            this.u0 = u0;
        }

        public double getV0() {
            return v0;
        }

        public void setV0(double v0) {
            this.v0 = v0;
        }

        public double getU1() {
            return u1;
        }

        public void setU1(double u1) {
            this.u1 = u1;
        }

        public double getV1() {
            return v1;
        }

        public void setV1(double v1) {
            this.v1 = v1;
        }
    }
}
