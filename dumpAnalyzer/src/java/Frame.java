package dumpanalyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class Frame implements Comparable<Frame> {
    private final int id;
    private final List<TileInstance> tiles = new ArrayList<>();

    public Frame(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    public void addTile(TileInstance tile) {
        tiles.add(tile);
    }

    public void sortTilesByContentId() {
        tiles.sort(Comparator.comparingInt(TileInstance::getContentId));
    }

    public List<TileInstance> getTiles() {
        return Collections.unmodifiableList(tiles);
    }

    @Override
    public int compareTo(Frame other) {
        return Integer.compare(this.id, other.id);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Frame frame)) {
            return false;
        }
        return id == frame.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Frame{id=").append(id).append(", tileCount=").append(tiles.size()).append(", tiles=[");
        for (int i = 0; i < tiles.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(tiles.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }
}
