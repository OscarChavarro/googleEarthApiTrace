package pyramidalimagecoverage.model;

import java.nio.file.Path;

public final class TileRecord {
    private final TileAddress address;
    private final Path imagePath;
    private boolean selected;

    public TileRecord(TileAddress address, Path imagePath) {
        this.address = address;
        this.imagePath = imagePath;
    }

    public TileAddress address() {
        return address;
    }

    public Path imagePath() {
        return imagePath;
    }

    public boolean selected() {
        return selected;
    }

    public boolean setSelected(boolean selected) {
        if (this.selected == selected) {
            return false;
        }
        this.selected = selected;
        return true;
    }
}
